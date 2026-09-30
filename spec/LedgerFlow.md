# Immutable Double-Entry Ledger Flow

```mermaid
flowchart TD
    Client[API client]
    Client --> Confirm["POST confirm bill payment"]
    Confirm --> PaymentService["Create payment process<br/>awaiting_account_charge"]
    PaymentService --> Accepted["202 Accepted<br/>paymentId"]
    PaymentService --> ChargeRequest["BillPaymentChargeRequested"]
    ChargeRequest --> Bus[("Background internal bus")]

    Bus --> AccountHandler["Banking account handler"]
    AccountHandler --> Ledger{"Account exists?<br/>Sufficient funds?"}
    Ledger -- No --> ChargeRejected["BillPaymentChargeRejected<br/>No ledger entry"]
    Ledger -- Yes --> ChargePosting["Append bill-payment transaction<br/>Debit: Customer<br/>Credit: Biller Clearing"]
    ChargePosting --> ChargeCompleted["BillPaymentChargeCompleted"]
    ChargeRejected --> Bus
    ChargeCompleted --> Bus

    Bus --> PaymentManager["Bill-payment process manager"]
    PaymentManager --> Biller["Settle with biller"]
    Biller -- Success --> Completed["status: completed<br/>BillPaymentCompleted"]
    Completed --> Bus
    Bus --> Audit["Operational audit log"]

    Biller -- Failure --> Reversing["status: reversing<br/>BillPaymentChargeReversalRequested"]
    Reversing --> Bus
    AccountHandler --> Reversal["Append inverse transaction<br/>Debit: Biller Clearing<br/>Credit: Customer"]
    Reversal --> ReversalResult{"Reversal appended?"}
    ReversalResult -- Yes --> Failed["BillPaymentChargeReversed<br/>status: failed"]
    ReversalResult -- No --> Manual["BillPaymentChargeReversalRejected<br/>status: manual_review"]
    Failed --> Bus
    Manual --> Bus

    ChargePosting --> Journal[("Immutable ledger journal")]
    Reversal --> Journal
    Journal --> Balance["Ledger-derived balance"]
    Journal --> Activity["Customer account activity"]

    Client --> StatusAPI["GET payment status"]
    StatusAPI --> PaymentService
```

Bill payment requests account changes through the internal bus. The banking handler alone owns ledger posting, while the payment process manager coordinates biller settlement and compensation.
