package com.example.banking.ledger.infrastructure

import java.sql.{Connection, PreparedStatement, ResultSet}
import java.time.Instant
import java.util.UUID

import cats.data.NonEmptyVector
import com.example.banking.domain.*
import com.example.banking.ledger.*
import com.example.domain.DomainError
import com.example.infrastructure.Database

final class H2LedgerRepository(database: Database) extends LedgerRepository:
  override def balance(accountId: AccountId): BigDecimal =
    database.withConnection(connection => readBalance(connection, accountId))

  override def snapshot(accountId: AccountId): AccountLedgerSnapshot =
    database.withConnection { connection =>
      val activities = readActivities(connection, accountId)
      AccountLedgerSnapshot(
        balance = activities.lastOption.fold(BigDecimal(0))(_.balanceAfter),
        activities = activities
      )
    }

  override def post(transaction: LedgerTransaction): Either[DomainError, Account] =
    if transaction.operation == BankingOperation.Transfer then
      Left(LedgerError.InvalidTransaction("Transfers must use atomic transfer posting"))
    else if transaction.operation == BankingOperation.Adjustment then
      Left(LedgerError.InvalidTransaction("Adjustments must be posted with a memo"))
    else
      database.withTransaction(connection => runAppend(connection, transaction))

  override def adjust(
      transaction: LedgerTransaction,
      memo: AdjustmentMemo
  ): Either[DomainError, Account] =
    database.withTransaction { connection =>
      lockCustomerEntryAccounts(connection, transaction)
      for
        _ <- transaction.adjustsTransactionId.fold(Right(())) { adjustedId =>
          checkAdjustable(connection, adjustedId, transaction)
        }
        account <- runAppend(connection, transaction)
      yield
        insertMemo(connection, transaction.transactionId, memo)
        account
    }

  override def adjustmentMemos(accountId: AccountId): Map[UUID, AdjustmentMemo] =
    database.withConnection { connection =>
      val statement = connection.prepareStatement(
        """
        SELECT a.transaction_id, a.reason, a.actor
        FROM balance_adjustments a
        JOIN ledger_entries e
          ON e.transaction_id = a.transaction_id AND e.customer_account_id = ?
        """
      )
      try
        statement.setString(1, accountId.value)
        val rows = statement.executeQuery()
        val builder = Map.newBuilder[UUID, AdjustmentMemo]
        while rows.next() do
          builder += UUID.fromString(rows.getString(1)) -> AdjustmentMemo(
            AdjustmentReason.unsafe(rows.getString(2)),
            AdminActor.unsafe(rows.getString(3))
          )
        builder.result()
      finally statement.close()
    }

  private def checkAdjustable(
      connection: Connection,
      adjustedId: UUID,
      adjustment: LedgerTransaction
  ): Either[DomainError, Unit] =
    val accountId = adjustment.entries.collectFirst {
      case LedgerEntry(LedgerAccount.Customer(accountId), _, _, _) => accountId
    }.get
    findTransaction(connection, adjustedId) match
      case None => Left(BankingError.AdjustedTransactionNotFound(adjustedId))
      case Some(original)
          if !original.entries.exists(_.account == LedgerAccount.Customer(accountId)) =>
        Left(BankingError.AdjustedTransactionAccountMismatch(adjustedId, accountId.value))
      case Some(_) => Right(())

  private def insertMemo(connection: Connection, transactionId: UUID, memo: AdjustmentMemo): Unit =
    val statement = connection.prepareStatement(
      "INSERT INTO balance_adjustments (transaction_id, reason, actor) VALUES (?, ?, ?)"
    )
    try
      statement.setString(1, transactionId.toString)
      statement.setString(2, memo.reason.value)
      statement.setString(3, memo.actor.value)
      statement.executeUpdate()
    finally statement.close()

  override def transfer(
      transaction: LedgerTransaction,
      sourceAccountId: AccountId,
      destinationAccountId: AccountId
  ): Either[DomainError, TransferAccounts] =
    entryFor(transaction, sourceAccountId) match
      case None                       =>
        Left(LedgerError.InvalidTransaction("A transfer requires a source debit and destination credit"))
      case Some(source) if source.direction != LedgerDirection.Debit =>
        Left(LedgerError.InvalidTransaction("A transfer requires a source debit and destination credit"))
      case Some(source) =>
        entryFor(transaction, destinationAccountId) match
          case None                            =>
            Left(LedgerError.InvalidTransaction("A transfer requires a source debit and destination credit"))
          case Some(destination) if destination.direction != LedgerDirection.Credit =>
            Left(LedgerError.InvalidTransaction("A transfer requires a source debit and destination credit"))
          case Some(_) =>
            database.withTransaction { connection =>
              lockAccounts(connection, sourceAccountId, destinationAccountId)
              if transactionExists(connection, transaction.transactionId) then
                Left(LedgerError.DuplicateTransaction(transaction.transactionId))
              else
                val sourceBalance = readBalance(connection, sourceAccountId)
                if sourceBalance < source.amount.amount then
                  Left(BankingError.InsufficientFunds(sourceBalance, source.amount.amount))
                else
                  insertTransaction(connection, transaction)
                  Right(
                    TransferAccounts(
                      source = Account(sourceAccountId, sourceBalance - source.amount.amount),
                      destination = Account(
                        destinationAccountId,
                        readBalance(connection, destinationAccountId)
                      )
                    )
                  )
            }

  private def entryFor(transaction: LedgerTransaction, accountId: AccountId): Option[LedgerEntry] =
    transaction.entries.toVector.find(_.account == LedgerAccount.Customer(accountId))

  override def reverse(
      originalTransactionId: UUID,
      reversalTransactionId: UUID,
      occurredAt: Instant
  ): Either[DomainError, Account] =
    database.withTransaction { connection =>
      val original = findTransaction(connection, originalTransactionId)
      original match
        case None =>
          Left(LedgerError.TransactionNotFound(originalTransactionId))
        case Some(found) if anyReverses(connection, originalTransactionId) =>
          Left(LedgerError.TransactionAlreadyReversed(originalTransactionId))
        case Some(found) =>
          LedgerTransaction.reversal(reversalTransactionId, found, occurredAt) match
            case Left(error)          => Left(error)
            case Right(reversal)     =>
              lockCustomerEntryAccounts(connection, reversal)
              runAppend(connection, reversal)
    }

  override def activities(accountId: AccountId): Vector[AccountActivity] =
    database.withConnection(connection => readActivities(connection, accountId))

  def transactions: Vector[LedgerTransaction] =
    database.withConnection { connection =>
      val statement =
        connection.prepareStatement(
          "SELECT transaction_id FROM ledger_transactions ORDER BY sequence_no"
        )
      try
        val rows = statement.executeQuery()
        val builder = Vector.newBuilder[LedgerTransaction]
        while rows.next() do
          findTransaction(connection, UUID.fromString(rows.getString(1))).foreach(builder += _)
        builder.result()
      finally statement.close()
    }

  private def runAppend(
      connection: Connection,
      transaction: LedgerTransaction
  ): Either[DomainError, Account] =
    transaction.entries.collectFirst {
      case entry @ LedgerEntry(LedgerAccount.Customer(accountId), _, _, _) => accountId -> entry
    } match
      case None => Left(LedgerError.InvalidTransaction("A ledger transaction requires a customer entry"))
      case Some((accountId, customerEntry)) =>
        if transactionExists(connection, transaction.transactionId) then
          Left(LedgerError.DuplicateTransaction(transaction.transactionId))
        else
          lockAccount(connection, accountId)
          val currentBalance = readBalance(connection, accountId)
          customerEntry.direction match
            case LedgerDirection.Debit if currentBalance < customerEntry.amount.amount =>
              Left(BankingError.InsufficientFunds(currentBalance, customerEntry.amount.amount))
            case _ =>
              insertTransaction(connection, transaction)
              Right(Account(accountId, currentBalance + applyDelta(customerEntry)))

  private def applyDelta(entry: LedgerEntry): BigDecimal =
    entry.direction match
      case LedgerDirection.Credit => entry.amount.amount
      case LedgerDirection.Debit  => -entry.amount.amount

  private def lockAccounts(
      connection: Connection,
      first: AccountId,
      second: AccountId
  ): Unit =
    val ordered = Vector(first, second).sortBy(_.value)
    ordered.foreach(lockAccount(connection, _))

  private def lockCustomerEntryAccounts(connection: Connection, transaction: LedgerTransaction): Unit =
    transaction.entries
      .collect { case entry @ LedgerEntry(LedgerAccount.Customer(accountId), _, _, _) => accountId }
      .distinct
      .sortBy(_.value)
      .foreach(lockAccount(connection, _))

  private def lockAccount(connection: Connection, accountId: AccountId): Unit =
    val statement =
      connection.prepareStatement("SELECT 1 FROM accounts WHERE account_id = ? FOR UPDATE")
    try
      statement.setString(1, accountId.value)
      statement.executeQuery().next()
    finally statement.close()

  private def transactionExists(connection: Connection, transactionId: UUID): Boolean =
    val statement =
      connection.prepareStatement("SELECT 1 FROM ledger_transactions WHERE transaction_id = ?")
    try
      statement.setString(1, transactionId.toString)
      statement.executeQuery().next()
    finally statement.close()

  private def anyReverses(connection: Connection, transactionId: UUID): Boolean =
    val statement =
      connection.prepareStatement("SELECT 1 FROM ledger_transactions WHERE reverses_transaction_id = ?")
    try
      statement.setString(1, transactionId.toString)
      statement.executeQuery().next()
    finally statement.close()

  private def readBalance(connection: Connection, accountId: AccountId): BigDecimal =
    val statement = connection.prepareStatement(
      """
      SELECT COALESCE(SUM(CASE WHEN e.direction = 'Credit' THEN e.amount ELSE -e.amount END), 0)
      FROM ledger_entries e WHERE e.customer_account_id = ?
      """
    )
    try
      statement.setString(1, accountId.value)
      val rows = statement.executeQuery()
      if rows.next() then rows.getBigDecimal(1)
      else BigDecimal(0)
    finally statement.close()

  private def readActivities(
      connection: Connection,
      accountId: AccountId
  ): Vector[AccountActivity] =
    val statement = connection.prepareStatement(
      """
      SELECT
        t.transaction_id, t.occurred_at, t.operation,
        COALESCE(t.reverses_transaction_id, t.adjusts_transaction_id),
        e.direction, e.amount, e.currency,
        CASE WHEN EXISTS (SELECT 1 FROM ledger_transactions r
              WHERE r.reverses_transaction_id = t.transaction_id)
          THEN 'Reversed' ELSE 'Posted' END,
        (SELECT e2.customer_account_id FROM ledger_entries e2
          WHERE e2.transaction_id = t.transaction_id
            AND e2.customer_account_id <> ?
          LIMIT 1)
      FROM ledger_transactions t
      JOIN ledger_entries e
        ON e.transaction_id = t.transaction_id AND e.customer_account_id = ?
      ORDER BY t.sequence_no
      """
    )
    try
      statement.setString(1, accountId.value)
      statement.setString(2, accountId.value)
      val rows = statement.executeQuery()
      val builder = Vector.newBuilder[AccountActivity]
      var runningBalance = BigDecimal(0)
      while rows.next() do
        val direction = LedgerDirection.valueOf(rows.getString(5))
        val amount = BigDecimal(rows.getBigDecimal(6))
        runningBalance = runningBalance + (amount *
          (if direction == LedgerDirection.Credit then 1 else -1))
        builder += AccountActivity(
          transactionId = UUID.fromString(rows.getString(1)),
          operation = BankingOperation.unsafe(rows.getString(3)),
          effect = direction match
            case LedgerDirection.Credit => BalanceEffect.Increase
            case LedgerDirection.Debit  => BalanceEffect.Decrease,
          amount = amount,
          currency = Currency.unsafe(rows.getString(7)),
          balanceAfter = runningBalance,
          occurredAt = rows.getTimestamp(2).toInstant(),
          status = AccountActivityStatus.unsafe(rows.getString(8)),
          originalTransactionId = Option(rows.getString(4)).map(UUID.fromString),
          counterpartyAccountId = Option(rows.getString(9)).map(AccountId.unsafe)
        )
      builder.result()
    finally statement.close()

  private def findTransaction(
      connection: Connection,
      transactionId: UUID
  ): Option[LedgerTransaction] =
    val statement = connection.prepareStatement(
      "SELECT operation, occurred_at, reverses_transaction_id, adjusts_transaction_id FROM ledger_transactions WHERE transaction_id = ?"
    )
    try
      statement.setString(1, transactionId.toString)
      val rows = statement.executeQuery()
      if !rows.next() then None
      else
        Some(
          LedgerTransaction.unsafe(
            transactionId,
            BankingOperation.unsafe(rows.getString(1)),
            NonEmptyVector.fromVectorUnsafe(readEntries(connection, transactionId)),
            rows.getTimestamp(2).toInstant(),
            Option(rows.getString(3)).map(UUID.fromString),
            Option(rows.getString(4)).map(UUID.fromString)
          )
        )
    finally statement.close()

  private def readEntries(connection: Connection, transactionId: UUID): Vector[LedgerEntry] =
    val statement = connection.prepareStatement(
      "SELECT account, customer_account_id, direction, amount, currency FROM ledger_entries WHERE transaction_id = ? ORDER BY entry_index"
    )
    try
      statement.setString(1, transactionId.toString)
      val rows = statement.executeQuery()
      val builder = Vector.newBuilder[LedgerEntry]
      while rows.next() do
        val (accountRaw, customerRaw) = (rows.getString(1), Option(rows.getString(2)))
        val account: LedgerAccount = customerRaw match
          case Some(customerId) => LedgerAccount.Customer(AccountId.unsafe(customerId))
          case None =>
            accountRaw match
              case "Cash"         => LedgerAccount.Cash
              case "BillerClearing" => LedgerAccount.BillerClearing
              case "Adjustments"    => LedgerAccount.Adjustments
              case _ => throw new IllegalStateException(s"Unknown ledger account: $accountRaw")
        builder += LedgerEntry(
          account,
          LedgerDirection.valueOf(rows.getString(3)),
          Money.of(rows.getBigDecimal(4)),
          Currency.unsafe(rows.getString(5))
        )
      builder.result()
    finally statement.close()

  private def insertTransaction(connection: Connection, transaction: LedgerTransaction): Unit =
    val txStatement =
      connection.prepareStatement(
        "INSERT INTO ledger_transactions (transaction_id, operation, occurred_at, reverses_transaction_id, adjusts_transaction_id) VALUES (?, ?, ?, ?, ?)"
      )
    val entryStatement =
      connection.prepareStatement(
        "INSERT INTO ledger_entries (transaction_id, entry_index, account, customer_account_id, direction, amount, currency) VALUES (?, ?, ?, ?, ?, ?, ?)"
      )
    try
      txStatement.setString(1, transaction.transactionId.toString)
      txStatement.setString(2, transaction.operation.toString)
      txStatement.setTimestamp(3, java.sql.Timestamp.from(transaction.occurredAt))
      transaction.reversesTransactionId match
        case Some(original) => txStatement.setString(4, original.toString)
        case None           => txStatement.setString(4, null)
      txStatement.setString(5, transaction.adjustsTransactionId.map(_.toString).orNull)
      txStatement.executeUpdate()

      var index = 0
      transaction.entries.toVector.foreach { entry =>
        val (accountRaw, customerRaw): (String, Option[String]) = entry.account match
          case LedgerAccount.Customer(accountId) => ("Customer", Some(accountId.value))
          case LedgerAccount.Cash                => ("Cash", None)
          case LedgerAccount.BillerClearing      => ("BillerClearing", None)
          case LedgerAccount.Adjustments         => ("Adjustments", None)
        entryStatement.setString(1, transaction.transactionId.toString)
        entryStatement.setInt(2, index)
        entryStatement.setString(3, accountRaw)
        entryStatement.setString(4, customerRaw.orNull)
        entryStatement.setString(5, entry.direction.toString)
        entryStatement.setBigDecimal(6, entry.amount.amount.bigDecimal)
        entryStatement.setString(7, entry.currency.code)
        entryStatement.executeUpdate()
        index += 1
      }
    finally
      txStatement.close()
      entryStatement.close()
