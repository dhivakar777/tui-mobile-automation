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
