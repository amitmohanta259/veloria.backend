@order @lifecycle @api @mutates
Feature: An order moves through its lifecycle only where the business says it can

  As the business
  I want the backend to decide which order transitions are legal and who may make them
  So that the rules hold even when the request does not come from the admin screen

  Before P0-5A these endpoints were reachable without any token, and the backend
  wrote whatever status string it was handed. The admin screen showed the right
  buttons, but it was the only thing enforcing the sequence.

  Background:
    Given a buyer account exists
    And the buyer has a live session
    And a product with 10 units in stock

  @regression @security @negative
  Scenario: Advancing an order requires a signed-in administrator
    Given the buyer places an order for 1 of that product
    When the order status is changed to "PACKED" with no credentials
    Then the response status should be 403
    And the order is still "ORDER_PLACED"

  @regression @security @negative
  Scenario: Read-only access does not carry the right to move an order
    Given the buyer places an order for 1 of that product
    And an admin is signed in to the GST module as "GST_VIEWER"
    When the order status is changed to "PACKED"
    Then the response status should be 403
    And the order is still "ORDER_PLACED"

  @regression @security @negative
  Scenario: Order figures are not readable without credentials
    When the sales statistics are requested with no credentials
    Then the response status should be 403

  @smoke @regression
  Scenario: An administrator walks an order through delivery
    Given the buyer places an order for 1 of that product
    And an admin is signed in to the GST module as "GST_ADMIN"
    When the order status is changed to "PACKED"
    Then the response status should be 200
    When the order status is changed to "IN_TRANSIT"
    Then the response status should be 200
    When the order status is changed to "OUT_FOR_DELIVERY"
    Then the response status should be 200
    And the product has 9 units left
    When the order status is changed to "DELIVERED"
    Then the response status should be 200
    And the order is still "DELIVERED"
    And the product has 9 units left

  @regression @negative
  Scenario: A status the application does not know is refused
    Given the buyer places an order for 1 of that product
    And an admin is signed in to the GST module as "GST_ADMIN"
    When the order status is changed to "SHIPPED_TO_MARS"
    Then the response status should be 400
    And the order is still "ORDER_PLACED"

  @regression @negative
  Scenario: The delivery sequence cannot be skipped
    Given the buyer places an order for 1 of that product
    And an admin is signed in to the GST module as "GST_ADMIN"
    When the order status is changed to "DELIVERED"
    Then the response status should be 400
    And the order is still "ORDER_PLACED"

  @regression
  Scenario: Repeating a transition that already happened changes nothing
    Given the buyer places an order for 1 of that product
    And an admin is signed in to the GST module as "GST_ADMIN"
    When the order status is changed to "PACKED"
    Then the response status should be 200
    When the order status is changed to "PACKED"
    Then the response status should be 200
    And the order is still "PACKED"
    And the product has 9 units left

  @regression @negative
  Scenario: A cancelled order cannot be quietly brought back
    Given the buyer places an order for 1 of that product
    And an admin is signed in to the GST module as "GST_ADMIN"
    When the order is cancelled with reason "customer changed their mind"
    Then the response status should be 200
    And the product has 10 units left
    When the order status is changed to "PACKED"
    Then the response status should be 400
    And the order is still "CANCELLED"
    And the product has 10 units left

  @regression
  Scenario: An administrator can stop an order that has already shipped
    # Widened by the approved P0-7 cancellation policy. Before it, dispatch was
    # the point of no return; an administrator may now stop an order in transit,
    # and the unit returns to availability when they do.
    Given the buyer places an order for 1 of that product
    And an admin is signed in to the GST module as "GST_ADMIN"
    When the order status is changed to "PACKED"
    And the order status is changed to "IN_TRANSIT"
    When the order is cancelled with reason "OPERATIONAL_ISSUE"
    Then the response status should be 200
    And the order is still "CANCELLED"
    And the product has 10 units left

  @regression @negative
  Scenario: A delivered order can never be cancelled
    Given the buyer places an order for 1 of that product
    And an admin is signed in to the GST module as "GST_ADMIN"
    When the order status is changed to "PACKED"
    And the order status is changed to "IN_TRANSIT"
    And the order status is changed to "OUT_FOR_DELIVERY"
    And the order status is changed to "DELIVERED"
    When the order is cancelled with reason "ADMIN_REQUEST"
    Then the response status should be 400
    And the order is still "DELIVERED"
    And the product has 9 units left
