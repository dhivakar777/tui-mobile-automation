@login
Feature: Login
  As a traveller I want to log in with my details so that I can browse travel offers.

  Background:
    Given the app is open on the login screen

  @negative
  Scenario: Mandatory fields are validated
    When I submit the login form without entering any details
    Then every login field shows the required-field error
    And I remain on the login screen

  @smoke
  Scenario: Successful login opens the listings
    When I enter the valid username and password
    And I select the valid date of birth in the calendar
    Then the date of birth field shows the selected date in ISO format
    When I submit the login form
    Then only the "All" tab is selected

  Scenario: Password visibility can be toggled
    When I enter the valid password
    Then the password is masked
    When I toggle the password visibility
    Then the password is shown in plain text
    When I toggle the password visibility
    Then the password is masked
