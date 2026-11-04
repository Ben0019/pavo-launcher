# Built-in app test report

Run: `tools/ui_visual_smoke.py` and `tools/i18n_screen_audit.py`
Device: Odin 3 (Android 15), Pavo 1.0.29, English UI for the interaction pass,
Korean UI for the translation pass. Raw data and screenshots are in
`/tmp/opencode/ui-smoke/` and `/tmp/opencode/i18n-screens/`.

## Interaction pass (17 apps, 100 screenshots)

Every built-in screen was opened, tapped, scrolled and closed with BACK.

- No crashes, ANRs, force-finishes or blank screens.
- All activities opened and reacted to input.
- BACK returned to the launcher from every app except:
  - **Performance Logs** – the screen stays open; BACK is probably consumed by
    a panel or the detail view. Needs a manual check.
  - **File Manager** – BACK navigates to the parent folder, which is intended.
  - **Launcher home** – BACK does nothing, which is intended.

## Translation coverage seen on screen (Korean)

These screens still render English text with the app set to Korean:

- **Setup** – the whole first-run wizard is hard-coded English (0 calls to
  `Loc.s`): title, every row title and description, `SKIP ALL`, `CONTINUE`,
  `GRANTED`.
- **Overlay controls** – `OVERLAY CONTROLS`, `METRICS`, `CPU temperature`,
  `CPU clock`, `CPU load`, `GPU …`, `Network speed`, `Horizontal strip` and the
  drag hint are English.
- **Controller Remapper** – `NO CONTROLLER DETECTED · standard buttons`,
  `OFF · mappings are saved but not applied`, the L1/R1 footer and
  `ALL APPS ▾` are English.
- **File Manager** – `INTERNAL`, `REFRESH`, `NEW FOLDER`,
  `LONG PRESS TO SELECT` are English.
- **Screen Recorder** – runtime status lines (`Recording failed`,
  `Could not create recording file`, …) are English.
- The weather line inside the overlay controls header showed `Rain` in English.

## Code-level sweep

`tools/ui_strings_audit.py` and a wider literal sweep found 264 candidate
user-visible strings that never reach the catalogue. The largest groups:

| file | strings |
| --- | --- |
| FtpServer.java | 41 (protocol replies — not UI) |
| MainActivity.java | 27 |
| ScreenRecorderEngine.java | 19 |
| FtpClient.java | 17 (protocol — not UI) |
| SetupActivity.java | 15 |
| ScreenRecorderActivity.java | 12 |
| ScreenRecorderService.java | 10 |
| ControllerMapActivity.java | 9 |
| DeviceHealthActivity.java | 8 |
| PerformanceTunerActivity.java | 6 |
| Updater.java | 6 |

FTP protocol replies are network protocol text and do not need translation.
The rest are real UI gaps.

## Suggested next steps

1. Route `SetupActivity.text()` / `button()` through `Loc.s` and add its 15
   strings to the catalogue.
2. Do the same for `PerfOverlayMenuView`/`PerfControlActivity` and
   `ControllerMapActivity`.
3. Translate the Screen Recorder runtime messages.
4. Decide whether manifest activity labels should be localized (they show in
   Recents/App info) or whether the launcher's own name mapping is enough.
