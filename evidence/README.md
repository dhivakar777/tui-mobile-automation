# Execution evidence

Artifacts from real passing runs, committed so results can be inspected without a device.
Regenerate with `mvn clean test`; live output goes to `target/` (git-ignored).

## `passing-run/` — default configuration, physical phone

| | |
|---|---|
| Command | `mvn clean test` (default configuration) |
| Device | Physical Android phone (OnePlus Nord CE, EB2101, Android 13), Appium 3.2.2, UiAutomator2 7.0.0 |
| Result | **Tests run: 14, Failures: 0, Errors: 0, Skipped: 0** — BUILD SUCCESS, 3:20 min, finished 2026-10-29 06:22:43 +05:30 |
| Breakdown | 5 Cucumber scenarios, all `PASSED`, no retries; 9 `CloudOptionsTest` unit tests |
| Code | The code in this commit |

- `cucumber.html` — 5 scenarios, all passing, no attachments (nothing failed).
- `test-execution.log` — the framework log, unedited; it contains no local file paths. Its first line is a
  `Creating cloud Android session ... bs://fake-app-id` entry from `CloudOptionsTest`, which runs the
  remote-driver path against a local stub server with fake credentials; no BrowserStack session was created.

## `passing-run/screenshots/` — manual screenshots of an earlier run

Captured by hand on 2026-10-26 from a separate local run, not the 2026-10-29 run whose report and log are above.

| | |
|---|---|
| Command | `mvn clean test` with the default configuration |
| Device | Same physical phone, mirrored to the desktop |
| Result | **Tests run: 5, Failures: 0, Errors: 0, Skipped: 0** — BUILD SUCCESS, 2:46 min, finished 2026-10-26 17:18:53 +05:30 |

- `2026-10-26_01_terminal-run-start-category-tabs-passing.png` — start of the run: the first scenario's session
  and its passing steps.
- `2026-10-26_02_terminal-5-of-5-passed-build-success.png` — the last scenario, then the Surefire summary and
  `BUILD SUCCESS`.
- `2026-10-26_03_cucumber-report-5-of-5-passed.png` — the Cucumber HTML report: 100% (5/5) passed.

**Cropped, not otherwise edited.** The originals were full-desktop screenshots; they were cropped to the
terminal output and the report body to remove personal and workplace details (the desktop dock and menu bar, an
IDE status bar and a browser address bar containing a local home-directory path). No text in the images was
altered.
