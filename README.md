# TUI Mobile Automation Framework

Android UI test framework for `TUIChallengeApp.apk` (`com.tui.qa.challenge`), built with Appium, Java,
Cucumber BDD and Maven. Five scenarios cover the login screen and the travel listings screen.

## Technology stack

| Component | Version | Notes |
|---|---|---|
| Java | 17 (`maven.compiler.release`) | Builds on any JDK 17+; the build fails fast below 17 |
| Maven | 3.9+ | Enforced by `maven-enforcer-plugin` |
| Appium server | 3.2.2 | Installed separately with npm, not a Maven dependency |
| Appium UiAutomator2 driver | 7.0.0 | Android automation driver |
| Appium java-client | 10.1.1 | Pinned |
| Selenium | 4.44.0 | Pinned via `selenium-bom` because java-client declares a version range |
| Cucumber JVM | 7.34.9 | `cucumber-java`, `cucumber-testng` |
| TestNG | 7.12.0 | Test engine behind the Cucumber runner |
| Jackson | 2.22.4 | Reads JSON test data into Java records |
| Log4j2 + SLF4J bridge | 2.26.1 | One logging configuration for framework and client logs |

## Prerequisites

1. **JDK 17 or newer** — `java -version`
2. **Maven 3.9+** — `mvn -v` (`brew install maven`)
3. **Android SDK** with platform-tools on the `PATH`, and `ANDROID_HOME` set — `adb version`
4. **Node.js**, for the Appium server
5. **Appium and the UiAutomator2 driver**
   ```bash
   npm install -g appium@3.2.2
   appium driver install uiautomator2@7.0.0
   appium driver doctor uiautomator2      # expect "0 required fixes"
   ```
