@inventory @order @api @mutates
Feature: Inventory is respected at checkout

  As the business
  I want an order to be refused when the stock is not there
  So that we never sell what we cannot ship

  Background:
    Given a buyer account exists
    And the buyer has a live session

  @smoke @regression
  Scenario: Buying what is in stock reduces what is left
    Given a product with 10 units in stock
    When the buyer places an order for 3 of that product
    Then the response status should be 200
    And the product has 7 units left

  @regression @negative @boundary
  Scenario: A customer cannot order more than is available
    Given a product with 3 units in stock
    When the buyer places an order for 4 of that product
    Then the response status should be 400
    And the product has 3 units left
    And no order was recorded for the buyer

  @regression @boundary
  Scenario: The last units can be bought, and then there are none
    Given a product with 5 units in stock
    When the buyer places an order for 5 of that product
    Then the response status should be 200
    And the product has 0 units left
    When the buyer places an order for 1 of that product
    Then the response status should be 400
    And the product has 0 units left

  @regression @negative
  Scenario: An order is refused whole when one of its products is short
    Given a product "A" with 10 units in stock
    And a product "B" with 1 units in stock
    When the buyer places an order for 2 of "A" and 2 of "B"
    Then the response status should be 400
    And product "A" has 10 units left
    And product "B" has 1 units left
    And no order was recorded for the buyer
