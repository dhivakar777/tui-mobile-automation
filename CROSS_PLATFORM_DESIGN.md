# Cross-platform, CI and parallel execution design

> **Status: design only.** Nothing in this document is implemented. iOS execution, device-cloud execution,
> CI and parallel execution have never been run against this framework. Statements about what *would* happen
> are proposals, not results.

## 1. Current state

### Implemented and validated (Android)
- A single Android path: Appium 3.2.2 + UiAutomator2 7.0.0 + java-client 10.1.1 driving
  `com.tui.qa.challenge` on a physical OnePlus Nord CE (EB2101, Android 13).
- Five Cucumber scenarios, run from the terminal with `mvn clean test`, passing 5/5 — including from a clean
  `git clone` of commit `da79754` on 2026-10-21 (176.6 s). Tag filtering works.
- Cucumber HTML report with failure screenshots embedded, and a DEBUG log carrying the scenario name.
- See `evidence/` and the status table in `DECISIONS.md` for the run that backs these claims.

### Not implemented and not validated
| Item | Status |
|---|---|
| iOS simulator or physical iOS device | Not implemented. No iOS app artifact, no macOS/Xcode setup performed |
| Device clouds (BrowserStack, Sauce Labs, LambdaTest) | Not implemented. No account used |
| GitHub Actions CI | **Not implemented.** A workflow was designed and proposed in Phase 10, then put on hold at the user's request. There is no `.github/workflows/` directory, so nothing has been statically checked or run |
| Parallel execution | Not implemented. One scenario at a time, one device |
| Retry on failure | Not implemented, deliberately descoped |
| Android emulator end-to-end run | Not validated. An emulator was booted during development but the suite was never completed on it |

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

### Isolating the differences, smallest first

1. **Platform selection by configuration.** Reintroduce `-Dplatform=android|ios`, loading
   `config/android.properties` or `config/ios.properties`. This mechanism existed and was deliberately removed
   during the architecture review because only one platform existed; bringing it back is a small, known change.
2. **Split the driver options, not the whole framework.** Extract `DriverFactory` again with one method per
   platform (`androidOptions()`, `iosOptions()`), each reading its own config file. `DriverFactory` was merged
   into `DriverManager` for the same reason: with a single option builder, the extra class earned nothing.
   With two builders it does.
3. **Locators: prefer identical identifiers in both apps.** If the iOS app uses the same identifier strings as
   the Android test tags, only `BasePage.byTag()` and the handful of attribute reads above need to branch, and
   each page class stays single. This is a request to make of the app developers, and it is by far the cheapest
   option.
4. **Only where screens genuinely differ** (the date picker is the clear case): either a platform check inside
   that one page method, or an `AndroidLoginPage` / `IosLoginPage` subclass selected by the platform setting.
   Choose per screen, based on how much actually differs.

**Explicitly not proposed:** a general abstraction layer of interfaces over every page, or java-client's
`@AndroidFindBy`/`@iOSXCUITFindBy` PageFactory annotations. The annotations hide when element lookups happen,
which conflicts with the explicit waits this framework relies on and with re-rendered Compose/SwiftUI elements.

## 3. iOS prerequisites

Design prerequisites only; none of this has been set up or verified.

| Requirement | Detail |
|---|---|
| Host | macOS. iOS automation cannot run on Linux, so iOS work needs a macOS machine or a `macos-*` CI runner |
| Xcode | Full Xcode plus command-line tools; `xcrun simctl` manages simulators |
| Appium driver | `appium driver install xcuitest`; check with `appium driver doctor xcuitest` |
| App artifact | A **simulator** build is a `.app` bundle; a **physical device** needs a signed `.ipa`. These are different artifacts from the same source, and `app.path` would point at the right one per target |
| Signing (physical devices only) | WebDriverAgent must be signed with an Apple Developer team: `appium:xcodeOrgId`, `appium:xcodeSigningId`, and the device UDID registered in the provisioning profile |
| Capabilities | `platformName=iOS`, `appium:automationName=XCUITest`, `appium:deviceName`, `appium:platformVersion`, `appium:bundleId`, and `appium:wdaLocalPort` when running more than one session |

## 4. CI strategy

### One job per platform
Run Android and iOS as **separate jobs**, each with its own runner, device and evidence, rather than mixing
platforms in one JVM. Platform jobs are independent, so they run concurrently whenever runner capacity allows.

| Job | Runner | Device | Notes |
|---|---|---|---|
| Android | `ubuntu-latest` | Emulator via `reactivecircus/android-emulator-runner` | Needs a KVM udev rule for hardware acceleration. Linux runners are cheaper and faster than macOS |
| iOS | `macos-latest` | iOS simulator via `xcrun simctl` | macOS runners are required and are billed at a higher rate |

```yaml
# Sketch only — not implemented, never run.
strategy:
  fail-fast: false            # one platform failing must not hide the other's results
  matrix:
    include:
      - platform: android
        os: ubuntu-latest
      - platform: ios
        os: macos-latest
runs-on: ${{ matrix.os }}
# per job: set up the device, install Appium + the platform driver, start the server,
# make the app artifact available, then:
#   mvn -B clean test -Dplatform=${{ matrix.platform }} -Dapp.path=...
```

