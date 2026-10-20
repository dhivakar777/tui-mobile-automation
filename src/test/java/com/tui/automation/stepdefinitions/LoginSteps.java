package com.tui.automation.stepdefinitions;

import com.tui.automation.pages.ListingsPage;
import com.tui.automation.pages.LoginPage;
import com.tui.automation.pages.LoginPage.Field;
import com.tui.automation.utilities.LoginData;
import com.tui.automation.utilities.TestDataReader;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

public class LoginSteps {

    private final LoginData data = TestDataReader.read("login.json", LoginData.class);
    private final LoginData.User user = data.validUser();

    @Given("the app is open on the login screen")
    public void theAppIsOpenOnTheLoginScreen() {
        assertTrue(new LoginPage().isDisplayed(), "Login screen should be displayed");
    }

    @Given("I am logged in as the valid user")
    public void iAmLoggedInAsTheValidUser() {
        new LoginPage().logIn(user.username(), user.password(), user.dateOfBirth());
        assertTrue(new ListingsPage().isDisplayed(), "Listings screen should be displayed after login");
    }

    @When("I submit the login form without entering any details")
    public void iSubmitTheLoginFormWithoutEnteringAnyDetails() {
        new LoginPage().submit();
    }

    @Then("every login field shows the required-field error")
    public void everyLoginFieldShowsTheRequiredFieldError() {
        LoginPage loginPage = new LoginPage();
        for (Field field : Field.values()) {
            assertEquals(loginPage.errorText(field), data.requiredFieldError(), "Error under " + field);
        }
    }

    @Then("I remain on the login screen")
    public void iRemainOnTheLoginScreen() {
        assertTrue(new LoginPage().isDisplayed(), "Login screen should still be displayed");
    }

    @When("I enter the valid username and password")
    public void iEnterTheValidUsernameAndPassword() {
        LoginPage loginPage = new LoginPage();
        loginPage.enterUsername(user.username());
        loginPage.enterPassword(user.password());
    }

    @When("I select the valid date of birth in the calendar")
    public void iSelectTheValidDateOfBirthInTheCalendar() {
        new LoginPage().selectDateOfBirth(user.dateOfBirth());
    }

    @Then("the date of birth field shows the selected date in ISO format")
    public void theDateOfBirthFieldShowsTheSelectedDateInIsoFormat() {
        assertEquals(new LoginPage().fieldText(Field.DATE_OF_BIRTH), user.dateOfBirthDisplayed(), "Date of birth field");
    }

    @When("I submit the login form")
    public void iSubmitTheLoginForm() {
        new LoginPage().submit();
    }

    @When("I enter the valid password")
    public void iEnterTheValidPassword() {
        new LoginPage().enterPassword(user.password());
    }

    @Then("the password is masked")
    public void thePasswordIsMasked() {
        LoginPage loginPage = new LoginPage();
        assertTrue(loginPage.isPasswordMasked(), "Password field should be masked");
        assertNotEquals(loginPage.fieldText(Field.PASSWORD), user.password(), "Masked password must not show plain text");
        assertEquals(loginPage.passwordToggleLabel(), "Show password", "Toggle label");
    }

    @When("I toggle the password visibility")
    public void iToggleThePasswordVisibility() {
        new LoginPage().togglePasswordVisibility();
    }

    @Then("the password is shown in plain text")
    public void thePasswordIsShownInPlainText() {
        LoginPage loginPage = new LoginPage();
        assertFalse(loginPage.isPasswordMasked(), "Password field should not be masked");
        assertEquals(loginPage.fieldText(Field.PASSWORD), user.password(), "Visible password text");
        assertEquals(loginPage.passwordToggleLabel(), "Hide password", "Toggle label");
    }
}
