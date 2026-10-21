# Execution evidence

A snapshot of one real test run, committed so results can be inspected without a device.
Regenerate it at any time with `mvn clean test`; the live output goes to `target/` (git-ignored).

## Run details

| | |
|---|---|
| Date | 2026-10-21, 15:02 IST |
| Commit under test | `da79754` ("Make screen-visibility checks return false on timeout; assert listings after login") |
| Checkout | Clean `git clone` of this repository into `/tmp/tui-clean` (21 tracked files, no local modifications) |
| Command | `mvn clean test` (no `-D` overrides) |
| Device | OnePlus Nord CE (EB2101), Android 13, physical, USB |
| Appium | Server 3.2.2, UiAutomator2 driver 7.0.0, `http://127.0.0.1:4723` |
| Result | **Tests run: 5, Failures: 0, Errors: 0, Skipped: 0** — BUILD SUCCESS |
| Scenario duration | 176.6 s (total Maven time 3:00) |

## Files

| File | Contents |
|---|---|
| `cucumber.html` | The Cucumber HTML report: 5 scenarios, 42 steps, all PASSED. Open it in a browser |
| `test-execution.log` | The framework log at DEBUG, with the scenario name on every line |

This run contains **no embedded screenshots**, because no scenario failed. Screenshots are attached to the
report only on failure; that behaviour was verified separately by forcing an assertion failure and extracting
the embedded PNG (see `DECISIONS.md`, section 7).
