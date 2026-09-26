@cart @api
Feature: Shopping bag

  As a signed-in shopper
  I want to add, change and remove items in my bag
  So that I can buy exactly what I want

  Background:
    Given a buyer account exists
    And a product from the catalogue is chosen

  @smoke @regression @security
  Scenario: Adding to the bag requires a session
    When the chosen product is added to the bag without a session
    Then the response status should be 401

  @smoke @regression
  Scenario: A product added to the bag is in the bag
    Given the buyer has a live session
    When the chosen product is added to the bag with quantity 1
    Then the response status should be 200
    And the bag contains the chosen product with quantity 1

  @regression @boundary
  Scenario Outline: Changing the quantity
    Given the buyer has a live session
    And the chosen product is in the bag with quantity 2
    When the bag quantity of the chosen product is set to <quantity>
    Then the bag <outcome>

    Examples: A positive quantity is stored as given
      | quantity | outcome                                       |
      | 1        | contains the chosen product with quantity 1   |
      | 5        | contains the chosen product with quantity 5   |

    Examples: Zero or less removes the line rather than failing
      | quantity | outcome                              |
      | 0        | no longer contains the chosen product |
      | -1       | no longer contains the chosen product |

  @regression
  Scenario: Removing an item
    Given the buyer has a live session
    And the chosen product is in the bag with quantity 1
    When the chosen product is removed from the bag
    Then the response status should be 200
    And the bag no longer contains the chosen product

  @regression @negative
  Scenario: Changing the quantity of something not in the bag
    Given the buyer has a live session
    When the bag quantity of the chosen product is set to 3
    Then the response status should not be 200
    And the response message should be "Bag item not found."

  @regression @security
  Scenario: One shopper's bag is invisible to another
    Given the buyer has a live session
    And the chosen product is in the bag with quantity 1
    And a second buyer with a live session exists
    When the second buyer's bag is requested
    Then the response status should be 200
    And that bag does not contain the chosen product

  @regression @gst
  Scenario: GST can be previewed for the bag once a delivery address is known
    Given the buyer has a live session
    And the buyer has a default delivery address in state "29" with PIN "560001"
    And the chosen product is in the bag with quantity 1
    When the bag GST preview is requested
    Then the response status should be 200

  @regression @gst @negative
  Scenario: A new shopper with no address yet gets a handled answer, not a server error
    Given the buyer has a live session
    And the chosen product is in the bag with quantity 1
    When the bag GST preview is requested
    Then the response status should not be 500
