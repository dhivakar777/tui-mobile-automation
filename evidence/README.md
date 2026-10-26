# Execution evidence

Artifacts from two real runs on 2026-10-25, committed so results can be inspected without a device.
Regenerate with `mvn clean test`; live output goes to `target/` (git-ignored).

Both runs used an Android emulator (API 35, 1080×2280) with Appium 3.2.2 and UiAutomator2 7.0.0.

## `failure-reproduction/` — the two reviewed failures, reproduced

| | |
|---|---|
| Command | `mvn clean test -Ddevice.udid=emulator-5554 -Dapp.path=<apk> -Ddevice.language= -Ddevice.locale=` |
| Why the extra flags | Blank `device.language` and `device.locale` switch off the en-GB locale pin, so the emulator keeps its own **en-US** locale — the condition under which the failures were reported |
| Result | **Tests run: 5, Failures: 2** — BUILD FAILURE |
| Failure 1 | *Successful login opens the listings*: date of birth expected `2009-08-07`, found `2009-07-08` |
| Failure 2 | *Hotel cards show the expected details*: price expected `£1,280`, found `$1,280` |

- `screenshots/` — the two PNGs written by the failure hook, unedited. They show the DOB field reading
  `2009-07-08` and the hotel card priced `$1,280`.
- `cucumber.html` — the report, with the same screenshots embedded.
- `test-execution.log` — the framework log. It was produced before the screenshot log line was changed to a
  project-relative path. **Redacted:** the local home-directory prefix was removed from three
  file paths (two screenshot paths and the APK path). Nothing else was changed.

## `passing-run/` — after the fix

| | |
|---|---|
| Command | `mvn clean test -Ddevice.udid=emulator-5554` (default configuration) |
| Starting state | The emulator was set back to en-US immediately before this run, so the pass comes from the locale pin rather than leftover device state |
| Result | **Tests run: 5, Failures: 0, Errors: 0, Skipped: 0** — BUILD SUCCESS, 135.8 s, no retries |
| Code | The final code in this commit |

- `cucumber.html` — 5 scenarios, all passing, no attachments (nothing failed).
- `test-execution.log` — the framework log; it contains no local file paths.

## `passing-run/screenshots/` — manual screenshots of a later run

Captured by hand on 2026-10-26 from a separate local run, not the 2026-10-25 run whose report and log are above.

| | |
|---|---|
| Command | `mvn clean test` with the default configuration |
| Device | Physical Android phone (OnePlus Nord CE, Android 13), mirrored to the desktop |
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

