# Parallel execution, iOS and CI design

> **Status.** Android is the only implemented platform, and the suite runs **one scenario at a time on one
> device**. Everything in sections 2–5 — parallel execution, iOS support, cloud execution and the CI matrix — is a
> **proposal**: not implemented, not configured and not tested. No runner setting, thread count, port allocation
> or iOS code described here exists in the repository.

## 1. Current state

| Capability | Status | Basis |
|---|---|---|
| Android UI scenarios (5) | **Implemented, locally validated** | 5/5 on an Android emulator (API 35) and on a physical Android 13 phone, 2026-10-25; see `evidence/` |
| Locale pinning (en-GB), retry for environmental failures, failure screenshots, allowlisted capability logging | **Implemented, locally validated** | See `DECISIONS.md` → Validation and Requirement Status |
| GitHub Actions build workflow (compile + Cucumber dry run) | **Implemented, not CI-validated** | Passes `actionlint` and its commands pass locally; it has **not yet run on GitHub Actions**. It runs no UI test |
| `ThreadLocal` driver storage | **Implemented, not tested concurrently** | Only ever exercised single-threaded |
| Parallel execution | **Design only** — section 2 | |
| iOS | **Design only** — section 3 | |
| Cloud devices | **Design only** — section 4 | |
| UI tests in CI; Android + iOS CI matrix | **Design only** — section 5 | |

## 2. Parallel Android execution — design only

### 2.1 Why `ThreadLocal` is not enough

`DriverManager` keeps the driver in a `ThreadLocal`, so two threads would each hold their own session object.
That prevents threads from *sharing a driver reference*, but nothing else:

- every thread would still read the same single `device.udid`, so two sessions would try to drive one device;
- two UiAutomator2 sessions on one Appium server need different on-device server ports;
- `TestRunner` runs scenarios sequentially, so no second thread exists today;
- shared resources — the log file, screenshot directory, Cucumber report, retry counters, device locale — have
  never been observed under concurrency.

`ThreadLocal` is therefore a precondition, not evidence. Thread safety is unverified until the validation in
2.8 has been carried out.

### 2.2 Bounded parallel scenarios (proposed, not enabled)

Cucumber's TestNG runner exposes scenarios through a `@DataProvider` (`scenarios()` in
`AbstractTestNGCucumberTests`). Parallelism would be switched on by overriding it — **this override does not
exist in `TestRunner`**:

```java
// PROPOSED — not in the repository
@Override
@DataProvider(parallel = true)
public Object[][] scenarios() {
    return super.scenarios();
}
```

The worker count would be bounded by TestNG's `dataproviderthreadcount`, passed through Surefire, and must never
exceed the number of available devices. The default would remain **1**, so the suite stays sequential unless
parallelism is requested explicitly. Surefire's own `<parallel>` setting would not be used: all scenarios run
through the single `runScenario` method, so method-level parallelism would not run scenarios concurrently.

### 2.3 One session per worker

Each worker thread would own exactly one Appium session for the duration of one scenario, created in `@Before`
and quit in `@After` — the lifecycle `Hooks` already implements. The existing guarantees carry over: a second
start on the same thread is rejected, and `quitDriver()` always clears the thread slot in a `finally` block.
What is new is that the device and ports for that session would come from an allocator rather than from global
configuration (2.4, 2.5).

### 2.4 Device allocation

A device pool would replace the single `device.udid`:

- a proposed key such as `device.udids` holding a comma-separated list of connected devices or emulators;
- a thread-safe queue of free devices; `@Before` **takes** a device (blocking until one is free), `@After`
  **returns** it in a `finally` block, including when session creation or teardown fails;
- a session is never created without a claimed device, so two workers cannot control the same device;
- the pool is sized from the list, and the worker count is capped at the pool size.

### 2.5 Per-session ports

Each session on one Appium server needs ports that no other live session uses:

| Driver | Port | Purpose |
|---|---|---|
| UiAutomator2 | `appium:systemPort` | Host port forwarded to the UiAutomator2 server on the device |
| UiAutomator2 | `appium:mjpegServerPort` | Only if screen streaming or recording is added |
| XCUITest (future) | `appium:wdaLocalPort` | Host port forwarded to WebDriverAgent |

Proposal: assign ports **with the device**, not independently — each pool entry carries its own fixed port
(for example device *n* uses a fixed base port plus *n*) — so a port is never reused while its device is
claimed, and collisions become deterministic and diagnosable. Before use, each port is checked as free on the
host; on teardown the session is quit so Appium removes its port forward. A cleanup check after a run confirms
no forward or UiAutomator2 server is left behind. The UiAutomator2 driver documents automatic selection of a
free `systemPort` when none is given; that has not been tested here, and explicit allocation is preferred so
failures can be traced to a specific device.

### 2.6 Isolation of state, data and evidence

