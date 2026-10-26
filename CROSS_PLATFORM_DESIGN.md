# Parallel execution, iOS and CI

> **Status.** Android is the only implemented platform. Parallel Android execution is **implemented** (section 2):
> scenarios can run concurrently across several devices, each with its own device and UiAutomator2 port, and
> the isolation mechanism has been validated locally. It is off by default. iOS support, cloud execution and the
> UI-test CI matrix (sections 3–5) remain **proposals** — not implemented, not configured and not tested.

## 1. Current state

| Capability | Status | Basis |
|---|---|---|
| Android UI scenarios (5) | **Implemented, locally validated** | 5/5 on an Android emulator (API 35) and on a physical Android 13 phone, 2026-10-25; see `evidence/` |
| Locale pinning (en-GB), retry for environmental failures, failure screenshots, allowlisted capability logging | **Implemented, locally validated** | See `DECISIONS.md` → Validation and Requirement Status |
| GitHub Actions build workflow (compile + Cucumber dry run) | **Implemented, CI-validated** | Passed on GitHub Actions on 2026-10-26 (run [40320669288](https://github.com/dhivakar777/tui-mobile-automation/actions/runs/40320669288)). It runs no UI test |
| Parallel execution across devices (device pool, per-device `systemPort`, parallel data provider) | **Implemented; isolation validated locally** — section 2 | Exercised on an emulator plus a physical phone, 2026-10-26. Off by default |
| iOS | **Design only** — section 3 | |
| Cloud devices | **Design only** — section 4 | |
| UI tests in CI; Android + iOS CI matrix | **Design only** — section 5 | |

## 2. Parallel Android execution — implemented

### 2.1 What exists

| Piece | Where | What it does |
|---|---|---|
| Parallel data provider | `runners/TestRunner` overrides `scenarios()` with `@DataProvider(parallel = true)` | Lets TestNG run scenarios concurrently; all scenarios go through Cucumber's single `runScenario` method, so this is the only switch that parallelises them |
| Thread bound | `parallel.threads` in `pom.xml`, passed to TestNG as `dataproviderthreadcount` | Maximum concurrent scenarios. **Default 1**, so the suite is sequential unless more threads are requested |
| Device pool | `driver/DevicePool`, configured by `device.udids` | A queue of devices, each paired with its own UiAutomator2 `systemPort` (8200, 8201, …). A thread claims a device before its session starts and returns it when the session ends — in a `finally` path, and also when session creation fails |
| Session wiring | `driver/DriverManager` | Sets the claimed device's `udid` and `systemPort` on the session; with `device.udids` blank, the pool is unused and the single `device.udid` applies as before |
| Traceability | `hooks/Hooks`, `log4j2.xml` | Every log line carries the device id; failure screenshots are named `<timestamp>_<device>_<scenario>.png` |

`DriverManager` still keeps one driver per thread in a `ThreadLocal`; on its own that only separates driver
references. Isolation comes from the pool: no two threads can hold the same device or port.

**Run it:**
```bash
mvn clean test -Ddevice.udids=emulator-5554,<phone-udid> -Dparallel.threads=2
```
`parallel.threads` must not exceed the number of devices listed; a thread that cannot claim a device within 10
minutes fails with a message saying so. `device.udid` and `device.udids` cannot be set together.

### 2.2 Validation carried out (2026-10-26, Android emulator API 35 + physical Android 13 phone)

| Check | Result |
|---|---|
| Default configuration unchanged (pool unused, 1 thread) | Same behaviour as before: one thread, no device claims, every session ended |
| Pool with a single device and 1 thread | Device claimed and released once per scenario; `systemPort` 8200 applied; no leaked session or port forward |
| Two devices, 2 threads, repeated | Scenarios ran on both devices at the same time — in every run, scenario pairs overlapped in time on *different* devices and **never on the same device** |
| Port allocation | Each device kept its own `systemPort` (8200 / 8201) for every session |
| Failure isolation | A temporary scenario failing on purpose on one device was reported with its own failure, screenshot and log lines; nothing from it appeared in the other worker's log, and the assertion was not retried |
| Cleanup | After every run: sessions started equalled sessions ended, devices claimed equalled devices released, and no adb port forward remained |

### 2.3 Known limitations found during validation

**Emulator resource limits.** The emulator has 2.5 GB of RAM and was already using swap. Under parallel load it
intermittently failed to start the UiAutomator2 server ("instrumentation process cannot be initialized within
30000ms") or the app, on both the first attempt and the retry, and once ran far slower than usual. These are
emulator limitations, not framework defects; a better-resourced emulator or a second physical device is the
remedy.

**Card-list reading under load — fixed.** Concurrent load first exposed two weaknesses in collecting the virtualised
card list: a single scroll gesture could carry a card past the screen, and the lazy list could reuse a card's
screen node for another card between reading its id and its text. `ListingsPage` now drags back to collect any
skipped card and re-reads the screen when a node changes identity mid-read; both protections were observed
working in later runs.

Whether the Cucumber HTML report attributes every concurrently finishing scenario correctly was spot-checked
from the logs, not exhaustively.

### 2.4 Local devices versus a device farm (design only)

| Setting | Local (implemented) | Future cloud / device farm |
|---|---|---|
| Worker count | `parallel.threads`, bounded by the devices in `device.udids` | The plan's concurrent-session limit |
| Device allocation | `DevicePool` | The provider assigns devices from requested capabilities; no local pool |
| Ports | Allocated per device by `DevicePool` | Managed by the provider |
| Appium server | One local server hosting all sessions | The provider's hub, set through `appium.server.url` |
| Credentials | None | From CI secrets or local environment, e.g. `CLOUD_USERNAME` / `CLOUD_ACCESS_KEY` placeholders; never in source, config files or logs |

## 3. iOS support — design only

Nothing for iOS exists in this repository: no iOS app build, no XCUITest configuration, no iOS code. Android
remains the only implemented and tested platform.

### 3.1 Platform selection and driver creation (proposed)

- A `-Dplatform=android|ios` system property, defaulting to `android`, selects `config/android.properties` or
  `config/ios.properties`. This mechanism existed briefly and was removed when only Android was present.
- A small `DriverFactory` interface with one implementation per platform, chosen by the platform setting.
  `DriverManager` keeps its current role — one session per thread, start, get, quit — and delegates session
  creation to the factory. This is the point at which the factory split, removed earlier as unnecessary,
  becomes worthwhile: there would be two genuinely different builders.

| Branch | Options | Driver | Status |
|---|---|---|---|
| Android | `UiAutomator2Options` | `AndroidDriver` | **Implemented** (today inside `DriverManager`) |
| iOS | `XCUITestOptions` | `IOSDriver` | **Proposed only — not implemented, not tested** |

Both branches would log only the allowlisted capabilities, which would need extending with the iOS names.

### 3.2 iOS prerequisites

- macOS with Xcode and its command-line tools; iOS automation cannot run on Linux.
- An iOS simulator, or a physical device registered with an Apple Developer team.
- Appium's XCUITest driver (`appium driver install xcuitest`), checked with `appium driver doctor xcuitest`.
- WebDriverAgent: built automatically for simulators; for physical devices it must be signed with the team's
  identity (`appium:xcodeOrgId`, `appium:xcodeSigningId`). Team identifiers and any signing material belong in
  the local environment or CI secrets, not in committed configuration.
- An iOS build of the app: a `.app` bundle for simulators, a signed `.ipa` for devices. None is available today.

### 3.3 Platform-specific configuration (proposed `config/ios.properties`)

| Key | Purpose |
|---|---|
| `app.path` or `app.bundle.id` | Install from a `.app`/`.ipa`, or launch an installed app by bundle identifier |
| `device.name` | Simulator or device name, e.g. `iPhone 15` |
| `platform.version` | iOS version of the simulator or device |
| `device.udid` | Required for physical devices; optional for simulators |
| `device.language`, `device.locale` | The same en-GB requirement as Android, applied through the XCUITest equivalents; how fully they apply on physical devices must be checked |
| `wda.local.port` | Per-session WebDriverAgent port, needed once iOS runs in parallel |

### 3.4 Page objects and locators

The same locator strategy cannot be assumed to work on both platforms.

- **Android today:** Jetpack Compose test tags exposed as resource-ids without a package prefix, located with
  `UiSelector().resourceId(...)` through `BasePage.byTag()`.
- **iOS:** elements are identified by `accessibilityIdentifier`, located with `AppiumBy.accessibilityId(...)`;
  patterns such as "all card names" need an iOS predicate string or class chain instead of `resourceIdMatches`.
- **Proposal:** page classes keep one set of business methods (`logIn`, `selectTab`, `card`), and each locator is
  resolved per platform. Where the iOS app uses the same identifier strings, only `byTag()` branches; where
  identifiers differ, the page declares a per-platform locator pair. Screens whose interaction genuinely differs
  get platform-specific methods or subclasses.
- **Known Android-specific code** that would need iOS equivalents: `UiSelector` locators in `BasePage` and
  `ListingsPage`; the `resource-id`, `password` and `selected` attribute reads; `android.widget.*` and
  `android.view.View` class-name lookups; and the Material 3 date dialog in `LoginPage.selectDateOfBirth()`.

### 3.5 Scenario reuse

The feature files and step definitions are written in business terms, so the five scenarios are candidates for
reuse; whether each works on iOS depends on the iOS app, which has not been seen.

| Scenario | Expected reuse | Likely platform-specific work |
|---|---|---|
| Mandatory fields are validated | Feature and steps reusable | Error-label locators |
| Successful login opens the listings | Feature reusable | Date entry through `UIDatePicker`; the displayed date format may differ |
| Password visibility can be toggled | Feature reusable | Detecting a masked field (secure text field type rather than an attribute) |
| Category tabs show the matching listings | Feature and steps reusable | Selected-tab state and the card-name pattern; scrolling untested on iOS |
| Hotel cards show the expected details | Feature and steps reusable | Field locators; price formatting depends on locale |

**Test data and assertions:** assertions stay in shared step definitions and do not branch on platform. Where
an expected value genuinely differs (a display format, for example), the platform's value would come from a
platform-specific test-data override, used only for that field. A scenario that does not apply to one platform
would be excluded with a tag such as `@android-only`, rather than by weakening its assertions.

### 3.6 Risks and implementation plan

**Risks:** the iOS app may not expose identifiers comparable to the Android test tags; date entry and masked-field
detection differ in interaction model, not just locators; device signing and provisioning are slow to set up;
macOS runners are scarcer and billed at a higher rate.

**Steps:** obtain an iOS simulator build; inspect it with Appium Inspector to record its identifiers; add the
platform switch and the `DriverFactory` split with Android unchanged and re-validated; add the iOS options and
one scenario (*Mandatory fields are validated*); extend scenario by scenario; then run all five repeatedly on a
simulator before considering devices or CI.

## 4. Cloud devices — design only

Investigated only; no cloud account was used and no cloud session has been created.

- `appium.server.url` would point at the provider's hub; the provider would allocate devices and ports.
- Apps are uploaded to the provider and referenced by the identifier it returns, instead of a local `app.path`.
- Credentials come from CI secrets or the local environment (placeholders such as `CLOUD_USERNAME` and
  `CLOUD_ACCESS_KEY`) and are never written to source, configuration, reports or logs. Capability logging is
  already an explicit allowlist, so provider credentials passed as capabilities would not be logged.
- Concurrency would be capped by the plan's session limit (2.4).

## 5. CI strategy — design beyond the existing build job

**Exists today:** `.github/workflows/build.yml` — compile, enforcer rules and a Cucumber dry run on
`ubuntu-latest`. It passes on GitHub Actions and runs no UI test.

**Proposed, not implemented — UI jobs as a matrix with independent platforms:**

| Job | Runner | Device | Key setup |
|---|---|---|---|
| Android UI | `ubuntu-latest` | Emulator (e.g. `reactivecircus/android-emulator-runner`) | Enable KVM; install Appium 3.2.2 and UiAutomator2 7.0.0; start the server and wait for `/status`; unzip the APK; run `mvn clean test -Dapp.path=…` |
| iOS UI | `macos-*` | iOS simulator via `xcrun simctl` | Install the XCUITest driver; build or cache WebDriverAgent; supply the `.app`; run with `-Dplatform=ios` |

- The two jobs would run concurrently when runner capacity allows; `fail-fast` would be off so one platform
  cannot hide the other's result. Parallelism *within* a job would use the device pool in section 2.
- Each job would upload its own artifact (for example `test-evidence-android`, `test-evidence-ios`) with
  `if: always()`: `target/cucumber-reports/`, `target/screenshots/`, `target/logs/`, `target/surefire-reports/`
  and the Appium server log, written outside `target/` so the clean step cannot remove it.
- A real-device iOS job would need signing material in CI secrets; nothing of that kind exists.
- **Open dependencies:** permission to use the committed `TUIChallengeApp.zip` in CI; an iOS app build; a cold
  emulator may need `-Dtimeout.explicit.wait.seconds=30`; a shorter emulator screen needs more scrolls.

## 6. Risks and limitations

- iOS, cloud execution and UI tests in CI are unimplemented and untested. Parallel execution is implemented, with the limitations in 2.3.
- The iOS design assumes the iOS app resembles the Android one; it has not been seen.
- Locale was the platform difference that surfaced on the emulator; other differences may appear on other
  devices, OS versions or screen sizes.
- `testdata/listings.json` is extracted from the current APK's bundled assets; another build would invalidate it
  on every platform.
- Android validation covers one emulator and one phone, both running in en-GB.
