# Localization QA

Three small tools verify the runtime catalogue (`assets/i18n.json`). All of them
are read-only unless noted.

```sh
python3 tools/i18n_audit.py assets/i18n.json      # structure / placeholders / whitespace
python3 tools/loc_key_audit.py                     # every Loc.s key used in code exists
python3 tools/ui_strings_audit.py                  # UI literals that never reach the catalogue
python3 tools/i18n_spellcheck.py                   # hunspell pass (needs dictionaries)
```

`i18n_audit` checks, per language:

- missing keys (against the union of all languages)
- format specifiers (`%s`, `%d`, `%1$s`, …) that differ from the English key
- newline count changes, leading/trailing whitespace changes
- non-ASCII digits (numeral localization)
- values identical to the English key
- doubled spaces and suspiciously long translations

`i18n_spellcheck` downloads `wooorm/dictionaries` into `/tmp/opencode/dicts`
and reports unknown words per language. Brand names, acronyms and English terms
that also appear in the input key are ignored.

## Results of the last pass

- All 25 languages now have the same 1956 keys; zero missing.
- Zero placeholder mismatches, zero localized digit characters.
- Fixed literal `\n` in keys/values (two empty-state strings never matched before).
- Restored date/time patterns that had been translated (Greek, Czech, Japanese):
  `HH:mm`, `MMM d, yyyy · HH:mm:ss`, `EEE MMM dd`.
- Removed 165 identifier keys (file names, socket names, prefs, JSON/URL fragments)
  and 30 language-code keys (`he`, `it`, `id`, `pl`, …) that had been translated as words.
- Removed 350 shell-command keys from the catalogue.
- Added 15 user-visible strings that were hard-coded in Java (toasts, content
  descriptions, recorder notifications, the `GPU ` label).
- Localized the built-in app list sentence with each language's own app names.
- Corrected Czech/Danish wording and the `Movies/Aurora Recordings` folder name.

## Known follow-ups

- Manifest activity labels are English-only; the launcher localizes built-in
  names itself, so this only shows in system surfaces (Recents, App info).
- Some strings still say "Aurora" where the product is "Pavo"; that is a source
  rename, not a translation issue.
- Japanese/Korean/Chinese keep three whitespace-only differences around
  `\nCPU`, `\nRAM`, `\nthrottle`.
