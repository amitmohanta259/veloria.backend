@product @api
Feature: Browsing the catalogue

  As a shopper
  I want to see products and their details
  So that I can decide what to buy

  @smoke @regression
  Scenario: New arrivals are listed with the details a card needs
    When the new-in products are requested without a session
    Then the response status should be 200
    And the product list is not empty
    And every product has a uuid, a name and a price

  @regression
  Scenario: Popular products are paged
    When popular products page 0 of size 20 are requested
    Then the response status should be 200
    And at most 20 products are returned

  @smoke @regression
  Scenario: A product can be opened by its id
    Given a product from the catalogue is chosen
    When that product's detail is requested
    Then the response status should be 200
    And the detail is for the chosen product

  @regression @negative
  Scenario: An unknown product id is not found
    When the detail of a product that does not exist is requested
    Then the response status should not be 200
    And the response message should not be empty

  @regression
  Scenario: Categories are listed
    When the categories are requested without a session
    Then the response status should be 200
    And the category list is not empty
