# Design decisions

Why this framework is shaped the way it is, what was rejected, and which of the original requirements are
actually implemented. Cross-platform, CI and parallel execution design lives in `CROSS_PLATFORM_DESIGN.md`;
setup and usage live in `README.md`.

## 1. Discovery before implementation

Nothing application-specific was written until the app had been inspected on a real device:

- `aapt dump badging` gave the package (`com.tui.qa.challenge`) and launch activity (`MainActivity`), so
  neither was guessed.
- `unzip -l` revealed `assets/hotel-results.json` and `assets/holiday-results.json` bundled in the APK. The
  expected listing values in `testdata/listings.json` were extracted from those files, so assertions compare
  the UI against the app's own data source rather than hand-copied numbers.
- Live `uiautomator dump` output provided every locator. The app is Jetpack Compose with
  `testTagsAsResourceId` enabled, which is why stable resource-ids exist at all.

**Behaviour found that changed what is worth testing:**

| Finding | Effect on the suite |
|---|---|
| Login does **not** verify credentials — any non-empty username and password succeeds | No "wrong password" scenario. Asserting an error that the app never shows would be inventing behaviour |
| A future date of birth (01/01/2099) is accepted | Not tested. Likely a product defect; a test for it would fail against current behaviour. Reported here instead |
| Hotels show the **per-person** price (rounded down: 989.5 → £989); Holidays show the **total** price, with no label distinguishing them | Scenario 5 asserts what the UI shows today. The inconsistency is reported as a finding |
| Tapping a card's price button does nothing visible | Not tested; there is no observable outcome to assert |
| The date dialog has a text-input mode | Used by `LoginPage.selectDateOfBirth()`, instead of paging a calendar back 17 years |
| Confirming `07/08/2009` makes the field display `2009-08-07` | Asserted, because it proves the picker's format conversion |

## 2. Dependency versions

Versions were chosen for mutual compatibility, not by taking the newest of each.

| Decision | Reason | Trade-off |
|---|---|---|
| **java-client 10.1.1, not 11.0.0** | Version 11 was released days earlier and breaks the API: `AppiumDriver` is no longer a `RemoteWebDriver`, and PageFactory and the wait engine moved out | Not on the newest major version. Upgrading later is a deliberate, testable change |
| **Selenium 4.44.0 pinned via `selenium-bom`** | java-client declares Selenium as a range `[4.42.0, 5.0)`. Unpinned, a build could silently change version the day Selenium publishes a release. 4.44 is the version java-client 10.1.1 fixed compatibility against | Manual bumps needed to move Selenium forward |
| **Cucumber 7.34.9, not 8.x** | Cucumber 8 deprecates `cucumber-testng` for removal in favour of the JUnit Platform engine. The assignment requires TestNG integration | Not on the newest major version; a future move to the JUnit Platform engine is a known migration |
| **`maven-enforcer-plugin` with `dependencyConvergence`** | Fails the build when two libraries pull different versions of the same transitive dependency — the usual cause of runtime `NoSuchMethodError` | A stricter build that can fail on an otherwise harmless upgrade. Worth it for reproducibility |
| **Java 17 release target** | Cucumber 7's baseline, and the standard LTS on CI runners. Compiles on newer JDKs | — |
| **Log4j2 with the SLF4J bridge** | java-client logs through SLF4J; the bridge routes those into the same configuration as framework logs, so one file holds both | One extra dependency |

## 3. Architecture

### Layering
`runner → feature → step definition → page object → DriverManager → ConfigManager`, one direction only.

- **Pages** own locators and interactions. They contain no assertions and read no test data, so a locator
  change touches exactly one file.
- **Step definitions** own assertions. They contain no locators, so a wording change in a feature touches
  only the step.
- **`ListingCard` is a record** used for both "what the screen shows" and "what the JSON expects", so
  `assertEquals(actual, expected)` compares five fields in one assertion with a readable diff.

### Decisions taken during the architecture review
The first implementation was deliberately reduced after review. Each removal was based on actual usage, not taste.

