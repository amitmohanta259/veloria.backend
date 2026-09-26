@admin @api
Feature: Roles and module permissions

  As an administrator
  I want to grant a role access to any module in the portal
  So that staff can reach the screens their job needs

  @smoke @regression
  Scenario: Every role reports every module, including the GST ones
    When the roles are listed
    Then the response status should be 200
    And every role reports 19 modules
    And the modules include "GST_MANAGEMENT", "GST_ACCOUNTING", "GST_TRACKER" and "GST_COMPLIANCE"

  @regression @database
  Scenario: A grant on a newly added module is saved and read back
    When a role is created for department "LOGISTICS" with designation "Automation Analyst" granting view on "GST_COMPLIANCE"
    Then the response status should be 201
    And the role reports "GST_COMPLIANCE" with view granted
    And the database holds a view grant on "GST_COMPLIANCE" for that role
    When that role is read back
    Then the role reports "GST_COMPLIANCE" with view granted

  @regression @database
  Scenario: Updating a role replaces its grants without leaving duplicates
    Given a role exists for department "OPERATIONS" with designation "Automation Analyst" granting view on "GST_TRACKER"
    When the role is updated to grant view on "INDICATORS" only
    Then the response status should be 200
    And the role reports "INDICATORS" with view granted
    And the role reports "GST_TRACKER" without view
    And the database holds exactly 1 permission row for that role

  @regression @database
  Scenario: Deleting a role removes its grants
    Given a role exists for department "ENGINEERING" with designation "Automation Analyst" granting view on "DASHBOARD"
    When the role is deleted
    Then the response status should be 200
    And the database holds exactly 0 permission rows for that role

  @regression
  Scenario Outline: Finance is a department like any other
    When staff are listed for department "<department>"
    Then the response status should be 200

    Examples:
      | department |
      | OPERATIONS |
      | FINANCE    |
