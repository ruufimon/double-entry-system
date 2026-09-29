package com.example.banking.domain

final case class AccountId private (value: String) derives CanEqual

object AccountId:
  private val ValidAccountId = "[A-Za-z0-9_-]{1,64}".r

  def from(rawAccountId: String): Either[BankingError, AccountId] =
    rawAccountId.trim match
      case ValidAccountId() => Right(AccountId(rawAccountId.trim))
      case _                => Left(BankingError.InvalidAccountId)

final case class Money private (amount: BigDecimal) derives CanEqual

object Money:
  def positive(amount: BigDecimal): Either[BankingError, Money] =
    if amount <= 0 then
      Left(BankingError.InvalidDepositAmount("Deposit amount must be greater than zero"))
    else if amount.scale > 2 then
      Left(BankingError.InvalidDepositAmount("Deposit amount must have at most two decimal places"))
    else
      Right(Money(amount))

final case class Account(id: AccountId, balance: BigDecimal) derives CanEqual

enum BankingError(val code: String, val message: String) derives CanEqual:
  case InvalidAccountId
      extends BankingError(
        "invalid_account_id",
        "Account ID must contain 1 to 64 letters, numbers, underscores, or hyphens"
      )
  case InvalidDepositAmount(details: String)
      extends BankingError("invalid_deposit_amount", details)
  case InvalidRequest(details: String)
      extends BankingError("invalid_request", details)
