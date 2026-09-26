@gst @api
Feature: GST reference data

  Background:
    Given an admin is signed in to the GST module as "GST_ADMIN"

  @smoke @regression
  Scenario: The configured tax rules are available
    When the GST rules are requested
    Then the response status should be 200
    And at least 8 active rules are returned

  @regression
  Scenario: The HSN master can be searched
    When the HSN master is searched
    Then the response status should be 200
    And the HSN list is not empty