6. **An Android device or emulator**, API 24+ (the app's `minSdkVersion`)
   ```bash
   adb devices -l                         # the state must be "device"
   ```
7. **The application APK**, available locally. It is not stored in this repository.

## Project structure

```
pom.xml                      Build, pinned dependency versions, enforcer rules
README.md                    This file
DECISIONS.md                 Design decisions and requirement status
CROSS_PLATFORM_DESIGN.md     iOS, cloud and parallel execution design (not implemented)
evidence/                    Report and log from the documented validation run
src/test/java/com/tui/automation/
  config/ConfigManager       Reads config.properties; any key can be overridden with -Dkey=value
  driver/DriverManager       Creates, exposes and ends the Appium session (one driver per thread)
  pages/BasePage             Shared driver, explicit wait, Compose test-tag locator
  pages/LoginPage            Login form, password toggle, date-of-birth dialog
  pages/ListingsPage         Category tabs and listing cards, including list scrolling
  pages/ListingCard          Record holding one card's visible text
  hooks/Hooks                Starts and ends the driver per scenario; failure screenshots
  stepdefinitions/           Gherkin steps translated into page-object calls and assertions
  runners/TestRunner         TestNG-based Cucumber runner
  utilities/TestDataReader   Loads JSON test data into records
src/test/resources/
  features/login.feature     3 scenarios
  features/listings.feature  2 scenarios
  testdata/login.json        Credentials and date of birth
  testdata/listings.json     Expected listing names and card details
  config/config.properties   Default settings
```

The dependency direction is one-way: **runner → feature → step → page → DriverManager → ConfigManager**.
Page classes hold locators and interactions but no assertions; step definitions hold assertions but no locators.

## Configuration

Defaults live in `src/test/resources/config/config.properties`. Override any key for a single run with
`-Dkey=value`; command-line values win over the file.

| Key | Default | Purpose |
|---|---|---|
| `appium.server.url` | `http://127.0.0.1:4723` | Appium server endpoint |
| `device.udid` | *(blank)* | Target device. Blank uses the only connected device; set it when several are attached |
| `app.path` | *(blank)* | Blank launches the installed app; set it to an APK path to install that file first |
| `app.package` | `com.tui.qa.challenge` | Application package |
| `app.activity` | `com.tui.qa.challenge.MainActivity` | Launch activity |
| `timeout.explicit.wait.seconds` | `15` | Explicit wait for elements; raise it on slow emulators |

### Supplying the APK

Either install the app once by hand:

```bash
adb install -r /path/to/TUIChallengeApp.apk
mvn clean test
```

or let Appium install it at the start of every session:

```bash
mvn clean test -Dapp.path=/path/to/TUIChallengeApp.apk
```

`*.apk` and `apps/` are git-ignored, so the binary is never committed.

## Running the tests

**1. Start the Appium server** in its own terminal:

```bash
appium --address 127.0.0.1 --port 4723 --log-timestamp --local-timezone
```

Check it is up: `curl http://127.0.0.1:4723/status` should report `"ready":true`.

**2. Run the suite** from the project root:

```bash
mvn clean test                                        # all five scenarios
mvn clean test -Ddevice.udid=<udid>                   # pick a device when several are connected
mvn clean test -Dapp.path=/path/to/TUIChallengeApp.apk
mvn clean test -Dtimeout.explicit.wait.seconds=30     # slower emulators
```

Each scenario gets a fresh session with the app data cleared, so scenarios can run in any order.

### Tagged execution

Tags: `@login` (3 scenarios), `@listings` (2), `@smoke` (1), `@negative` (1).

```bash
mvn clean test -Dcucumber.filter.tags="@smoke"
mvn clean test -Dcucumber.filter.tags="@login"
mvn clean test -Dcucumber.filter.tags="@login and not @negative"
mvn clean test -Dcucumber.filter.tags="@login or @listings"
```

## Reports, logs and screenshots

| Evidence | Location |
|---|---|
| Cucumber HTML report | `target/cucumber-reports/cucumber.html` |
| Failure screenshots | **Embedded inside that HTML report**, attached to the failing scenario |
| Execution log | `target/logs/test-execution.log` (DEBUG, with the scenario name on every line) |
| Console log | INFO level, in the terminal |
| TestNG/Surefire output | `target/surefire-reports/` |
| Appium server log | Wherever the server was started, e.g. `appium --log /tmp/appium.log` |
| Snapshot of a known-good run | `evidence/` — report and log from a clean-checkout run; see `evidence/README.md` |

Open the report with `open target/cucumber-reports/cucumber.html`. `mvn clean` deletes `target/`, so copy
anything you need to keep.

## Continuous integration

**Not implemented.** This repository contains no workflow. The proposed design, including the Android and iOS
job strategy, is in `CROSS_PLATFORM_DESIGN.md`.

## Troubleshooting

**`Could not start an Appium session … Is the Appium server running?`**
Check `curl http://127.0.0.1:4723/status` and `adb devices`. A device shown as `unauthorized` needs the USB
debugging prompt accepted on the phone.

**`Could not find a connected Android device in 20000ms`**
No device is attached, or several are and none was chosen. Pass `-Ddevice.udid=<udid>` from `adb devices`.

**`NoSuchElementException` for an element you can see in the UI dump**
The app is built with Jetpack Compose, whose test tags are exposed as resource-ids **without** a package
prefix, which `AppiumBy.id` cannot match. The framework locates them with
`new UiSelector().resourceId("...")` in `BasePage.byTag()`. Use that helper for new locators.

**`Missing required configuration '<key>'`**
Set it in `config/config.properties` or pass `-D<key>=...`.

**`Invalid test data in testdata/<file>.json: Missing creator property '<field>'`**
A JSON file is missing a field that the matching record requires. The message names the file and the field.

**Build fails on the enforcer rules**
`requireJavaVersion [17,)` or `requireMavenVersion [3.9,)` failed. Check `mvn -v`, which reports the JDK Maven
itself uses; that can differ from your shell's `java -version`.

## Current limitations

- **Android only.** iOS, device clouds and cross-platform execution are design-only; see `CROSS_PLATFORM_DESIGN.md`.
- **No CI pipeline.** No workflow is included in this repository.
- **Retry and parallel execution are not implemented.** `DriverManager` keeps one driver per thread, which is a
  precondition for parallel execution, but parallel execution has never been enabled or verified. Single
  device, single thread today.
- **Validated on one device only:** a OnePlus Nord CE (EB2101, Android 13). Not yet run on an emulator end to end.
- **Scenarios depend on the app's bundled data.** `testdata/listings.json` was extracted from the APK's
  `assets/*.json`. A new APK build with different data needs that file regenerated.
- The login screen does not verify credentials, so no scenario asserts a failed login. See `DECISIONS.md`.
