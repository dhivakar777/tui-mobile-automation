@listings
Feature: Travel listings
  As a logged-in traveller I want to browse hotels and holidays by category.

  Background:
    Given the app is open on the login screen
    And I am logged in as the valid user

  Scenario: Category tabs show the matching listings
    Then only the "All" tab is selected
    And the list shows exactly the expected "All" results
    When I select the "Hotels" tab
    Then only the "Hotels" tab is selected
    And the list shows exactly the expected "Hotels" results
    When I select the "Holidays" tab
    Then only the "Holidays" tab is selected
    And the list shows exactly the expected "Holidays" results

  Scenario: Hotel cards show the expected details
    When I select the "Hotels" tab
    Then the first hotel cards show the expected details
