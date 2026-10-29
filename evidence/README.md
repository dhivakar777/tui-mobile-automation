# Execution evidence

Artifacts from real passing runs, committed so results can be inspected without a device.
Regenerate with `mvn clean test`; live output goes to `target/` (git-ignored).

## `passing-run/` — default configuration, physical phone

All files in this folder come from the same run.

| | |
|---|---|
| Command | `mvn clean test` (default configuration) |
| Device | Physical Android phone (OnePlus Nord CE, EB2101, Android 13), Appium 3.2.2, UiAutomator2 7.0.0 |
| Result | **Tests run: 14, Failures: 0, Errors: 0, Skipped: 0** — BUILD SUCCESS, 2:58 min, finished 2026-10-29 06:37:44 +05:30 |
| Breakdown | 5 Cucumber scenarios, all `PASSED`, no retries; 9 `CloudOptionsTest` unit tests |
| Code | The code in this commit |

- `cucumber.html` — 5 scenarios, all passing, no attachments (nothing failed).
- `test-execution.log` — the framework log, unedited; it contains no local file paths. Its first line is a
  `Creating cloud Android session ... bs://fake-app-id` entry from `CloudOptionsTest`, which runs the
  remote-driver path against a local stub server with fake credentials; no BrowserStack session was created.

### `screenshots/`

- `2026-10-29_01_terminal-run-start-category-tabs-passing.png` — start of the run: the first scenario's session
  and its passing steps. Captured by hand from the terminal, cropped to the terminal output.
- `2026-10-29_02_terminal-5-of-5-passed-build-success.png` — the last scenario, then the Surefire summary and
  `BUILD SUCCESS`. Captured by hand from the terminal, cropped to the terminal output.
- `2026-10-29_03_cucumber-report-5-of-5-passed.png` — the committed `cucumber.html` rendered with headless Chrome,
  with the listings feature expanded: 100% (5/5) passed. The "minutes ago" text is relative to render time.

No text in the images was altered.