| Change | Reason | Trade-off |
|---|---|---|
| **`DriverFactory` merged into `DriverManager`** | Two classes for one responsibility; the factory was package-private with a single caller | A second platform would justify splitting it again. Doing so is mechanical |
| **Environment-variable config layer removed** | Used nowhere except a validation run; CI can pass `-Dapp.path=$APP_PATH` just as easily | One override mechanism instead of two. Secrets would need another approach, but this project has none |
| **`-Dplatform` and `<platform>.properties` removed** | Existed only for a hypothetical iOS; a single `config.properties` is enough today | Reinstating it is a rename plus a few lines. Documented in `CROSS_PLATFORM_DESIGN.md` |
| **`getBoolean()` and `app.no.reset` removed; `noReset=false` hard-coded** | Clearing app data per scenario is a **test-isolation rule**, not an environment setting. Making it configurable would let someone break scenario independence | Changing it now means editing code, which is the point |
| **`device.name` and `timeout.new.command.seconds` removed** | UiAutomator2 ignores `deviceName` on Android; the command timeout never needed to vary | Fewer knobs |
| **`/status` probe for session diagnostics removed** | Duplicated what Selenium's exception cause already reports | A slightly less friendly message; the hint moved to the README |
| **`get()` made required, `getOptional()` added** | Four of six keys are mandatory; a missing one should fail loudly with a message that says where to set it | — |
| **No `BaseTest`** | Cucumber `Hooks` is the single owner of the driver lifecycle. A `BaseTest` alongside hooks is the classic source of duplicated setup | — |

Result: the config and driver layers went from 274 to 196 lines with no loss of function.

### `BasePage`: the one abstraction added, and why
Every locator in this Compose app needs
`AppiumBy.androidUIAutomator("new UiSelector().resourceId(\"tag\")")`, across roughly 25 locators in two pages,
plus a shared explicit wait.

| Option | Verdict |
|---|---|
| **`BasePage` with the driver, the wait, `byTag()` and `waitForVisible()`** (chosen) | One place for the Compose workaround and the timeout. Inheritance is justified because both pages need exactly the same three things |
| A static `Locators.byTag()` helper | No inheritance, but the wait setup and driver lookup would be duplicated per page |
| Inline in every locator | Longest code; a typo in the UiSelector string would only surface at runtime, 25 times over |
| Selenium `PageFactory` / `@FindBy` | Rejected: hides when lookups happen, and works poorly with re-rendered Compose elements |

### Locator strategy
`AppiumBy.id` **does not work** for this app. Compose test tags appear as resource-ids with no package prefix,
and the `id` strategy expects `package:id/name`. This was proven by trying four strategies against a live
session over raw HTTP:

| Strategy | Result |
|---|---|
| `id` = `login_screen_top_app_bar` | fails |
| `id` = `com.tui.qa.challenge:id/login_screen_top_app_bar` | fails |
| `-android uiautomator` → `UiSelector().resourceId(...)` | **works** (chosen) |
| `xpath` → `//*[@resource-id=...]` | works, but slower |

Accessibility ids are used where the app provides them (`Switch to text input mode`), and class names only for
a child element inside an already-identified parent.

### Screen-visibility checks
`isDisplayed()` originally returned `waitForVisible(root).isDisplayed()`, which can never return `false`: on
timeout it throws, so the assertion messages in the steps were unreachable. `BasePage.isVisible()` now catches
`TimeoutException` and returns `false`, so a failed login reports "Listings screen should be displayed after
login" rather than a raw timeout on a resource-id. The waiting behaviour is unchanged.

## 4. Scrolling a virtualised list

`ListingsPage.allCardNames()` is the most involved code in the framework. Compose's `LazyColumn` only
materialises the one or two cards on screen, so the page source never contains all 40. Every element of the
design fixes a failure that was actually observed:

| Element | Why |
|---|---|
| Collect into a `TreeMap` keyed by the index in the test tag (`content_card_hotel_name_<n>`) | De-duplicates across overlapping screens and preserves display order |
| **A W3C touch drag instead of `mobile: scrollGesture`** | Measured from the Appium log: `scrollGesture` cost 2.7 s per call (275 s of a 404 s run). The drag is about 0.67 s. It also always reported "cannot scroll further", which ended collection after one screen |
| A 150 ms pause before releasing the finger | Prevents a fling, which would skip cards |
| A 60% drag rather than 80% | At 80%, one run collected 39 of 40 cards |
| **End of list = the same cards at the same screen positions** | Comparing indices alone ended collection one card early: the log showed card 2 at `y=1632` still visible at `y=514` after a real scroll. Position is what distinguishes "nothing moved" from "a tall card is still in view" |
| Re-read the screen on `StaleElementReferenceException`, up to 3 attempts | Observed in a real run: the lazy list recomposed between `findElements` and `getText` |
| `MAX_SCROLLS = 60` | A runaway guard only; the stop condition is "nothing moved". Note that the All tab already uses 48 on a 2153 px screen, so a shorter screen may need a higher guard |

