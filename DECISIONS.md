# Design Decisions

Android UI automation for `TUIChallengeApp.apk`. Setup and usage: `README.md`. Extended cross-platform notes:
`CROSS_PLATFORM_DESIGN.md`.

## Technology and Project Structure

Appium (java-client 10.1.1) + Java 17 + Cucumber BDD 7.34.9 + TestNG 7.12.0 + Maven, driving Android through
the UiAutomator2 driver. Jackson reads JSON test data; Log4j2 handles logging.

Version choices were made for mutual compatibility rather than recency. java-client 11 was avoided because it
changes the driver API (`AppiumDriver` is no longer a `RemoteWebDriver`). Cucumber 8 was avoided because it
deprecates `cucumber-testng`, and TestNG integration is required. Selenium is pinned via `selenium-bom`
because java-client declares it as an open range, which would otherwise let the resolved version drift between
builds. `maven-enforcer-plugin` fails the build on divergent transitive versions.

### Structure and boundaries

```
src/test/java/com/tui/automation/
  config/           ConfigManager          settings: config.properties, overridable with -D
  driver/           DriverManager          Appium session lifecycle, ThreadLocal driver
  pages/            BasePage, LoginPage,   locators and UI interaction
                    ListingsPage, ListingCard
  hooks/            Hooks                  per-scenario start/teardown, failure screenshot
  stepdefinitions/  LoginSteps,            Gherkin glue and assertions
                    ListingsSteps
  runners/          TestRunner             TestNG + Cucumber entry point
  utilities/        TestDataReader + data records
src/test/resources/  features/ · testdata/ · config/ · log4j2.xml
```

Everything sits under `src/test`; the project ships no production code.

**Page Object Model.** Page classes own locators and interactions and return values. They contain no
assertions. Step definitions own assertions and contain no locators. This boundary is what makes a UI change
land in one file.

**`BasePage`** holds only what both pages genuinely share: the driver reference, one `WebDriverWait` built from
configuration, the Compose test-tag locator, and visibility helpers. Page-specific behaviour — the date dialog,
tab selection, card reading, list scrolling — stays in the page that owns it. The alternatives were a static
helper, which would duplicate wait and driver setup per page, or inlining the Compose locator workaround at
roughly 25 call sites. Selenium's `PageFactory` was rejected because it obscures when lookups occur, which
conflicts with explicit waits and with Compose elements recreated on recomposition.

**Driver lifecycle.** `Hooks` is the single owner: one session per scenario, created in `@Before` and quit in
`@After` inside a `finally` block so teardown survives a failing screenshot. `DriverManager` holds the driver
in a `ThreadLocal`, rejects a second start on the same thread, and clears the slot on quit. No `BaseTest`
exists; a base class alongside hooks tends to duplicate setup.

**Configuration and test data.** Two layers only: `config.properties` for committed defaults, and `-Dkey=value`
per run. Six keys: server URL, device UDID, APK path, app package, app activity, explicit-wait timeout.
Required values fail with a message naming the key. The framework reads only these properties — it does not
load `.env` or environment variables (`.env` is git-ignored as a precaution, not consumed).

Test data is externalized to `testdata/login.json` and `listings.json`, deserialized into Java records with
strict Jackson settings, so a missing or misspelled field fails immediately with the file and field named.
Expected listing values were extracted from the APK's own bundled `assets/*.json`, so assertions compare the UI
against the application's data source rather than hand-copied numbers. This couples the data file to this APK
build; different content requires regeneration. The committed `user1` credentials are not a secret — the login
accepts any non-empty value.

**Reuse.** Config, driver, hooks, runner, reporting and logging are app-agnostic. A different application
would need new page objects, locators, feature files and test data, and a different platform would additionally
need platform-specific capabilities and interactions.

### Simplifications after review

An architecture review was commissioned before adding features. Each of the following was removed after
confirming it had no user: a separate `DriverFactory` (one caller, merged into `DriverManager`), an
environment-variable configuration layer, a `-Dplatform` switch for a hypothetical iOS target, a configurable
`app.no.reset` (clearing app data per scenario is a test-isolation rule, not an environment setting, so it is
now fixed), two unused config keys, and an HTTP probe that duplicated information already in the Selenium
exception. The config and driver layers went from 274 to 196 lines with no loss of capability.

## Locator Strategy and Application-Specific Decisions

The app is built with Jetpack Compose with test tags exposed as resource-ids. Those ids carry no `package:id/`
prefix, which Appium's `id` strategy expects — so `AppiumBy.id` silently fails on every element despite the id
being visible in a UI dump. Four strategies were compared against a live session before choosing:

| Strategy | Result |
|---|---|
| `id`, bare tag and package-prefixed | Not found |
| `-android uiautomator` → `UiSelector().resourceId(...)` | Found — adopted |
| `xpath` on `@resource-id` | Found, slower |

The working strategy is centralized in `BasePage.byTag()`. Accessibility ids are used where the app supplies
them; class names only to reach a child inside an already-identified parent. Locators were discovered from live
`uiautomator` dumps on a connected device and validated by execution — a dump shows which attributes exist, but
only a running session shows which strategy can match them.

