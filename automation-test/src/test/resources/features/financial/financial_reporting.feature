@financial @api
Feature: Financial reporting integrity

  As the finance team
  I want every figure to come from the same books
  So that statements, GST and indicators can never disagree

  @smoke @regression
  Scenario: The dashboard reports live figures
    When the dashboard summary is requested
    Then the response status should be 200
    And total orders is at least active orders

  @regression @database
  Scenario: The trial balance balances
    When the trial balance is requested
    Then the response status should be 200
    And total debits equal total credits

  @regression
  Scenario: Reconciliation has no failing check
    When the reconciliation report is requested
    Then the response status should be 200
    And no reconciliation check is FAIL

  @regression
  Scenario: Indicators never present a number they cannot support
    When the indicators are requested
    Then the response status should be 200
    And every indicator marked not available or not meaningful has no value