### Evidence collection per platform
Each job uploads its own artifact (`test-evidence-android`, `test-evidence-ios`) with `if: always()`, so
evidence survives a failing run:

- `target/cucumber-reports/cucumber.html` — the report, with failure screenshots embedded
- `target/logs/test-execution.log` — the framework log
- `target/surefire-reports/` — TestNG output
- the Appium server log, written outside `target/` so `mvn clean` cannot delete it

### Known CI issues identified during the Phase 10 design
These were found while designing the Android workflow and remain unresolved because the workflow was put on hold:

1. **No approved APK source.** The APK is not in the repository and redistribution permission is unknown.
   GitHub secrets cap at 48 KB, far below the 12 MB binary. The proposal was a Release asset in a private
   repository, downloaded with the built-in `GITHUB_TOKEN`.
2. **Locale affects the date picker.** The date dialog showed `DD/MM/YYYY` on an `en_IN` device. Material 3
   derives that format from the locale, so a default `en-US` emulator would likely expect `MM/DD/YYYY` and the
   ISO date assertion would fail. *Hypothesis, not yet tested.* The fix is Appium's standard `language`/`locale`
   capabilities, left blank for local runs.
3. **Cold emulators are slower.** `-Dtimeout.explicit.wait.seconds=30` in CI only.
4. **Screen height affects scrolling.** Collecting all 40 cards took 48 scrolls on a 2153 px device against a
   `MAX_SCROLLS` guard of 60. A shorter emulator screen needs more scrolls, so either a taller device profile
   or a higher guard is required.

## 5. Parallel execution safety

### Why each concurrent test needs its own session
An Appium session owns one device and one app under test. Two scenarios sharing a session would interleave taps
on the same screen, so results would be meaningless. Isolation must cover the session, the device, the Appium
server ports and the evidence.

### What the current code already does, and what is unproven
- `DriverManager` stores the driver in a `ThreadLocal`, creates one per `startDriver()`, rejects a second start
  on the same thread, and always calls `DRIVER.remove()` in a `finally` block. That is the right shape for
  one-session-per-thread.
- **This has never been executed with more than one thread, so thread safety is unverified.** The classes that
  concurrent threads would share — `ConfigManager` (static read-only properties), `TestDataReader` (a shared
  Jackson `ObjectMapper`, used only for reads) and Log4j `ThreadContext` (per-thread by design) — look safe by
  inspection, but inspection is not evidence.

### Concrete blockers before parallel execution could be enabled
1. **Device allocation.** `device.udid` is a single value, so every thread would target the same device. A pool
   is needed, with each thread claiming one device for the duration of a scenario.
2. **Per-session ports.** UiAutomator2 needs a unique `systemPort` per concurrent session on one Appium server
   (`wdaLocalPort` for XCUITest). Without it, sessions collide.
3. **The runner is sequential.** `TestRunner` would need to override `scenarios()` with
   `@DataProvider(parallel = true)`, and the thread count would need to be bounded by the number of devices.
4. **Evidence isolation.** The log file is shared; lines already carry the scenario name via `ThreadContext`,
   which should be sufficient, but it must be checked under load. The Cucumber report is written once at the
   end and is expected to handle concurrent scenarios, which also needs confirming.

### How it would be validated before being claimed
- Run the suite with two devices and two threads, repeatedly, and confirm every scenario passes and the counts
  are right.
- Confirm no session leaks: `adb devices` clean afterwards, no orphaned UiAutomator2 processes, and a matching
  number of session-start and session-end lines in the log.
- Force a failure in one thread and confirm the other thread's results and screenshots are unaffected.
- Compare against sequential results, so parallelism is shown to change the speed and not the outcome.

## 6. Staged route

Each stage ends with its own evidence, and nothing is claimed before that evidence exists.

| Stage | Goal | Done when |
|---|---|---|
| 1 | Android CI on an emulator (the Phase 10 design, currently on hold) | A green GitHub Actions run with uploaded artifacts |
| 2 | Parallel on Android: device pool, `systemPort`, parallel data provider | Repeated two-device runs green, with isolated evidence and no leaked sessions |
| 3 | iOS locally: XCUITest driver, platform config, iOS locators, the date picker | The five scenarios green on a simulator |
| 4 | An iOS CI job alongside Android in a matrix | Both platform jobs green, with separate artifacts |
| 5 | Device cloud, if an account becomes available | A cloud session green, with artifacts linked from the report |

## 7. Risks and limitations

- **Nothing in this document has been executed.** iOS, cloud, CI and parallel execution are all unimplemented.
- The iOS locator mapping assumes the iOS app exposes identifiers comparable to the Android test tags. If it
  does not, the page layer needs more platform-specific code than estimated here.
- The CI locale issue (section 4.2) is an untested hypothesis. It may not be the only platform difference that
  surfaces on a different device or Android version.
- **A defect to fix before any cloud use:** `DriverManager` logs the full capability map at session start. Cloud
  capabilities carry access keys, so that log line must be redacted first or credentials will be written to the
  log file and CI artifacts.
- `testdata/listings.json` was extracted from the current APK's bundled assets. A different app build, or a
  version that fetches live data, would invalidate the expected values on every platform.
- The framework has been validated on one physical Android device only. Behaviour on emulators, other Android
  versions and other screen sizes is unverified.
