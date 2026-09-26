@cart @api
Feature: Favourites

  Background:
    Given a buyer account exists
    And a product from the catalogue is chosen

  @regression @security
  Scenario: Favourites require a session
    When the favourites are requested without a session
    Then the response status should be 401

  @regression
  Scenario: A favourite can be added and removed
    Given the buyer has a live session
    When the chosen product is marked as a favourite
    Then the response status should be 200
    And the favourites contain the chosen product
    When the chosen product is unmarked as a favourite
    Then the favourites do not contain the chosen product
