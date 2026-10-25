# Cross-platform, CI and parallel execution design

> **Status: design only.** Nothing in this document is implemented. iOS execution, device-cloud execution,
> CI and parallel execution have never been run against this framework. Statements about what *would* happen
> are proposals, not results.

## 1. Current state

### Implemented and validated (Android)
- A single Android path: Appium 3.2.2 + UiAutomator2 7.0.0 + java-client 10.1.1 driving
  `com.tui.qa.challenge`, with the session pinned to the en-GB locale.
- Five Cucumber scenarios passing 5/5 from the terminal with `mvn clean test` on an Android emulator (API 35)
  and a physical OnePlus Nord CE (Android 13), on 2026-10-25. Tag filtering works.
- Cucumber HTML report, failure screenshots on disk and embedded in the report, and a DEBUG log carrying the
  scenario name.
- Retry for environmental failures, verified with a controlled temporary scenario.
- A GitHub Actions workflow for compilation and Gherkin validation, statically checked; not yet run on GitHub.
- See `evidence/` and the status table in `DECISIONS.md` for the run that backs these claims.

### Not implemented and not validated
| Item | Status |
|---|---|
| iOS simulator or physical iOS device | Not implemented. No iOS app artifact, no macOS/Xcode setup performed |
| Device clouds (BrowserStack, Sauce Labs, LambdaTest) | Not implemented. No account used |
| UI tests in CI | **Not implemented.** The existing workflow only compiles and dry-runs the features |
| Parallel execution | Not implemented. One scenario at a time, one device |

## 2. Cross-platform architecture

### What is already platform-neutral
These need no change to serve a second platform:

- `features/*.feature` — written in business language, with no Android terms.
- `stepdefinitions/` — they call page methods and assert; they contain no locators.
- `testdata/*.json` and `utilities/` — data and its record types.
- `config/ConfigManager` — a properties file plus `-D` overrides.
- `hooks/Hooks` — start driver, capture failure evidence, quit.
- `runners/TestRunner` — feature discovery, glue and reporting.
- `DriverManager`'s `ThreadLocal` lifecycle and the W3C touch drag used for scrolling.

### What is Android-specific today
Found by inspecting the code; these are the only places a second platform would touch:

| Location | Android-specific detail | iOS equivalent |
|---|---|---|
| `DriverManager.startDriver()`, `androidOptions()` | `UiAutomator2Options`, `AndroidDriver` | `XCUITestOptions`, `IOSDriver` |
| `BasePage.byTag()` | `new UiSelector().resourceId(...)` for Compose test tags | `AppiumBy.accessibilityId(...)` for `accessibilityIdentifier` |
| `ListingsPage.CARD_NAMES` | `UiSelector().resourceIdMatches("content_card_hotel_name_[0-9]+")` | iOS predicate, e.g. `name BEGINSWITH 'content_card_hotel_name_'` |
| `ListingsPage.isSelected()`, `cardIndex()` | `selected` and `resource-id` attributes | `selected` / `value` and `name` attributes |
| `LoginPage.isPasswordMasked()` | the `password` attribute | a secure field is a distinct element type (`XCUIElementTypeSecureTextField`) |
| `LoginPage`, `ListingsPage` child lookups | `android.widget.EditText`, `android.view.View`, `android.widget.TextView` | different class names; prefer identifiers over class names on both platforms |
| `LoginPage.selectDateOfBirth()` | Material 3 date dialog, text-input mode, `dd/MM/yyyy` | `UIDatePicker`, a different interaction model |

### Selected approach

Keep driver configuration and locators platform-specific, and share everything above them.

- **Platform selection by configuration.** A `-Dplatform=android|ios` switch selecting
  `config/android.properties` or `config/ios.properties`.
- **Driver options split per platform**, one builder each, reading its own configuration. Only the options
  builder and driver type differ.
- **Locators resolved per platform**, ideally with the same identifier strings in both apps. If the iOS app
  exposes identifiers comparable to the Android test tags, only `BasePage.byTag()` and the handful of attribute
  reads above need to branch, and each page class stays single.
- **Platform-specific page behaviour only where screens genuinely differ** — the date picker being the clear
  case — via a platform check in that one method or a platform subclass of that page.

