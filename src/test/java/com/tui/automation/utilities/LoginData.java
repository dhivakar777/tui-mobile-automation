package com.tui.automation.utilities;

/** Shape of testdata/login.json. */
public record LoginData(User validUser, String requiredFieldError) {

    public record User(String username, String password, String dateOfBirth, String dateOfBirthDisplayed) {
    }
}
