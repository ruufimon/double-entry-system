Feature: Admin configures monthly interest
  The authenticated admin configures one annual rate for all accounts.
  The rate applies from the next Bangkok calendar month, and monthly interest
  is calculated from each day's closing balance and credited to the ledger.

  Background:
    Given the business timezone is Asia/Bangkok
    And the current annual interest rate is 0.00 percent
    And monthly interest uses daily closing balances

  Scenario: Review a rate change before saving it
    Given the admin is authenticated
    And the admin UI clock is fixed at 28 October 2026
    When the admin opens Interest rate settings
    And enters an annual rate of 3.65 percent
    Then the review shows the current rate as 0.00 percent
    And the review shows the new rate as 3.65 percent
    And the review shows an effective date of 1 November 2026
    And the review says the rate applies to all accounts
    And the review says interest is calculated daily and credited monthly
    And no rate change has been saved yet

  Scenario: Save a reviewed rate change
    Given the admin UI clock is fixed at 28 October 2026
    And the admin has reviewed an annual rate of 3.65 percent
    When the admin saves the rate
    Then the rate change is persisted
    And the current rate remains 0.00 percent through October 2026
    And the 3.65 percent rate is scheduled from 1 November 2026
    And the rate history shows the new rate as Scheduled
    And the rate history keeps the existing rate as Current

  Scenario: Reject an invalid rate
    Given the admin is reviewing a new rate
    When the admin enters a negative rate or a value with more than two decimal places
    And attempts to save
    Then the rate is rejected with a clear validation message
    And no rate change is persisted

  Scenario: Reject rate changes without admin authentication
    Given the admin is not authenticated with the configured admin password
    When a rate change is submitted
    Then the request is rejected
    And the configured rate and rate history remain unchanged

  Scenario: Calculate interest for a stable positive balance
    Given the annual rate for April 2027 is 3.65 percent
    And an account has a closing balance of THB 10,000.00 for all 30 days of April
    When April 2027 interest is processed
    Then the account receives THB 30.00 interest
    And the ledger contains a balanced interest credit for the account
    And the account activity shows the interest credit

  Scenario: Calculate interest when the daily balance changes
    Given the annual rate for April 2027 is 3.65 percent
    And an account's closing balance is THB 10,000.00 for 15 days of April
    And its closing balance is THB 20,000.00 for the remaining 15 days
    When April 2027 interest is processed
    Then the account receives THB 45.00 interest

  Scenario: A transaction affects that day's closing balance
    Given the annual rate for April 2027 is 3.65 percent
    And an account has a closing balance of THB 10,000.00 through 15 April
    When THB 10,000.00 is deposited on 16 April
    And April 2027 interest is processed
    Then the deposit affects the closing balance for 16 April
    And the account receives THB 45.00 interest for April

  Scenario: Use the leap-year denominator
    Given the annual rate for February 2028 is 3.65 percent
    And an account has a closing balance of THB 10,000.00 for all 29 days of February
    When February 2028 interest is processed
    Then the calculation uses a 366-day year
    And the account receives THB 28.92 interest

  Scenario: Do not accrue interest for non-positive daily balances
    Given the annual rate for April 2027 is 3.65 percent
    And an account has zero or negative closing balances for every day of April
    When April 2027 interest is processed
    Then the account receives no interest credit

  Scenario: Reprocessing a month does not issue a duplicate credit
    Given an account has already received its April 2027 interest credit
    When April 2027 interest processing runs again
    Then the account has only one April 2027 interest credit
    And its balance is not increased a second time

  Scenario: Catch up a completed month after service downtime
    Given the service was unavailable while April 2027 interest was due
    When the service resumes in May 2027
    Then April 2027 interest is processed automatically
    And each account receives at most one April 2027 interest credit
