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

**Failure evidence.** On failure, the `@After` hook captures one screenshot while the session is still open,
embeds it in the Cucumber report and writes it to `target/screenshots/<timestamp>_<scenario-name>.png`, logging
the path. Capture errors are logged as warnings only, so they cannot skip teardown or replace the scenario's
original failure; this was verified by killing the session inside a temporary failing scenario.

**Retry.** `RetryAnalyzer` (TestNG `IRetryAnalyzer`) is attached to Cucumber's `runScenario` test method by
`RetryTransformer`, an `IAnnotationTransformer` registered as a TestNG listener in the Surefire configuration —
annotation transformers cannot be added with `@Listeners`. It retries only environmental failures (session not
created or lost, device input refused, stale element) and never assertion failures, timeouts or missing
elements, because those can be genuine defects. `retry.max.retries` defaults to 1; 0 disables it. The build
result reflects the final attempt. A retried attempt stays visible: it is a skipped test in the Surefire results,
a `WARN` log line naming the exception, and a FAILED entry in the Cucumber report — which, not knowing about the
TestNG retry, lists the passing attempt as a separate entry and still counts the earlier failure in its summary.

**Safe capability logging.** Session start logs an explicit allowlist of capabilities rather than the full map,
so credentials added later (for example a cloud access key) are never written to logs or reports.

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

### Device locale

The app formats both dates and prices from the device locale. On an en-US emulator the date dialog's input mask
becomes `MM/DD/YYYY`, so typing 7 August yields `2009-07-08`, and prices render as `$1,280` instead of
`£1,280`. Two scenarios failed for exactly this reason on a reviewer's emulator; earlier verification had only
used a phone that was already set to en-GB.

The test data describes UK listings with GBP prices, so the session now pins the device to **en-GB** through
Appium's standard `language` and `locale` capabilities (`device.language`, `device.locale`). Assertions were not
changed. Appium leaves the device in that locale after the run; blanking both keys keeps the device's own locale.
The `$` rendering for GBP prices on a US device looks like an application defect — the currency should follow
the data, not the device — and is reported rather than encoded in a test.

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
| Deferring parallel execution, cloud and iOS; keeping the repository local until the engineer chose to publish it | Engineer's decisions |
| RetryAnalyzer and the CI workflow | Initially deferred by the engineer; later implemented by AI in response to external review feedback that the engineer supplied, with the engineer setting the priorities and acceptance criteria |
| Fixes after external review: locale pin, retry, failure screenshots on disk, build workflow, capability-logging allowlist | Implemented and verified by AI against the engineer's brief; the engineer reviewed the validation report and requested a further review of retry reporting |
| Added assertion that the listings screen is displayed after login | Requested by the engineer during a focused review |
| Clean-checkout verification run | Executed by the engineer; output then checked against the repository |

### Generated output that needed correction

| Issue | Correction |
|---|---|
| `AppiumBy.id(...)` compiled and looked correct but matched nothing at runtime | Compared strategies against a live session; centralized the working one in `BasePage.byTag()` |
| `mobile: scrollGesture` was trusted for its "can scroll more" result, which is always false here | Replaced with a W3C drag; end-of-list derived from visible cards (`9f1244e`) |
| End-of-list compared card indices only, collecting 39 of 40 cards in one run | Compare index and screen position (`9f1244e`) |
| `isDisplayed()` returned `waitForVisible(...).isDisplayed()`, which throws on timeout and can never return false — making the steps' assertion messages unreachable | `BasePage.isVisible()` catches the timeout (`d6b7997`) |
| The first implementation added `DriverFactory`, an env-var config layer and a platform switch with no present user | Removed (`6beac0f`) |
| All runs used one phone that was already set to en-GB, so locale-dependent date and price formatting went unnoticed; an external reviewer's en-US emulator failed 2 of 5 scenarios | Session pinned to en-GB; verified by reproducing both failures, then passing from an en-US starting state |
| Documentation attributed the observed date format to an "en_IN device"; en_IN was the Mac's locale as reported by Maven, not the phone's | Corrected to en-GB |
| Session start logged the full capability map, which would expose credentials such as a cloud access key | Replaced with an explicit allowlist of safe capabilities |
| A commit command chained with `;` committed despite a failing suite, so `0c766a7` records a verification that had not passed at that point | Switched to `&&`; the underlying flake was fixed in `9f1244e` |

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

**Implemented: build validation only.** `.github/workflows/build.yml` runs on push and pull request to `main`:
JDK 17 with Maven caching, `mvn test-compile` (which also runs the enforcer rules), and a Cucumber dry run that
fails the build if any feature step lacks a definition. Reports are uploaded even when a step fails. It launches
no emulator and runs no UI test. The workflow passes `actionlint`, and both Maven commands were run verbatim
locally from a clean `target/`; it has **not yet run on GitHub Actions**.

**Design only — parallel execution, iOS, cloud and UI tests in CI.** None is implemented or tested. The full
design is in `CROSS_PLATFORM_DESIGN.md`; the decisions that shape it are:

