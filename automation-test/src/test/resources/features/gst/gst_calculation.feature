@gst @api
Feature: GST calculation

  As the business
  I want tax computed from the configured HSN rules and the place of supply
  So that every invoice carries the right CGST, SGST or IGST

  Background:
    Given an admin is signed in to the GST module as "GST_ADMIN"

  @smoke @regression
  Scenario: A supply within the seller's state is split into CGST and SGST
    When GST is calculated for HSN "4202" at 150000 paise from state "29" to state "29"
    Then the response status should be 200
    And the supply is intra-state
    And CGST and SGST are equal and IGST is zero
    And total tax equals CGST plus SGST plus IGST

  @smoke @regression
  Scenario: A supply to another state is charged as IGST only
    When GST is calculated for HSN "4202" at 150000 paise from state "27" to state "29"
    Then the response status should be 200
    And the supply is inter-state
    And IGST is charged and CGST and SGST are zero
    And total tax equals CGST plus SGST plus IGST

  @regression @boundary
  Scenario Outline: The rate follows the HSN chapter and the price band
    When GST is calculated for HSN "<hsn>" at <pricePaise> paise from state "27" to state "29"
    Then the response status should be 200
    And the IGST rate is <igstBp> basis points
    And the IGST amount is <igstPaise> paise

    Examples: Bags and leather goods are 18% at any price
      | hsn  | pricePaise | igstBp | igstPaise |
      | 4202 | 50000      | 1800   | 9000      |
      | 4203 | 250000     | 1800   | 45000     |

    Examples: Apparel is 5% below one thousand rupees and 12% from one thousand
      | hsn  | pricePaise | igstBp | igstPaise |
      | 6101 | 99999      | 500    | 5000      |
      | 6101 | 100000     | 1200   | 12000     |
      | 6211 | 99999      | 500    | 5000      |
      | 6211 | 100000     | 1200   | 12000     |
      | 6301 | 99999      | 500    | 5000      |
      | 6301 | 100000     | 1200   | 12000     |

  @regression
  Scenario Outline: The same rule splits evenly within the state
    When GST is calculated for HSN "<hsn>" at <pricePaise> paise from state "29" to state "29"
    Then the response status should be 200
    And the CGST rate is <halfBp> basis points
    And the SGST rate is <halfBp> basis points
    And CGST and SGST are equal and IGST is zero

    Examples:
      | hsn  | pricePaise | halfBp |
      | 4202 | 150000     | 900    |
      | 6211 | 99999      | 250    |
      | 6211 | 100000     | 600    |
