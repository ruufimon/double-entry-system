package com.example.banking.domain

import java.time.Instant
import java.util.UUID

import com.example.messaging.InternalMessage

final case class BillPaymentChargeRequested(
    paymentId: UUID,
    accountId: AccountId,
    amount: Money,
    occurredAt: Instant
) extends InternalMessage

final case class BillPaymentChargeCompleted(
    paymentId: UUID,
    accountId: AccountId,
    amount: Money,
    resultingBalance: BigDecimal,
    occurredAt: Instant
) extends InternalMessage

final case class BillPaymentChargeRejected(
    paymentId: UUID,
    accountId: AccountId,
    errorCode: String,
    errorMessage: String,
    occurredAt: Instant
) extends InternalMessage

final case class BillPaymentChargeReversalRequested(
    paymentId: UUID,
    reversalId: UUID,
    accountId: AccountId,
    occurredAt: Instant
) extends InternalMessage

final case class BillPaymentChargeReversed(
    paymentId: UUID,
    reversalId: UUID,
    accountId: AccountId,
    resultingBalance: BigDecimal,
    occurredAt: Instant
) extends InternalMessage

final case class BillPaymentChargeReversalRejected(
    paymentId: UUID,
    reversalId: UUID,
    accountId: AccountId,
    errorCode: String,
    errorMessage: String,
    occurredAt: Instant
) extends InternalMessage
