@return @api
Feature: Returns overview

  @regression @security
  Scenario: Returns data requires the GST permission
    When the returns statistics are requested without a token
    Then the response status should be 401

  @regression
  Scenario: The returns figures agree with each other
    Given an admin is signed in to the GST module as "GST_ADMIN"
    When the returns statistics are requested
    Then the response status should be 200
    And the return rate equals returns divided by orders