### Virtualized listings

The listings screen is a Compose `LazyColumn`: only the cards on screen exist in the hierarchy, so the page
source never contains all 40 and the list must be scrolled and accumulated. Results are collected into a map
keyed by the index embedded in each card's test tag, which de-duplicates across overlapping screens and
preserves display order.

Two decisions shaped the implementation:

- **A W3C touch drag rather than `mobile: scrollGesture`.** Appium log timings showed the gesture at roughly
  2.7 s per call, and it consistently reported that it could not scroll further, which ended collection after
  one screen. The drag includes a brief pause before release to prevent a fling that would skip cards.
- **End-of-list detected by card index *and* screen position.** Index alone ended collection one card early,
  because a tall card's name remains visible at a new position after a genuine scroll. A stale-element re-read
  was also needed, since the lazy list can recompose between finding an element and reading it.

Trade-off: collecting three tabs takes roughly 115 s. Shorter, faster drags were measured and rejected because
they raise the risk of skipping a card, and the exact-list assertion was judged more valuable than the time.

## AI Tool Usage and Corrections


Engineering direction, review and scope control came from the engineer, who explored the application manually
before the automation work began, applied prior Appium and test-automation experience to the design choices,
and reviewed the generated code rather than accepting it as produced.

| Area | Attribution |
|---|---|
| Implementation code, discovery scripting, log analysis, documentation drafting | AI-generated, engineer-reviewed |
| Manual exploration of the app before automation; prior automation experience applied to design | Engineer |
| Phased delivery with validation gates; commissioning the architecture review; requiring justification before any new helper or base class | Engineer's instruction |
| `BasePage` responsibilities | Options and trade-offs presented by AI; **selection by the engineer** |
| Removing the `DriverFactory` split, env-var layer, platform switch and configurable `noReset` | Proposed by AI in the commissioned review; **approved by the engineer** |
| Scenario selection | Candidates proposed by AI from observed behaviour; **assessed and confirmed by the engineer** against release risk |
| Deferring RetryAnalyzer and parallel execution; holding the CI workflow; keeping the repository local | Engineer's decisions |
| Added assertion that the listings screen is displayed after login | Requested by the engineer during a focused review |
| Clean-checkout verification run | Executed by the engineer; output then checked against the repository |

### Generated output that needed correction

| Issue | Correction |
|---|---|
| `AppiumBy.id(...)` compiled and looked correct but matched nothing at runtime | Compared strategies against a live session; centralized the working one in `BasePage.byTag()` |
| `mobile: scrollGesture` was trusted for its "can scroll more" result, which is always false here | Replaced with a W3C drag; end-of-list derived from visible cards (`f06c069`) |
| End-of-list compared card indices only, collecting 39 of 40 cards in one run | Compare index and screen position (`f06c069`) |
| `isDisplayed()` returned `waitForVisible(...).isDisplayed()`, which throws on timeout and can never return false — making the steps' assertion messages unreachable | `BasePage.isVisible()` catches the timeout (`da79754`) |
| The first implementation added `DriverFactory`, an env-var config layer and a platform switch with no present user | Removed (`cdad739`) |
| A commit command chained with `;` committed despite a failing suite, so `24f08ca` records a verification that had not passed at that point | Switched to `&&`; the underlying flake was fixed in `f06c069` |

Debugging was carried out within the session from log and session evidence, with the engineer reviewing the
diagnosis and fix. Commit authorship is the engineer's on all commits with no co-author trailers, so the
division above reflects the working session rather than git metadata.

## Test Scenarios and Selection Rationale

Discovery on a real device preceded selection, and four observations shaped it: the login screen does **not**
verify credentials (any non-empty values reach the listings); a future date of birth is accepted; tapping a
card's price produces no visible change; and Hotels display a per-person price rounded down while Holidays
display a total, with no distinguishing label. The first two look like product defects and are reported rather
than encoded as failing tests. The third has nothing observable to assert.

| Scenario (feature file) | Value for release confidence |
|---|---|
| Mandatory fields are validated | Login is the only entry point. Asserts all three field-level errors and that the app stays put, so validation cannot silently disappear |
| Successful login opens the listings | The main journey, plus the date picker's `dd/MM/yyyy` → ISO conversion — a real in-app transformation — and arrival on the listings screen with the default tab |
| Password visibility can be toggled | Guards a visible-credential regression: masked, revealed with matching icon label, masked again |
| Category tabs show the matching listings | Asserts exact card names in display order per tab (40 / 20 / 20) and that exactly one tab is selected, so wrong filtering cannot pass unnoticed |
| Hotel cards show the expected details | Asserts name, destination, rating, board type and price for the first three cards against data extracted from the APK. Wrong prices are harder to spot than an outage |

Together these exercise both screens and every interactive control, across validation, navigation, filtering
and data rendering. No two overlap, and none merely asserts that an element is present.