**Why the scenario takes ~115 s:** collecting three lists costs about 101 s (48 + 23 + 24 scrolls), of which
the drags are ~64 s and `findElements` ~19 s. Faster drags were measured and rejected for now because they
raise the risk of skipping a card, and the exact-list assertion is worth more than the saved seconds.

## 5. Configuration and test data

- **Two layers only:** `config.properties` for committed, safe defaults, and `-Dkey=value` for per-run
  overrides. Rejected alternative: surefire `<systemPropertyVariables>` in the pom, which is less code but
  breaks IDE runs and mixes configuration into the build file.
- **JSON into records, with Jackson failing fast.** `FAIL_ON_MISSING_CREATOR_PROPERTIES` and
  `FAIL_ON_NULL_CREATOR_PROPERTIES` are enabled, and unknown fields fail by default, so a typo in a data file
  produces "Missing creator property 'password' … testdata/login.json" rather than a `NullPointerException`
  mid-scenario. Verified by deleting a field.
- **`user1`/`user1` is committed** in `testdata/login.json`. This is not a secret: the app accepts any
  non-empty value, so the string has no access value. Real credentials would be passed as `-D` properties or CI
  secrets instead, and `.env` is git-ignored.
- **Listing expectations are derived from the APK's bundled assets**, which keeps the assertions honest but
  couples them to this app build. A new APK needs `testdata/listings.json` regenerated.

## 6. The five scenarios

Chosen after exploring the app, to cover both screens, every interactive control and the main risks, with no
overlap between scenarios.

| # | Scenario | Tags | What it protects against |
|---|---|---|---|
| 1 | Mandatory fields are validated | `@login @negative` | Validation silently disappearing. Checks all three field-level errors and that the app stays on the login screen |
| 2 | Successful login opens the listings | `@login @smoke` | The main journey, plus the date picker's `dd/MM/yyyy` → ISO conversion, the listings screen, and the default tab |
| 3 | Password visibility can be toggled | `@login` | Masking regressions. Asserts the masked state, the revealed plain text, the icon label, and masking again |
| 4 | Category tabs show the matching listings | `@listings` | Filtering and navigation. Asserts the exact names in order per tab (40 / 20 / 20) and that exactly one tab is selected |
| 5 | Hotel cards show the expected details | `@listings` | Data rendering. Asserts name, destination, rating, board type and price for the first three cards against the APK's data |

No scenario merely checks that an element exists. Each asserts behaviour or content.

## 7. Reporting and debugging

- **One report, not two.** The Cucumber HTML plugin writes `target/cucumber-reports/cucumber.html`. A second
  reporting library would duplicate the same information.
- **Screenshots via `scenario.attach()`**, the standard Cucumber API, embedded in that report. No screenshot
  utility class and no separate image folder to keep in sync. Verified by forcing a failure and extracting the
  embedded PNG: it showed the exact failure state.
- **The scenario name on every log line** via Log4j `ThreadContext` (`%X{scenario}`), so interleaved or
  parallel output stays attributable.
- **Evidence capture never breaks teardown:** the `@After` hook takes the screenshot inside a `try`, with
  `DriverManager.quitDriver()` in the `finally`. `hasDriver()` prevents a screenshot attempt when session
  creation failed.
- Console at INFO for readability, file at DEBUG for investigation, third-party libraries at WARN.

## 8. CI

**Not implemented.** An Android GitHub Actions workflow was designed in Phase 10 and put on hold before any
file was created, so there is no `.github/workflows/` directory. The design, the chosen runner strategy for
Android and iOS, and the four open issues found while designing it (APK source, device locale, emulator
timeouts, scroll limits on shorter screens) are recorded in `CROSS_PLATFORM_DESIGN.md`.

The framework is already CI-ready in the sense that every environment-specific value is a `-D` override and all
evidence lands under `target/`, which is straightforward to upload as artifacts.

## 9. Requirement status

