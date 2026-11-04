# Built-in app test report

Tools: `tools/ui_visual_smoke.py` (interaction) and
`tools/i18n_screen_audit.py` (on-screen translation).
Device: Odin 3 (Android 15), Pavo 1.0.30.
Raw data: `/tmp/opencode/ui-smoke/` and `/tmp/opencode/i18n-screens/`.

## Interaction pass (17 apps)

Every built-in screen was opened, tapped, scrolled and closed with BACK.

- No crashes, ANRs, force-finishes or blank screens.
- All activities opened and reacted to input.
- BACK returns to the launcher from every app except:
  - **File Manager** – goes up one folder (intended).
  - **Launcher home** – BACK does nothing (intended).

## On-screen translation pass (Korean)

The audit now really switches the app language before running; an earlier run
tested English by mistake, which made several screens look untranslated.

Confirmed gaps in that earlier report and their status:

| screen | result |
| --- | --- |
| Setup | **was fully English** – fixed in 1.0.30 (`text()` now goes through `Loc.s`, 19 new strings) |
| Overlay controls | already localised — false positive |
| Controller Remapper | already localised — false positive |
| File Manager | already localised — false positive |
| Screen Recorder | already localised — false positive |

Remaining Latin text on Korean screens is app names (`LocalSend`,
`Backdrops`), file-system folders (`Alarms`, `Audiobooks`) and technical tokens
(`msm-adreno-tz`, `D-PAD/L-STICK`). None of them are translation bugs.

## Known follow-ups

- Manifest activity labels are English-only; the launcher maps built-in names
  itself, so this only shows in system surfaces (Recents, App info).
- Some source strings still say "Aurora" where the product is "Pavo".