Rejected candidates: invalid-credentials error (the app has no credential check), future-DOB rejection (the app
accepts it), price-button navigation (no observable outcome), image loading (depends on network state — images
failed during development because of a device proxy), and a standalone "listings load" scenario (already
covered above).

## CI and Cross-Platform Execution Design

**Design and proposal only — not implemented.** There is no `.github/` directory in this repository; no
workflow has been written, statically checked or executed. An Android workflow was designed in detail and then
held. Nothing below has run.

**Separate jobs per platform.** Android on a Linux runner with an emulator; iOS on a macOS runner with a
simulator, because iOS automation requires macOS and Xcode. The jobs are independent, so they can run
concurrently when runner capacity allows, and neither needs the other's toolchain. Each job sets up JDK 17 with
Maven caching, installs Appium and its platform driver, starts the server as a background step and waits for it
to report ready, then runs `mvn clean test` with the platform and APK path passed as `-D` overrides. A Maven
failure fails the job, so a genuine test failure fails the pipeline.

**Platform selection** would reintroduce the `-Dplatform` switch and per-platform property files removed
earlier, with the driver options split into one builder per platform. The existing `@login`, `@listings`,
`@smoke` and `@negative` tags remain available through `-Dcucumber.filter.tags`, and a platform tag could
exclude a scenario that does not apply to one platform.

**Isolation for parallelism.** One device and one session per job. Parallelism *within* a job additionally
requires a device pool so each thread claims its own device, a unique Appium server port per concurrent
session, and confirmation that evidence stays separated. The current test data is read-only, so concurrent
reads are safe; any scenario that mutated shared state would need per-thread data.

**Evidence** would be uploaded per platform even when a job fails: the Cucumber HTML report with embedded
failure screenshots, the execution log, TestNG output, and the Appium server log written outside `target/` so
the build's clean step cannot remove it.

**Open issues found while designing it.** No approved APK source exists — the binary is not committed, and
GitHub secrets cap well below its size, so a private release asset was proposed. Device locale affects the date
dialog's input mask (`DD/MM/YYYY` was observed on an `en_IN` device), so a differently configured emulator may
break the login scenarios; this is an untested hypothesis. Cold emulators need a longer explicit wait, and a
shorter emulator screen needs more scrolls than the physical device used here.

## Validation and Requirement Status

**What was actually run.** `mvn clean test` from a clean `git clone` of commit `da79754` on 2026-10-21 against
a physical OnePlus Nord CE (Android 13) with Appium 3.2.2 and UiAutomator2 7.0.0: **5 scenarios, 0 failures,
0 errors, 176.6 s, BUILD SUCCESS.** The report and log from that run are committed in `evidence/`; the report
records 42 passing steps and no failures. Screenshot-on-failure was exercised separately by temporarily
changing an expected value and confirming the attached image showed the failing screen; the all-green evidence
run correctly contains no attachments.

| Requirement | Status |
|---|---|
| Appium + Java + Maven + Cucumber BDD + TestNG | Implemented, verified by the run above |
| Page Object Model with locators confined to page classes | Implemented, verified |
| Externalized JSON test data with strict parsing | Implemented; failure mode observed |
| Cucumber HTML report | Implemented, verified (`evidence/cucumber.html`) |
| Terminal execution from a clean checkout | Implemented, verified |
| Five scenarios | Implemented, verified against the feature files |
| Tag-based execution (`@login`, `@listings`, `@smoke`, `@negative`) | Implemented, verified |
| Validated locators | Implemented; taken from live dumps and exercised by passing scenarios |
| Logging with per-scenario context; failure screenshots | Implemented, verified |
| Driver lifecycle and teardown on failure | Implemented, verified |
| Configurable device, APK and server settings | Implemented, verified |
| `.gitignore` hygiene; no secrets or APK committed | Implemented, verified |
| **ThreadLocal driver management** | **Implemented, not verified** — present in `DriverManager`, but only ever run single-threaded, so it has not been proven by a parallel run |
| **RetryAnalyzer** | **Not implemented.** No retry analyzer or listener wiring exists in the repository. Deliberately deferred |
| **Parallel execution** | **Not implemented and not validated.** No parallel data provider or thread configuration. Blockers listed in the CI section |
| **CI workflow** | **Not implemented.** Design only; never executed |
| **iOS support** | **Design only.** No iOS build, macOS setup or XCUITest configuration |
| **Cloud device execution** | **Investigated, not executed.** No account used and no session created. The investigation identified the requirements and one prerequisite defect: `DriverManager` logs the full capability map, which would write a cloud access key into logs and artifacts, so that line must be redacted first |
| Emulator execution | **Not validated.** An emulator was started during development, but the suite was never completed on one. All verification used a single physical device |

**Other limitations.** Verification covers one device, one Android version and one locale. The expected listing
data is tied to this APK build. One environmental failure was observed and not reproduced
(`InvalidElementStateException`, with input injection refused in the device log) — it is documented in
`README.md` rather than hidden behind a retry. Statements marked "verified" rest on command output and
artifacts observed during the working session; apart from the clean-checkout run, they were not independently
re-executed.
