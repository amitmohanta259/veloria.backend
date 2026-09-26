@order @api
Feature: Placing an order

  As a signed-in shopper
  I want to place an order for what is in my bag
  So that the store records exactly what I bought and the tax on it

  Background:
    Given a buyer account exists
    And the buyer has a live session
    And a product from the catalogue is chosen

  @regression @mutates @database @gst
  Scenario: A valid order is recorded with its lines and its GST
    When the buyer places an order for 2 of the chosen product delivered to "12 MG Road, Bengaluru, Karnataka 560001"
    Then the response status should be 200
    And an order code is returned
    And the order is stored with 1 line of quantity 2
    And output GST is recorded for the order
    And the recorded tax matches the supply type

  @regression @negative
  Scenario: An order with nothing in it is refused
    When the buyer places an order with no items delivered to "Bengaluru, Karnataka 560001"
    Then the response status should be 400

  @regression @negative @boundary
  Scenario Outline: Quantity must be at least one
    When the buyer places an order for <quantity> of the chosen product delivered to "Bengaluru, Karnataka 560001"
    Then the response status should be 400

    Examples:
      | quantity |
      | 0        |
      | -1       |

  @regression @negative
  Scenario: A delivery location is required
    When the buyer places an order for 1 of the chosen product with no delivery location
    Then the response status should be 400
