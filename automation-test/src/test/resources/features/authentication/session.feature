@authentication @api @security
Feature: Session lifetime and token rotation

  As a shopper
  I want my session to stay alive while I use the store
  So that I am never signed out in the middle of doing something

  Background:
    Given a buyer account exists

  @smoke @regression
  Scenario: A freshly issued session is accepted
    Given the buyer has a live session
    When the shopper's bag is requested
    Then the response status should be 200

  @regression
  Scenario: Refreshing issues a different token that works at once
    Given the buyer has a live session
    When the session is refreshed
    Then the response status should be 200
    And a new token different from the old one is returned
    When the shopper's bag is requested with the new token
    Then the response status should be 200

  @regression @database
  Scenario: The old token keeps working briefly after rotation, then not
    Given the buyer has a live session
    When the session is refreshed
    Then the old token is still accepted for the bag
    And the old session row is capped to at most 60 seconds of life
    And the new session row keeps the original login expiry

  @regression @negative
  Scenario: A token whose grace period has passed is dead
    Given the buyer has a session whose access lapsed 1 seconds ago and whose login ended 1 seconds ago
    When the shopper's bag is requested
    Then the response status should be 401
    When the session is refreshed
    Then the response status should be 401

  @regression
  Scenario: A shopper who was idle is carried on, not signed out
    Given the buyer has a session whose access lapsed 3600 seconds ago and whose login ends in 604800 seconds
    When the shopper's bag is requested
    Then the response status should be 401
    When the session is refreshed
    Then the response status should be 200
    When the shopper's bag is requested with the new token
    Then the response status should be 200

  @regression @negative
  Scenario: A login that has ended cannot be refreshed
    Given the buyer has a session whose access lapsed 1 seconds ago and whose login ended 1 seconds ago
    When the session is refreshed
    Then the response status should be 401