| Concern | Today | Requirement under parallelism |
|---|---|---|
| Scenario state | Each scenario starts with app data cleared (`noReset=false`); step classes are created per scenario | Unchanged; no static mutable state may be added to steps or pages |
| Test data | JSON read into immutable records; read-only | Safe to share; any future data that is mutated or consumed (accounts, bookings) must be allocated per worker |
| Configuration | Read-only properties and JVM system properties | Device and port values must come from the allocator, not global properties |
| Device locale | Set per session by Appium | Set per device; the first-session locale change is already the most fragile step and would run on every device |
| Logs | One file; every line carries the scenario name | Add the device id to the log context; optionally route each scenario to its own file |
| Screenshots | `target/screenshots/<timestamp>_<scenario>.png` | Add the device id to the name, so simultaneous failures of a retried or duplicated scenario cannot collide |
| Cucumber report | One HTML file written at the end | Confirm that concurrently finishing scenarios are all recorded and correctly attributed |
| Retry | One `RetryAnalyzer` per scenario parameter set (TestNG keeps an analyzer per data-provider parameters) | Confirm the counter is not shared between workers and that a retry claims a device like any other attempt |

### 2.7 Local devices versus a device farm

| Setting | Local | Future cloud / device farm |
|---|---|---|
| Worker count | Number of connected devices or emulators; default 1 | The plan's concurrent-session limit |
| Device allocation | The local pool (2.4) | The provider assigns devices from requested capabilities; no local pool |
| Ports | Allocated per device (2.5) | Managed by the provider; not configured locally |
| Appium server | Local, one server or one per device | The provider's hub, set through `appium.server.url` |
| Credentials | None | From CI secrets or local environment, e.g. `CLOUD_USERNAME` / `CLOUD_ACCESS_KEY` placeholders; never in source, config files or logs |

### 2.8 Risks, prerequisites and validation plan

**Prerequisites:** at least two devices or emulators with enough host CPU and memory to run them together, and a
decision on where per-device configuration lives.

**Risks:** emulator instability under load (one emulator already stopped responding after repeated locale
changes); port collisions with other tools; the Cucumber report and retry under concurrency; longer suite time
than expected if the host cannot sustain several emulators.

**Implementation and validation steps, in order:**
1. Add the device pool and per-device ports; run sequentially with a pool of one to prove nothing regresses.
2. Add the parallel data provider with the thread count still at 1; confirm identical results.
3. Run with 2 devices and 2 workers, repeated several times; results must match the sequential run.
4. Force a failure in one worker; the other worker's result, screenshot and log lines must be unaffected.
5. After each run, confirm no leaked sessions, port forwards or UiAutomator2 servers remain.
6. Exercise retry under concurrency with a controlled temporary scenario, then remove it.
7. Only then document parallel execution as implemented, with the run evidence.

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
- Concurrency would be capped by the plan's session limit (2.7).

## 5. CI strategy — design beyond the existing build job

**Exists today:** `.github/workflows/build.yml` — compile, enforcer rules and a Cucumber dry run on
`ubuntu-latest`. It runs no UI test and has not yet run on GitHub Actions.

**Proposed, not implemented — UI jobs as a matrix with independent platforms:**

| Job | Runner | Device | Key setup |
|---|---|---|---|
| Android UI | `ubuntu-latest` | Emulator (e.g. `reactivecircus/android-emulator-runner`) | Enable KVM; install Appium 3.2.2 and UiAutomator2 7.0.0; start the server and wait for `/status`; unzip the APK; run `mvn clean test -Dapp.path=…` |
| iOS UI | `macos-*` | iOS simulator via `xcrun simctl` | Install the XCUITest driver; build or cache WebDriverAgent; supply the `.app`; run with `-Dplatform=ios` |

- The two jobs would run concurrently when runner capacity allows; `fail-fast` would be off so one platform
  cannot hide the other's result. Parallelism *within* a job follows section 2.
- Each job would upload its own artifact (for example `test-evidence-android`, `test-evidence-ios`) with
  `if: always()`: `target/cucumber-reports/`, `target/screenshots/`, `target/logs/`, `target/surefire-reports/`
  and the Appium server log, written outside `target/` so the clean step cannot remove it.
- A real-device iOS job would need signing material in CI secrets; nothing of that kind exists.
- **Open dependencies:** permission to use the committed `TUIChallengeApp.zip` in CI; an iOS app build; a cold
  emulator may need `-Dtimeout.explicit.wait.seconds=30`; a shorter emulator screen needs more scrolls.

## 6. Risks and limitations

- Parallel execution, iOS, cloud execution and UI tests in CI are unimplemented and untested.
- The iOS design assumes the iOS app resembles the Android one; it has not been seen.
- Locale was the platform difference that surfaced on the emulator; other differences may appear on other
  devices, OS versions or screen sizes.
- `testdata/listings.json` is extracted from the current APK's bundled assets; another build would invalidate it
  on every platform.
- Android validation covers one emulator and one phone, both running in en-GB.
