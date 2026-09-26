@order @idempotency @api @mutates
Feature: A retried checkout does not become a second order

  As a shopper whose payment appeared to hang
  I want pressing Pay again to give me the order I already placed
  So that I am not charged for two orders and not sold the same stock twice

  Placing an order is what deducts stock, so a duplicate order is not an untidy
  record — it is a second deduction. The shopper's browser sends one reference
  per checkout attempt and reuses it unchanged on a retry.

  True concurrency is proved against PostgreSQL in
  OrderIdempotencyPostgresTest; these scenarios cover the API contract.

  Background:
    Given a buyer account exists
    And the buyer has a live session

  @smoke @regression
  Scenario: Submitting the same checkout twice returns the one order
    Given a product with 10 units in stock
    When the buyer checks out 2 of that product under a new checkout reference
    Then the response status should be 200
    When the buyer submits the same checkout again
    Then the response status should be 200
    And both responses name the same order
    And exactly 1 order exists for that checkout reference
    And the product has 8 units left

  @regression
  Scenario: Submitting the same checkout five times still leaves one order
    Given a product with 10 units in stock
    When the buyer checks out 1 of that product under a new checkout reference
    Then the response status should be 200
    When the buyer submits the same checkout 5 more times
    Then exactly 1 order exists for that checkout reference
    And the product has 9 units left

  @regression @boundary
  Scenario: A duplicate of a checkout that took the last unit is not told it is out of stock
    Given a product with 1 units in stock
    When the buyer checks out 1 of that product under a new checkout reference
    Then the response status should be 200
    When the buyer submits the same checkout again
    Then the response status should be 200
    And both responses name the same order
    And the product has 0 units left

  @regression
  Scenario: Two separate checkouts are two separate orders
    Given a product with 10 units in stock
    When the buyer checks out 1 of that product under a new checkout reference
    Then the response status should be 200
    When the buyer checks out 1 of that product under a new checkout reference
    Then the response status should be 200
    And the two responses name different orders
    And the product has 8 units left

  @regression @negative
  Scenario: Reusing a reference for a different bag is refused
    Given a product with 10 units in stock
    When the buyer checks out 1 of that product under a new checkout reference
    Then the response status should be 200
    When the buyer submits that reference with 3 of that product instead
    Then the response status should be 400
    And exactly 1 order exists for that checkout reference
    And the product has 9 units left

  @regression @negative @security
  Scenario: Another shopper cannot use someone else's checkout reference
    Given a second buyer with a live session exists
    And a product with 10 units in stock
    When the buyer checks out 1 of that product under a new checkout reference
    Then the response status should be 200
    When the second buyer submits that same checkout reference
    Then the response status should be 400
    And the refusal reveals nothing about the other shopper's order
    And exactly 1 order exists for that checkout reference

  @regression
  Scenario: A checkout sent without a reference behaves exactly as before
    Given a product with 10 units in stock
    When the buyer checks out 1 of that product with no checkout reference
    Then the response status should be 200
    When the buyer checks out 1 of that product with no checkout reference
    Then the response status should be 200
    And the two responses name different orders
    And the product has 8 units left
