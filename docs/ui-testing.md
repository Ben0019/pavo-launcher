# UI testing

## Interaction smoke test

```sh
python3 tools/ui_visual_smoke.py --serial 192.168.1.10:43929 --actions 6 --lang en
```

Launches every built-in app, OCRs the screen with Tesseract, taps safe controls
(chips, tabs, toggles, `+`/`-`), scrolls, presses BACK and checks logcat for
crashes. DANGER words (delete, reset, stop, uninstall, …) are never tapped.
Screenshots and `visual-report.json` go to `--out` (default
`/tmp/opencode/ui-smoke`).

Options: `--only FanActivity`, `--actions N`, `--lang ko` (forces the app
language before the run).

## Translation coverage on screen

```sh
python3 tools/i18n_screen_audit.py --lang ko --ocr kor+eng
```

Screenshots each app and lists Latin words that are probably untranslated.
Requires Tesseract traineddata; the script reads `/tmp/opencode/tessdata` by
default (`eng`, `kor`, `deu` were used here).

## Accessibility-based test

`tools/ui_smoke.py` is the same idea using `uiautomator dump`. It only works on
screens that reach an idle state (Pavo draws most UI on a canvas, so the OCR
harness is the reliable one).
