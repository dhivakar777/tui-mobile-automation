package com.tui.automation.pages;

import io.appium.java_client.AppiumBy;
import io.appium.java_client.HasOnScreenKeyboard;
import io.appium.java_client.HidesKeyboard;
import org.openqa.selenium.By;
import org.openqa.selenium.support.ui.ExpectedConditions;

public class LoginPage extends BasePage {

    public enum Field {
        USERNAME("username_input_field"),
        PASSWORD("password_input_field"),
        DATE_OF_BIRTH("date_of_birth_field");

        private final String tag;

        Field(String tag) {
            this.tag = tag;
        }
    }

    private static final By SCREEN_ROOT = byTag("login_form_screen_root");
    private static final By PASSWORD_TOGGLE = byTag("password_input_field_visibility_button");
    private static final By CALENDAR_ICON = byTag("date_of_birth_field_calendar_icon");
    private static final By DATE_DIALOG = byTag("date_of_birth_dialog");
    private static final By DATE_DIALOG_TEXT_MODE = AppiumBy.accessibilityId("Switch to text input mode");
    private static final By DATE_DIALOG_CONFIRM = byTag("date_of_birth_dialog_confirm_button");
    private static final By SUBMIT_BUTTON = byTag("login_form_submit_button");

    public boolean isDisplayed() {
        return isVisible(SCREEN_ROOT);
    }

    public void enterUsername(String username) {
        waitForVisible(byTag(Field.USERNAME.tag)).sendKeys(username);
    }

    public void enterPassword(String password) {
        waitForVisible(byTag(Field.PASSWORD.tag)).sendKeys(password);
    }

    /** Selects the date through the calendar dialog's text input mode, expecting dd/MM/yyyy. */
    public void selectDateOfBirth(String ddMMyyyy) {
        waitForVisible(CALENDAR_ICON).click();
        waitForVisible(DATE_DIALOG_TEXT_MODE).click();
        // The dialog's date field applies the dd/MM/yyyy mask itself, so only digits are typed.
        waitForVisible(DATE_DIALOG).findElement(AppiumBy.className("android.widget.EditText"))
                .sendKeys(ddMMyyyy.replace("/", ""));
        waitForVisible(DATE_DIALOG_CONFIRM).click();
        wait.until(ExpectedConditions.invisibilityOfElementLocated(DATE_DIALOG));
    }

    public void logIn(String username, String password, String ddMMyyyy) {
        enterUsername(username);
        enterPassword(password);
        selectDateOfBirth(ddMMyyyy);
        submit();
    }

    public void submit() {
        hideKeyboard();
        waitForVisible(SUBMIT_BUTTON).click();
    }

    public String fieldText(Field field) {
        return waitForVisible(byTag(field.tag)).getText();
    }

    public String errorText(Field field) {
        return waitForVisible(byTag(field.tag + "_error")).getText();
    }

    public boolean isPasswordMasked() {
        return Boolean.parseBoolean(waitForVisible(byTag(Field.PASSWORD.tag)).getAttribute("password"));
    }

    public void togglePasswordVisibility() {
        waitForVisible(PASSWORD_TOGGLE).click();
    }

    /** Accessibility label of the toggle icon: "Show password" or "Hide password". */
    public String passwordToggleLabel() {
        return waitForVisible(PASSWORD_TOGGLE).findElement(AppiumBy.className("android.view.View")).getAttribute("content-desc");
    }

    private void hideKeyboard() {
        if (driver instanceof HasOnScreenKeyboard keyboard && keyboard.isKeyboardShown()) {
            ((HidesKeyboard) driver).hideKeyboard();
        }
    }
}
