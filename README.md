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
7. **The application APK.** The repository contains it as `TUIChallengeApp.zip`; unzip it, or use your own copy.

## Project structure

```
pom.xml                      Build, pinned dependency versions, enforcer rules
README.md                    This file
DECISIONS.md                 Design decisions and requirement status
CROSS_PLATFORM_DESIGN.md     Parallel and cloud execution (implemented) and iOS, CI-matrix design
evidence/                    Reports, logs and screenshots from documented runs
.github/workflows/build.yml  CI: compile and Gherkin validation (no UI tests)
src/test/java/com/tui/automation/
  config/ConfigManager       Reads config.properties; any key can be overridden with -Dkey=value
  driver/DriverManager       Creates, exposes and ends the Appium session (one driver per thread)
  driver/DevicePool          Gives each parallel scenario its own device and UiAutomator2 port
  driver/CloudOptions        BrowserStack settings, credentials from environment variables
  pages/BasePage             Shared driver, explicit wait, Compose test-tag locator
  pages/LoginPage            Login form, password toggle, date-of-birth dialog
  pages/ListingsPage         Category tabs and listing cards, including list scrolling
  pages/ListingCard          Record holding one card's visible text
  hooks/Hooks                Starts and ends the driver per scenario; failure screenshots
  stepdefinitions/           Gherkin steps translated into page-object calls and assertions
  runners/TestRunner         TestNG-based Cucumber runner
  retry/                     Retry analyzer for environmental failures, and its TestNG wiring
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
`-Dkey=value`; command-line values win over the file. Never pass credentials this way: Surefire writes every
`-D` property into `target/surefire-reports/TEST-TestSuite.xml`. The framework masks any `user:password@` part
of `appium.server.url` in its own logs and errors, but cannot mask Surefire's record.

| Key | Default | Purpose |
|---|---|---|
| `appium.server.url` | `http://127.0.0.1:4723` | Appium server endpoint |
| `device.udid` | *(blank)* | Target device. Blank uses the only connected device; set it when several are attached |
| `app.path` | *(blank)* | Blank launches the installed app; set it to an APK path to install that file first |
| `app.package` | `com.tui.qa.challenge` | Application package |
| `app.activity` | `com.tui.qa.challenge.MainActivity` | Launch activity |
| `device.language`, `device.locale` | `en`, `GB` | Device locale for the session. The app formats dates and prices from it, and the test data expects en-GB. Appium leaves the device in this locale afterwards; blank both to keep the device's own |
| `retry.max.retries` | `1` | Extra attempts for a scenario that fails with an environmental error; `0` disables retry |
| `timeout.explicit.wait.seconds` | `15` | Explicit wait for elements; raise it on slow emulators |
| `execution.target` | `local` | `local` or `cloud`; anything else is rejected |
| `cloud.provider`, `cloud.hub.url`, `cloud.app`, `cloud.device.name`, `cloud.platform.version`, `cloud.project.name`, `cloud.build.name` | BrowserStack defaults; app and device blank | Cloud mode only. Credentials are **not** configured here; see *Cloud execution* |
| `device.udids` | *(blank)* | Devices to share between concurrent scenarios, comma-separated; use instead of `device.udid` for parallel runs |
| `parallel.threads` (Maven property) | `1` | Scenarios run at once. `1` keeps the suite sequential; must not exceed the devices in `device.udids` |

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

`*.apk` and `apps/` are git-ignored, so an extracted APK is never committed by accident. The repository does contain `TUIChallengeApp.zip`, which was uploaded separately.

## Running the tests

