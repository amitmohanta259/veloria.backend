@authentication @api
Feature: Signing in and access to protected areas

  As a shopper
  I want browsing to be open and my account to be protected
  So that I can look around freely but only I can act on my account

  @smoke @regression @security @negative
  Scenario: A wrong password is refused with a clear message
    When a shopper signs in with an unknown email and a wrong password
    Then the response status should be 401
    And the response message should be "Invalid credentials."

  @smoke @regression @security
  Scenario: The bag cannot be read without signing in
    When the shopper's bag is requested without a session
    Then the response status should be 401

  @regression @security @negative
  Scenario: A made-up token is treated as not signed in, not as a bad request
    Given the shopper holds a token the server has never issued
    When the shopper's bag is requested
    Then the response status should be 401

  @smoke @regression
  Scenario: Browsing the catalogue needs no sign-in
    When the new-in products are requested without a session
    Then the response status should be 200
    When the categories are requested without a session
    Then the response status should be 200

  @regression
  Scenario: A stale token does not get in the way of browsing
    Given the shopper holds a token the server has never issued
    When the new-in products are requested
    Then the response status should be 200

  @regression @security @gst
  Scenario: GST administration requires the GST permission
    When the GST rules are requested without a token
    Then the response status should be 401
    Given an admin is signed in to the GST module as "GST_ADMIN"
    When the GST rules are requested
    Then the response status should be 200
