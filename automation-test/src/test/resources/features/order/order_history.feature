@order @api @mutates
Feature: Order history

  Background:
    Given a buyer account exists
    And the buyer has a live session
    And a product from the catalogue is chosen
    And the buyer has placed an order for 1 of the chosen product delivered to "Ranchi, Jharkhand 834001"

  @regression
  Scenario: A placed order appears in the shopper's history
    When the order history is requested
    Then the response status should be 200
    And the history contains the placed order

  @regression
  Scenario: A placed order can be opened by its code
    When the placed order's detail is requested
    Then the response status should be 200
    And the detail shows the placed order

  @regression @security
  Scenario: Another shopper cannot open the order
    Given a second buyer with a live session exists
    When the second buyer requests the placed order's detail
    Then the response status should not be 200

  @regression @negative
  Scenario: A return cannot be requested before delivery
    When the buyer requests a return on the placed order
    Then the response status should be 400
    And the response message should be "Only delivered orders can be returned"