| Requirement | Status | Evidence or note |
|---|---|---|
| Appium + Java + Maven | **Implemented and validated** | `mvn clean test` drives a real device |
| Cucumber BDD with Gherkin | **Implemented and validated** | 2 feature files, 5 scenarios |
| TestNG integration | **Implemented and validated** | `TestRunner extends AbstractTestNGCucumberTests`; surefire reports TestNG results |
| UiAutomator2 driver for Android | **Implemented and validated** | Driver 7.0.0, Appium 3.2.2 |
| Pinned, mutually compatible versions | **Implemented and validated** | BOM pins plus `dependencyConvergence` passing |
| Page Object Model | **Implemented and validated** | `pages/` holds all locators; steps hold none |
| JSON external test data | **Implemented and validated** | `testdata/*.json` into records; failure modes tested |
| Cucumber HTML report | **Implemented and validated** | `target/cucumber-reports/cucumber.html`, contents parsed |
| Logging | **Implemented and validated** | `target/logs/test-execution.log` with scenario context |
| Failure screenshots | **Implemented and validated** | Verified with a deliberate failure; PNG extracted and viewed |
| Exactly five meaningful scenarios | **Implemented and validated** | Section 6; all assert behaviour |
| Validated locators | **Implemented and validated** | Captured from live dumps and exercised through real sessions |
| Tag-based execution | **Implemented and validated** | `-Dcucumber.filter.tags="@smoke"` ran 1 of 5 |
| `mvn clean test` from the terminal | **Implemented and validated** | 5/5 green from a **clean `git clone`** of commit `da79754`, 2026-10-21; report and log in `evidence/` |
| ThreadLocal driver management | **Implemented, not validated** | The code is in `DriverManager`, but it has never been exercised with more than one thread |
| Reliable driver lifecycle and teardown | **Implemented and validated** | Teardown observed to run on failing scenarios; `finally` clears the thread slot |
| Configurable device settings | **Implemented and validated** | `-Ddevice.udid`, `-Dapp.path`, `-Dappium.server.url`, timeouts all exercised |
| No dead code or duplicate scenarios | **Implemented and validated** | Every method is referenced; step methods are invoked by Cucumber annotations |
| Git repository hygiene | **Implemented and validated** | `target/`, IDE metadata, `*.apk` and `.env` ignored; no secrets tracked |
| README and DECISIONS | **Implemented** | This file, `README.md`, `CROSS_PLATFORM_DESIGN.md` |
| **Retry handling** | **Deferred, not implemented** | Descoped by the user. No retry analyzer or listener exists |
| **Parallel execution** | **Deferred, not implemented** | Descoped by the user. ThreadLocal is a precondition only; see the blockers in `CROSS_PLATFORM_DESIGN.md` |
| **GitHub Actions CI** | **Deferred, not implemented** | Designed, then put on hold. No workflow file exists |
| **iOS support** | **Design only** | `CROSS_PLATFORM_DESIGN.md` |
| **Cloud execution** | **Design only** | Same document, including a credential-logging defect to fix first |
| Emulator execution | **Not validated** | An emulator was booted during development; the suite was never completed on it |

## 10. AI assistance and corrections

This framework was built with Claude Code. The generated output needed correction in several places, which is
worth recording because the failures are instructive:

| Generated | Problem | Correction |
|---|---|---|
| `AppiumBy.id("login_screen_top_app_bar")` | Looked correct and failed at runtime; Compose tags carry no package prefix | Proved the working strategy against a live session, then centralised it in `BasePage.byTag()` |
| `mobile: scrollGesture` with its "can scroll more" return value | Scrolled, but always returned `false`, so collection stopped after one screen | Replaced with a W3C drag; end of list detected from the visible cards |
| `scrollGesture` with a serialised `WebElement` as `elementId` | Appium expects the raw element id string | Pass `((RemoteWebElement) el).getId()` |
| End-of-list detection by card index | Missed one card when a tall card stayed in view | Compare index **and** y position |
| `isDisplayed()` returning `waitForVisible(...).isDisplayed()` | Could never return `false`, making the steps' assertion messages unreachable | `BasePage.isVisible()` catches the timeout |
| `DriverFactory` + `DriverManager` + an env-var config layer + a platform switch | Enterprise-shaped abstractions with no present need | Removed during the architecture review (section 3) |
| A commit command chained with `;` after a test run | Committed despite a failing suite, so commit `24f08ca` claims a verification that had not passed at that point | Switched to `&&` so commits only follow green runs. The flake was fixed in `f06c069` |

An unexplained environmental failure also remains on record: one run in four hit
`InvalidElementStateException` with `Input event injection failed` in logcat — Android refusing a synthetic
touch. It was not reproduced and is documented in the README rather than hidden behind a retry.