The five UI scenarios run **locally only**: they need a connected Android device or emulator and a running
Appium server. CI does not run them; see [Continuous integration](#continuous-integration).

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

### Parallel execution

Run scenarios concurrently across several devices, one device per thread:

```bash
mvn clean test -Ddevice.udids=emulator-5554,<phone-udid> -Dparallel.threads=2
```

Each scenario claims a free device and its own UiAutomator2 port, and returns it when its session ends. Log
lines and failure screenshot names include the device id. The isolation mechanism has been validated locally on
an emulator and a phone; see *Current limitations* for the emulator's resource limits.

### Cloud execution (BrowserStack)

Implemented but **not yet verified against BrowserStack**. Upload the APK to BrowserStack App Automate to get a
`bs://…` app id, then:

```bash
export BROWSERSTACK_USERNAME=<your-username>     # credentials only as environment variables,
export BROWSERSTACK_ACCESS_KEY=<your-access-key> # never in config files or -D properties
mvn clean test -Dexecution.target=cloud -Dcloud.app=bs://<app-id> \
  -Dcloud.device.name="Google Pixel 7" -Dcloud.platform.version=13.0
```

Cloud mode never falls back to local execution: a missing setting or variable stops the run before any session is
requested. Session-creation errors are reported with credentials masked.

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
| Failure screenshots | `target/screenshots/<timestamp>_<scenario-name>.png`, also embedded in the HTML report. The path is logged |
| Execution log | `target/logs/test-execution.log` (DEBUG, with the scenario name on every line) |
| Console log | INFO level, in the terminal |
| TestNG/Surefire output | `target/surefire-reports/` |
| Appium server log | Wherever the server was started, e.g. `appium --log /tmp/appium.log` |
| Committed evidence | `evidence/passing-run/` (report, log and screenshots); see `evidence/README.md` |

Open the report with `open target/cucumber-reports/cucumber.html`. `mvn clean` deletes `target/`, so copy
anything you need to keep.

## Retry

A scenario that fails with an environmental error — session not created or lost, device input refused, or a
stale element — is retried up to `retry.max.retries` times (default 1). Assertion failures, timeouts and missing
elements are **never** retried, because they can be genuine defects.

How a retried scenario is reported (observed with controlled temporary scenarios):

| Outcome | Maven / Surefire | Cucumber HTML report |
|---|---|---|
| Fails once, passes on retry | `BUILD SUCCESS`; the failed attempt counts as **Skipped** (e.g. `Tests run: 2, Failures: 0, Skipped: 1`) | **Two entries:** one FAILED, one PASSED. The report does not mark the second as a retry, so its summary still counts a failure |
| Fails on every attempt | `BUILD FAILURE`; earlier attempts Skipped, last attempt a Failure | One FAILED entry per attempt |
| Assertion failure | `BUILD FAILURE` on the first attempt; never retried | One FAILED entry |

So the build result reflects the final attempt, and no failed attempt is hidden: it stays in the HTML report, as
a skipped test in `target/surefire-reports/`, and as a `WARN` line from `RetryAnalyzer` in
`target/logs/test-execution.log` naming the scenario and the exception. When the HTML report shows a failure but
the build passed, check that log for a retry.

```bash
mvn clean test -Dretry.max.retries=0      # disable retry
```

## Continuous integration

`.github/workflows/build.yml` runs on push and pull request to `main`, and manually from the Actions tab:

1. JDK 17 with Maven caching.
2. `mvn -B -ntp test-compile` — compiles everything and runs the enforcer rules.
3. `mvn -B -ntp test -Dcucumber.execution.dry-run=true` — fails if any feature step has no step definition.
4. Uploads `target/cucumber-reports/`, `target/surefire-reports/` and `target/logs/` as the
   `build-validation-reports` artifact, even when a step fails.

**It does not run the UI tests.** It starts no emulator or Appium server and installs no app. The workflow passes
`actionlint`, and it **passed on GitHub Actions** on 2026-10-26 (run [40320669288](https://github.com/dhivakar777/tui-mobile-automation/actions/runs/40320669288), commit `17dd197`): every
step succeeded and the reports artifact was uploaded. The next step is an emulator job, described in `DECISIONS.md`.

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

**Dates or prices differ from the expected values (`2009-07-08`, `$1,280`)**
The device locale is not en-GB. Check that `device.language` and `device.locale` are not blanked.

**`Cannot set the device locale to 'en_GB'`** (session creation fails)
Seen on the first session after a locale change on a freshly rebooted emulator. Run the suite again; the locale
is normally applied by then, and later sessions skip the change.

**Build fails on the enforcer rules**
`requireJavaVersion [17,)` or `requireMavenVersion [3.9,)` failed. Check `mvn -v`, which reports the JDK Maven
itself uses; that can differ from your shell's `java -version`.

## Future work (design only)

Not implemented, configured or tested. The design, risks and validation plan are in `CROSS_PLATFORM_DESIGN.md`.

- **iOS.** A `-Dplatform=android|ios` switch and a per-platform driver factory would add an `IOSDriver` /
  XCUITest branch beside today's Android one. It would need macOS, Xcode, a simulator or registered device, an
  iOS build of the app and WebDriverAgent signing for real devices. Locators would be resolved per platform,
  because Compose test tags and iOS accessibility identifiers are located differently.
- **UI tests in CI** and cloud providers other than BrowserStack.

## Current limitations

- **Android only.** iOS and cross-platform execution are design-only; see `CROSS_PLATFORM_DESIGN.md`.
- **BrowserStack execution is implemented but not verified against BrowserStack.** No BrowserStack session has been
  run; the remote path was exercised against a local Appium server only.
- **CI validates the build only.** The workflow passes on GitHub Actions, but no UI tests run in CI.
- **Parallel execution is off by default.** The device and port isolation was validated on an emulator and a
  phone. Under parallel load the emulator (2.5 GB RAM) intermittently failed to start sessions — an emulator
  limitation; see `CROSS_PLATFORM_DESIGN.md` §2.3.
- **Validated on two devices:** an Android emulator (API 35) and a OnePlus Nord CE (EB2101, Android 13), both
  in en-GB.
- **Scenarios depend on the app's bundled data.** `testdata/listings.json` was extracted from the APK's
  `assets/*.json`. A new APK build with different data needs that file regenerated.
- The login screen does not verify credentials, so no scenario asserts a failed login. See `DECISIONS.md`.