| Decision | Reason | Trade-off |
|---|---|---|
| Parallelism through Cucumber's `scenarios()` data provider, bounded by device count, default 1 | All scenarios run through one TestNG method, so the data provider is the only place to parallelise scenarios; a default of 1 keeps runs sequential unless requested | Needs a thread count and device list configured together |
| Device and `systemPort` allocated together from a pool, claimed in `@Before` and released in `finally` | `ThreadLocal` only separates driver references; without allocation every thread would target the same device and could collide on the UiAutomator2 port | More moving parts in session setup |
| Platform chosen by `-Dplatform`, with a `DriverFactory` split per platform | The factory split was removed when only Android existed; a second, genuinely different options builder is what justifies it | Reintroduces a class removed during review |
| Shared feature files, steps and assertions; locators and some page methods per platform | The scenarios are written in business terms, but Compose test tags and iOS accessibility identifiers are located differently, and date entry and masked-field detection differ in interaction | Page classes gain platform branches where screens differ |
| Platform-specific values only through test-data overrides; unsupported scenarios excluded by tag | Keeps assertions identical across platforms instead of weakening them | Requires per-platform data files once values diverge |
| One CI job per platform (Ubuntu emulator, macOS simulator), `fail-fast` off, separate artifacts | iOS automation requires macOS; independent jobs run concurrently and one platform cannot hide the other's result | macOS runners are scarcer and more expensive |

**Next step towards emulator execution in CI.** An Ubuntu job that enables KVM, boots an emulator, installs
Appium 3.2.2 with UiAutomator2 7.0.0, starts the server and runs `mvn clean test -Dapp.path=<apk>`, using the
committed `TUIChallengeApp.zip` once its use is confirmed. A cold emulator may need
`-Dtimeout.explicit.wait.seconds=30`.

## Validation and Requirement Status

**What was actually run (2026-10-25)**, Appium 3.2.2 + UiAutomator2 7.0.0:

| Run | Device | Result | Evidence |
|---|---|---|---|
| Reproduction of the reviewed failures, locale pin disabled (`-Ddevice.language= -Ddevice.locale=`) | Android emulator, API 35, en-US | 5 run, **2 failures** (`2009-07-08`, `$1,280`) | `evidence/failure-reproduction/` — report, log, 2 screenshots |
| Default `mvn clean test`, emulator reset to en-US first | Same emulator | **5 run, 0 failures, 135.8 s**, no retries | `evidence/passing-run/` |
| Repeated default runs | Same emulator | Steady state (already en-GB): 5/5, no retries. Locale transitions from en-US: 4 of 6 passed; both failures came immediately after the emulator had stopped responding and was rebooted, when the first session reported `Cannot set the device locale` | Console output and logs |
| Default `mvn clean test` | OnePlus Nord CE (EB2101), Android 13, en-GB | 5 run, 0 failures, 149.7 s | Console output only |
| Temporary retry check (removed afterwards) | Emulator | Environmental failure retried once and passed; assertion failure ran once and failed; `retry.max.retries=0` disabled retry | Not committed |
| Temporary screenshot-resilience check (removed afterwards) | Phone | Screenshot saved; with the session killed, capture failed with a warning, teardown ran and the original failure was reported | Not committed |
| CI workflow commands, run locally | — | Both steps pass from a clean `target/` | Not run on GitHub Actions |

| Requirement | Status |
|---|---|
| Appium + Java + Maven + Cucumber BDD + TestNG | Implemented, verified |
| Page Object Model with locators confined to page classes | Implemented, verified |
| Externalized JSON test data with strict parsing | Implemented; failure mode observed |
| Cucumber HTML report | Implemented, verified |
| Five scenarios passing on emulator and phone | Implemented, verified |
| Tag-based execution (`@login`, `@listings`, `@smoke`, `@negative`) | Implemented, verified |
| Validated locators | Implemented; exercised by passing scenarios on two devices |
| Logging with per-scenario context | Implemented, verified |
| Failure screenshots on disk and in the report | Implemented, verified with genuine failures |
| Driver lifecycle and teardown on failure | Implemented, verified |
| Configurable device, APK, server, locale and retry settings | Implemented, verified |
| **RetryAnalyzer** | **Implemented, verified** with a controlled temporary scenario; not yet observed absorbing a real flake in the five scenarios |
| Safe capability logging | Implemented, verified in the session log |
| **CI workflow** | **Implemented (build validation only), not yet run on GitHub Actions.** No UI tests in CI |
| `.gitignore` hygiene; no secrets committed | Implemented. History pattern-scanned for credentials; not a full audit |
| **ThreadLocal driver management** | **Implemented, not tested concurrently** — only ever run single-threaded, so thread safety is unverified |
| **Parallel execution** | **Design only, not implemented.** Needs a parallel data provider, a device pool and a unique `systemPort` per session; see `CROSS_PLATFORM_DESIGN.md` §2 |
| **Emulator execution in CI** | **Not implemented** — next step described above |
| **iOS support** | **Design only, not implemented or tested.** No iOS build, XCUITest configuration or iOS code; see `CROSS_PLATFORM_DESIGN.md` §3 |
| **Cloud device execution** | **Investigated, not executed.** No account used. The capability-logging prerequisite it identified is now fixed |

**Other limitations.** Verification covers one emulator and one phone, both forced to en-GB. The expected
listing data is tied to this APK build. Retry absorbs only the listed environmental exceptions; anything else
fails the build on the first attempt. The first session after a locale change is the fragile point: on a
freshly rebooted emulator it failed twice with `Cannot set the device locale`, and one retry was not enough to
absorb it; re-running the suite, with the locale then already set, passed.