Feature files, step definitions, test data, hooks, the runner and reporting stay shared.

## 3. iOS prerequisites

Design prerequisites only; none of this has been set up or verified.

- **macOS with Xcode.** iOS automation cannot run on Linux, so it needs a macOS machine or a `macos-*` CI runner.
- **An iOS build of the application.** A simulator build and a physical-device build are different artifacts;
  a physical device additionally requires Apple Developer signing.
- **Appium's XCUITest driver**, with iOS capabilities supplied through the platform configuration file.

## 4. CI strategy

### One job per platform
Run Android and iOS as **separate jobs**, each with its own runner, device and evidence, rather than mixing
platforms in one JVM. Platform jobs are independent, so they run concurrently whenever runner capacity allows.

| Job | Runner | Device | Notes |
|---|---|---|---|
| Android | `ubuntu-latest` | Emulator via `reactivecircus/android-emulator-runner` | Needs a KVM udev rule for hardware acceleration. Linux runners are cheaper and faster than macOS |
| iOS | `macos-latest` | iOS simulator via `xcrun simctl` | macOS runners are required and are billed at a higher rate |

Each job would set up JDK 17 with Maven caching, install Appium and its platform driver, start the device and
the Appium server, make the app artifact available, then run `mvn clean test` with the platform and APK path
passed as `-D` overrides. A Maven failure fails the job, so a genuine test failure fails the pipeline. Neither
job should be allowed to mask the other's result.

### Evidence collection per platform
Each job uploads its own artifact (`test-evidence-android`, `test-evidence-ios`) with `if: always()`, so
evidence survives a failing run:

- `target/cucumber-reports/cucumber.html` — the report, with failure screenshots embedded
- `target/logs/test-execution.log` — the framework log
- `target/surefire-reports/` — TestNG output
- the Appium server log, written outside `target/` so `mvn clean` cannot delete it

### Open dependencies and risks

1. **APK source.** The repository now contains `TUIChallengeApp.zip`, so an Android job could unzip it; whether
   redistributing the app this way is permitted should be confirmed before relying on it.
2. **Device locale.** The app formats dates and prices from the device locale. This was confirmed on an en-US
   emulator and is handled by pinning sessions to en-GB; an iOS job would need the equivalent setting.
3. **Emulator differences.** A cold emulator may need a longer explicit wait, and a shorter screen requires more
   scrolls than the devices used for validation.

## 5. Parallel execution safety

An Appium session owns one device and one app, so two scenarios sharing a session would interleave taps and
produce meaningless results. Isolation must cover the session, the device, the Appium server ports and the
evidence.

`DriverManager` stores the driver in a `ThreadLocal`, creates one session per start, rejects a second start on
the same thread, and clears the slot on quit. That is the right shape for one session per thread, but
**`ThreadLocal` alone does not prove that parallel execution works, and parallel execution has not been
validated** — the suite has only ever run single-threaded.

Enabling it would require:

- **A device pool**, since `device.udid` is a single value and every thread would otherwise target one device.
- **A unique Appium server port per concurrent session**, or sessions collide.
- **A parallel runner**, since `TestRunner` is sequential, with the thread count bounded by available devices.
- **Confirmed evidence isolation.** Log lines already carry the scenario name, and the report is written once at
  the end; both need checking under concurrency.

Validation would mean repeated multi-device runs with correct counts, no leaked sessions, and a forced failure
in one thread leaving the other thread's results and screenshots intact.

## 6. Risks and limitations

- **Nothing in this document has been executed.** iOS, cloud, CI and parallel execution are all unimplemented.
- The iOS locator mapping assumes the iOS app exposes identifiers comparable to the Android test tags. If it
  does not, the page layer needs more platform-specific code than estimated here.
- Locale was the platform difference that surfaced on the emulator; other differences may appear on other
  devices or Android versions.
- Capability logging is now an explicit allowlist, so cloud credentials would not be logged. Cloud credentials
  themselves would still need to come from CI secrets.
- `testdata/listings.json` was extracted from the current APK's bundled assets. A different app build, or a
  version that fetches live data, would invalidate the expected values on every platform.
- The framework has been validated on one emulator and one physical device, both in en-GB. Other Android
  versions and screen sizes are unverified.
