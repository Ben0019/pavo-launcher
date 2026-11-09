# Pavo changelog

Every release in plain language: what changed, why it matters, and nothing else.

## 1.0.43 — 09 Nov 2026

### Performance overlay

- Rebuilt the performance overlay to work like Bannerlator's Fusion HUD.
- Five sizes — Minimal, Pill, Tiles, Full and Mega — and pinching or the − / + size buttons now step through every one in order instead of jumping between two.
- Each size hugs its content and uses colour-coded monospace readouts: FPS and frame time, GPU and CPU load, clocks and temperatures, RAM, battery, network, per-core load and clocks, average and 1% / 0.1% lows, and a live frametime graph.
- The overlay remembers the size you leave it on.

## 1.0.42 — 08 Nov 2026

### Performance presets

- The performance presets now work like ClusterTune, and you can modify each one however you see fit.
- New fan and performance presets in the Fan Control and Performance Tuner apps. Edit them and they stay as the default; long-press to restore.
- Fan and performance presets are no longer tied together, so you can mismatch them however you like.

### Fan Control

- New HOLD mode lets you hold the fan and temperature at a point of your choice. The fan runs at a constant speed while CPU load and clock stay dynamic.
- Smoother cooldowns and quieter SMALL, MEDIUM and LARGE curves at the same temperatures.

### Quick Controls

- New Quick Controls panel that appears when you swipe the handle at the edge of the screen.
- It can be turned on and off in the Overlay & Drawer app.
- Long-pressing the handle lets you move it along the edge or to a different edge of the display.

#### Quick Controls features

- Brightness and volume control, performance and fan presets, and refresh-rate control.
- Scrolling down reveals buttons for screenshots, the screen recorder, and Touch Mapper for mapping controls in games without controller support.
- A single tap on Screenshot captures the whole screen; a long press opens region capture.
- A single tap on Record starts full-screen recording; a long press records a single app.

## 1.0.41 — 07 Nov 2026

### Performance overlay

- FPS no longer blanks between samples: the last reading and frame-generation state are held for 3 seconds across slow PServer passes, stale layers and pending re-resolves.
- One layer miss is tolerated before re-resolving, and a new layer is seeded with the previous FPS so a one-sample empty window cannot flash 1 fps.
- The held values reset only when the target app actually changes.

### Presets

- Reverted automatic Performance Tuner ↔ Fan Control pairing. Choosing a tuner preset no longer changes the fan preset, and choosing a fan preset no longer applies tuner clocks.
- HOLD still starts the clock-hold service when selected.

### Also in 1.0.40

- SMALL / MEDIUM / LARGE performance presets with paired low-noise fan presets.
- HOLD temperature ceiling with editable approach curve and clock governor.
- Per-core clock hold every 60 ms under load.
- Frame-generation telemetry for Winlator-style containers.
- 25-language localisation.

## 1.0.40 — 07 Nov 2026

### Performance presets

- **SMALL / MEDIUM / LARGE** now use the CQ8725S underclock values; **STOCK** restores full clocks.
- Presets scale to the installed device's frequency steps, snap restored/edited values to supported clocks, and cap every core.
- Preset chips are editable in place; long-press restores factory values.

### Fan Control

- Low-noise **SMALL / MEDIUM / LARGE** curves matched to the performance presets, scaled to the device's thermal trip point.
- New **HOLD** preset: choose a target temperature and hold speed. The curve ends at the hold point with a temperature/speed crosshair; approach points are editable, the target stretches the curve, and the speed scales it.
- Above the hold point the fan ramps gently (5 %/°C), never jumping straight to 100 %.
- A thermal governor lowers CPU/GPU maximums above the target and recovers them when the load allows.
- Colour-coded preset chips; the new labels and hints are localised in all 25 languages.

### Stability / hold

- Clock caps re-apply every 60 ms under load and write every core, closing the vendor stock-clock window.
- Raw junction telemetry drives the HOLD governor.

### Performance overlay

- Frame-generation detection and telemetry for Winlator-style containers; real FPS first with presented total in brackets; correct system-power reporting.

### Joysticks

- Built-in Joysticks app removed; the stick remapper keeps the post-1.0.39 calibration work.

## 1.0.39 — 06 Nov 2026

### Joysticks

- New built-in **JOYSTICKS** app: live pads, per-stick deadzone, response curve and min output, centre/range capture and a 64-point gate measurement.
- Real correction in Pavo's virtual controller, not just a shortcut to the vendor screen: per-axis centre and range, radial deadzone with rescaling, unit-circle diagonal clamping, soft-knee curve and an anti-deadzone floor.
- Per-app stick profiles, using the same app picker as Fan Control, with a **USE GLOBAL** reset.
- ON/OFF switch that keeps the tuning when disabled, live slider apply, and a layout that matches the other built-ins, with an animated icon.

### Controller reliability

- The remapper now recovers when the controller node is recreated (sleep/wake, USB mode switch, re-enumeration) instead of going silent while the game keeps reading raw input.
- Optional raw-vs-corrected trace used to verify the curve during real play.

### File Manager

- Dialog action rows stay inside the card; the app header is hidden while a dialog is open.
- Left stick scrolls archive listings continuously; the right stick moves to **Extract all** / **Close**.
- Long-press a ZIP to get **Extract all**; **COMPRESS** zips any selection next to its source.

### Localisation

- New strings added across all 25 languages.

## 1.0.38 — 06 Nov 2026

### Pavo 1.0.38

#### SCREEN mode
- **480×270** raw capture instead of 320×180, so small text, thin HUD lines and icons survive the downscale.
- **Proximity weighting** to each stick: 1.0 at the outer edge, 0.25 at the screen centre. Left and right LEDs now follow the screen region nearest them.
- The frame's **bright central accent** is blended 50% into the edge colour, so a blue scene with a red/orange portal produces a purple/magenta ring.
- Brightness is now matched by **linear luminance**, not HSV value. Warm amber scenes no longer overpower the screen; blue and warm colours are normalised.
- Linear brightness response across the full scene range, using the 256 available LED levels.
- SCREEN ceiling follows the display brightness perceptually: `user brightness × (display/255)²`.
- Stream processing runs at **60 Hz**, with a 32 rad/s flash attack boost so short bright flashes are not skipped and rise quickly.
- A 0.5% highlight cap keeps detected small text from vanishing; dark scenes still go to zero.

#### Stability
- Includes the 1.0.37 notification, accessibility, charge-guard and ANR fixes.

#### Build
- Version **1.0.38 (103)**
- SHA-256: `685fd8e1b32ccc251cdeb5aab091feead5146cf37901102eb88aedbd93bbc89a`

## 1.0.37 — 05 Nov 2026

### Pavo 1.0.37

#### RGB lighting
- **SCREEN mode** now uses low-latency root `screenrecord` capture at 320×180, giving roughly **25–30 colour updates per second** instead of 5 Hz. The older screencap path remains as a fallback.
- SCREEN, RAINBOW and MULTI run at **60 fps**, with the periodic 80 ms LED-enable hitch removed.
- Smoother wake restore: one LED-enable write at ~650 ms, the last colour held through the panel wake, and no repeated enable writes.
- Scene brightness now follows the whole image plus bright accents, with a strong S-curve and much more obvious dark/bright response.
- White and coloured **text on dark backgrounds** is detected: full-row sampling, lower colour-vote threshold and highlight priority.
- Per-stick independent colour, speed-controlled easing, and the screencap fallback are retained.

#### Stability
- Fixed the main-thread ANR from stopping `PavoInputService`: the raw socket is closed before its readers.
- Accessibility connect no longer restarts services.
- RGB screen-on/off handling now lives in `RgbService`, so it works with accessibility disabled.
- Charge guard keeps separation while the display is off; notification rows hide reliably again.
- Static RGB modes keep a quiet service alive so they are restored after wake.

#### Accessibility
- Setup can enable Pavo accessibility through **PServer**, bypassing Android's restricted-settings gate on rooted devices.
- If PServer is unavailable, Setup shows the App info → ⋮ → Allow restricted settings path with an App info button.
- Fan control falls back to `FanCurveService` when the accessibility host unbinds.
- The new accessibility hint is translated into all 25 languages.

#### Other
- Charge-limit fix for display-off.
- Larger, more readable performance overlay text in panel mode.
- Korean UI now uses **글라스 효과** for glass throughout.

#### Build
- Version **1.0.37 (75)**
- SHA-256: `e79f663028a7a266f1e811e40b159fd791fecabb9bfed32cb8edf5640f52aa23`

## 1.0.36 — 05 Nov 2026

### Pavo 1.0.36

#### 60 fps animations
- SCREEN, RAINBOW and MULTI now animate at 60 fps instead of 30 fps.
- SCREEN measured at 61.4 fps with a 95th-percentile frame time of 18 ms and no stalls across an 8-second run.

#### The real cause of the stutter
- The service re-wrote the LED enable every 250 ms. On this driver each write blocks for about 80 ms, producing four visible hitches per second.
- The enable is now re-asserted only during a short post-wake settle window. Steady-state animation is no longer interrupted.

#### Faster, more accurate SCREEN sampling
- Sample cost reduced from ~208 ms to ~171 ms by skipping rows that are not sampled, using ~50k well-distributed pixels, and replacing per-pixel `pow()`/RGB-to-HSV with a lookup table and inline HSV.
- The 5 Hz capture cadence is now stable.
- The colour spring settles in ~0.22 s at maximum Speed for quicker scene tracking.

#### Capture limit
- `screencap` takes ~137 ms on this device, so 5 Hz is the practical content-sampling ceiling. LED output still interpolates at 60 fps between samples.

Includes the 1.0.34 and 1.0.35 fixes: display-off charge-limit reliability, larger panel-mode overlay text, and per-half SCREEN colour improvements.

#### Build
- Version 1.0.36 (57)
- SHA-256: `6c60c0804fd0712b7ea3f22b956d3d0298521d4b6e81b1c35fb32df3c648551f`

## 1.0.35 — 05 Nov 2026

### Pavo 1.0.35

#### Smoother colour at maximum speed
- SCREEN now uses a critically damped spring instead of a fixed per-frame mix with a hard scene-cut snap. Colour keeps moving between the 5 Hz screen samples and never jumps in a single frame.
- SCREEN, RAINBOW and MULTI animate at 30 fps instead of 14 fps, halving the hue step at high speed.
- Maximum Speed still tracks scene changes quickly; lower settings settle more gently.

Includes the 1.0.34 fixes: display-off charge-limit reliability, larger panel-mode overlay text, and the per-half SCREEN colour improvements.

#### Build
- Version 1.0.35 (56)
- SHA-256: `628d97d6e20c392a4a9f0d97bf69929329173f8591fe283d674c2016da982c0c`

## 1.0.34 — 05 Nov 2026

### Pavo 1.0.34

#### Charge limit now survives the display turning off
- The charge guard keeps a low-importance foreground row and holds a partial wake lock **only while charging**, so the polling loop keeps running in doze instead of being frozen.
- Separation is re-armed with a forced `0 → 1` settings transition, with one retry. The vendor clears the hardware state when the display sleeps but leaves the setting at `1`, so rewriting the same value previously did nothing.
- `POWER_CONNECTED` / `POWER_DISCONNECTED` broadcasts re-arm the guard immediately.
- The notification row now shows live state, for example "Charge separation active · 88%" or "Charging normally · 80%".

#### Performance overlay
- Larger, more readable text in panel mode: preset chip, battery, metric labels and values, FPS, units and per-core rows.
- Panel widened to 330 dp; taller FPS graph and core rows.
- The horizontal strip keeps its original layout and typography.

#### RGB screen-follow mode
- Speed now controls SCREEN easing across the full slider range.
- Large scene changes snap to the new colour instead of smearing; small changes stay gently smoothed.
- SCREEN starts from black rather than easing from the unrelated static colour saved for that stick.
- Sampling raised to 5 Hz with a fixed mild vividness, so pale static colours can no longer mute or grey out SCREEN.
- Left and right halves are sampled independently; verified with a blue/red split image.

#### Build
- Version 1.0.34 (55)
- SHA-256: `bc3d22b96fd2ae819e4cb501785e547829565fd7322b381951daba475878a8bb`

## 1.0.33 — 05 Nov 2026

#### RGB lighting
- **Per-stick control.** A BOTH / EACH switch beside PSERVER shares one colour and mode or lets each stick keep its own. Switching to BOTH adopts the selected stick's colour and mode.
- **Independent animations.** One stick can breathe while the other stays static; rainbow, strobe, multicolour and off can be mixed freely.
- **Gesture toggles.** Tap a joystick to select it. In BOTH, tapping toggles both sticks off/on. In EACH, long-press toggles that stick and restores its last non-off mode.
- **Editable hex code.** Type, delete, copy, paste and cut. Accepts #RRGGBB, RRGGBB and #RGB; invalid input shows a toast and restores the last valid colour.
- **SCREEN mode.** The display is sampled in left and right halves; each stick follows its side using the dominant colour blended with 22% of the local average. 160 ms sampling with smooth easing.
- **Hardware note.** The Odin 3 has one addressable RGB zone per stick. The second zone is not wired and all eight driver channels address the same zone, so two colours are the simultaneous maximum.
- **Localisation.** New RGB strings, including EACH and the invalid-code toast, are translated in all 25 languages.

#### Performance overlay
- Opacity now affects only the panel background; every HUD label, value and caption stays fully opaque.

#### Reliability
- Fixed a foreground-service race in the battery guard that could crash the process and silently stop background services.

#### Documentation
- Attached `Pavo-Features.pdf`, rebuilt for 1.0.33 with the complete RGB feature guide and current overlay behaviour.

## 1.0.32 — 05 Nov 2026

#### Charge separation
- Fixed automatic charge separation failing to hold the limit. The guard now detects the charger from VBUS voltage because separation itself zeroes `usb/online`; previously it treated that as an unplugged cable, released the hold, recharged, and repeated until the battery reached 100%.
- Cable removal must now be seen for three consecutive polls (~15 s) before separation is released, so one bad sample cannot cancel the hold.
- Charge separation state is now a localized toast instead of a persistent notification: active, unavailable, and released to normal charging.

#### Diagnostics
- Added BatteryGuard log lines for separate, hold and release transitions.

## 1.0.31 — 05 Nov 2026

- Korean wording updated from native feedback:
  - 리퀴드 글라스 효과 / 가벼운 글라스 효과
  - 명도 (was 어둡게)
  - 설정 내보내기 / 설정 가져오기
  - 적용중 (was 적용된)
  - 파워 (was 힘)
- CPU and GPU are now shown in English instead of 프로세서 / 그래픽 in the overlay, Performance Tuner, Stress Test and Device Health.

## 1.0.30 — 04 Nov 2026

- First-run Setup screen is now localized in all 25 languages: permission rows, descriptions, ALLOW/SKIP, SKIP ALL/CONTINUE and the status labels.
- Fixed the UI audit tool's language switch; the previous translation report had false positives.
- Re-ran the interaction smoke test over all 17 built-in apps: no crashes, ANRs or blank screens.

## 1.0.29 — 04 Nov 2026

Localization accuracy pass across all 25 languages, plus the tooling used to verify it.

- All languages now contain the same 1956 keys — no missing or leftover entries.
- Fixed two empty-state strings that contained a literal `\n`, so they never matched the translation lookup.
- Restored translated date/time formats (Greek, Czech, Japanese) that had been localised into letters and broke `SimpleDateFormat`.
- Removed keys that were identifiers rather than UI text: file names, socket names, preference keys, JSON/URL fragments, shell commands and bare language codes (`he`, `it`, `id`, `pl`, …) that had been translated as words.
- Added 15 visible strings that were still hard-coded in Java: overlay/drawer toasts, recorder notifications, accessibility descriptions and the `GPU` label.
- The built-in app list now uses each language's own app names instead of English.
- Corrected Czech and Danish wording, and the `Movies/Pavo Recordings` folder name.

Tooling: `tools/i18n_audit.py`, `tools/loc_key_audit.py`, `tools/ui_strings_audit.py`, `tools/i18n_spellcheck.py` (see `docs/i18n-qa.md`).

## 1.0.28 — 04 Nov 2026

- Complete localization for all 25 languages: every visible string, unit and label is translated, including the built-in apps.
- Numbers are plain Arabic numerals in every language (1, 2, 3) — the earlier Korean/kanji/Thai digit transliteration is gone.
- Restored scrolling icon names on the home screen and in the app drawer.
- Touch Mapper fixes:
  - The mapper keeps running when the launcher restarts or the foreground snapshot blips.
  - Controller shortcuts work exactly like normal mode, including long-press for the overlay menu.
  - The shortcut connection now retries and reconnects if the launcher restarted.
  - Opening the performance overlay or the drawer pauses mapping and hides the virtual controls; closing them resumes.
- The performance overlay rebuilds its window if the service was restarted without it.
- Korean pass: corrected mistranslations (Reset, Restore, recordings, 12h vs 12 o'clock, favorites), and stopped translating technical identifiers such as file names, commands and preference keys.

## 1.0.27 — 04 Nov 2026

- Added a UI size setting: Launcher settings → Display → UI size (Auto, 90%, 100%, 110%, 120%).
- Auto uses the physical panel: 110% on the 6-inch Odin 3/Odin 2 and 106% on the 7-inch Portal models.
- Added the Display category to launcher settings and gave it a proper monitor icon.
- UI scaling applies to text and spacing across the launcher and built-in apps.

## 1.0.26 — 03 Nov 2026

- Fan Control: OFF now reliably disables the fan, and RPM estimates use a ceiling probed from the fan node instead of a fixed multiplier.
- RGB Lighting: added the shared AYN/Retroid vendor settings backend, so the same controls work on SN3112 and HTR3212 devices.
- Controller Remapper: detects the connected controller and its actual buttons, supports learning extra buttons, registers the virtual controller dynamically, uses PServer only, and no longer rebuilds/shakes the list when toggling remapping.
- Controller outline: fixed the Z/C pulse positions.
- Performance Tuner: preset clock values now appear immediately instead of after the background refresh.
- Overlay popup: removed the device performance mode section.

## 1.0.25 — 03 Nov 2026

- Korean: fixed charge terms that used the billing meaning — Charge separated is now 충전 분리됨 and Charge type is 충전 유형; other separation strings now use 충전 분리 instead of 전하 분리.
- Korean: the charger card is fully translated (CHARGER, Connection, Input, Input power, Input limit, Power delivery, Charge path, USB / charger).
- Korean: corrected Current → 전류, Power → 전력, Design capacity → 설계 용량, Time to empty → 사용 가능 시간, Charge cycles → 충전 주기.

## 1.0.23 — 03 Nov 2026

- Display options are now read from the panel: refresh rates from supported modes, resolutions from the native short side, HDR types from the display capabilities, and a detected aspect ratio (e.g. "Native 16:9"). The header shows the device name and the PSERVER footer is back.
- Device Health now discovers the battery and charger power supplies instead of assuming Odin's paths, and scales voltage, current, temperature, capacity and resistance by their reported units. Fixed a current-scaling bug that could show a small idle draw as several amps.
- Removed the charge-speed control; charging control is now charge limit and separation only.

## 1.0.17 — 03 Nov 2026

- Performance logs now show power in watts with two decimals (e.g. 0.04 W, 8.72 W) instead of switching to mW below 1 W.

## 1.0.16 — 03 Nov 2026

- Fixed the boot crash on Android 15: the accessibility service read credential-encrypted preferences before the user was unlocked, killing the launcher at every boot and leaving the RGB/notification fixes unapplied until a manual reinstall.
- Updates now install without PServer: the app asks for the per-app "install unknown apps" permission, keeps the downloaded APK, resumes when you return, and handles the installer's pending-user-action result.

## 1.0.15 — 03 Nov 2026

### 1.0.15
Same fixed build as the replaced 1.0.14, published under a new version so Pavo Updates offers it to existing 1.0.14 installs. Animated modes could stay stopped after the screen had been off if the wake event was missed; they now resume on their own or on the next mode selection.

### Joystick lighting
- Static and Off are back on the direct SN3112 path, with the LED colour and brightness owned entirely by Pavo.
- Screen-off/on is fixed for every mode. Static and animated modes wait for the panel's own black LED write, then restore; the animation loop pauses while the panel is blank and re-asserts the enable, so the sticks no longer blink off and stay dark.
- **MULTI** is new: the left and right rings show two different hues at once, offset by 120°, both cycling with the Speed slider. The ring is a single RGB zone on this hardware, so this is a two-ring gradient rather than a per-segment gradient.
- **SCREEN** is back: it samples the dominant on-screen colour through the PServer screencap path.
- **Breath** is now much smoother: ~60 fps, a gamma-corrected fade, persistent LED descriptors, and a display-priority animation thread.

### Also includes 1.0.13
- Built-in app glass no longer turns transparent or blinks after the screen wakes; the live renderer is repaired before the first frame.
- Updates keep the home layout and shortcuts, with a backup for corrupt layouts.
- Joystick LEDs re-enable quickly after wake, and Rainbow no longer flashes its saved colour.
- Pavo Updates and auto-translated release notes in 25 languages; 4:3 panels shrink to fit.

## 1.0.14 — 03 Nov 2026

### Hotfix
This build replaces the original 1.0.14. Animated modes could stay stopped after the screen had been off if the wake event was missed; they now resume on their own or on the next mode selection.

### Joystick lighting
- Static and Off are back on the direct SN3112 path, with the LED colour and brightness owned entirely by Pavo.
- Screen-off/on is fixed for every mode. Static and animated modes wait for the panel's own black LED write, then restore; the animation loop pauses while the panel is blank and re-asserts the enable, so the sticks no longer blink off and stay dark.
- **MULTI** is new: the left and right rings show two different hues at once, offset by 120°, both cycling with the Speed slider. The ring is a single RGB zone on this hardware, so this is a two-ring gradient rather than a per-segment gradient.
- **SCREEN** is back: it samples the dominant on-screen colour through the PServer screencap path.
- **Breath** is now much smoother: ~60 fps, a gamma-corrected fade, persistent LED descriptors, and a display-priority animation thread.

### Also includes 1.0.13
- Built-in app glass no longer turns transparent or blinks after the screen wakes; the live renderer is repaired before the first frame.
- Updates keep the home layout and shortcuts, with a backup for corrupt layouts.
- Joystick LEDs re-enable quickly after wake, and Rainbow no longer flashes its saved colour.
- Pavo Updates and auto-translated release notes in 25 languages; 4:3 panels shrink to fit.

## 1.0.13 — 02 Nov 2026

### Joystick lighting
- Static and Off now use Odin's built-in joystick-light control instead of writing the LED driver directly, so Pavo and the stock RGB page stay in sync and the colour, brightness and on/off state behave like a normal vendor setting.
- Static colours are reapplied reliably after the display wakes, and the saved colour is shared with Odin's own lighting page.
- Animated modes now write both sticks and both LED zones in a single PServer transaction, removing most of the per-frame shell overhead.
- The experimental Screen-follow mode has been removed; the app keeps Static, Breath, Rainbow, Strobe and Off.

### Glass
- Built-in app glass no longer turns transparent or blinks after the screen turns off and on. The live renderer is rebuilt when the display wakes and repaired in `onPreDraw` before the first visible frame.

### Also includes 1.0.12
- Home grid layout and shortcuts survive cold starts and updates without reflowing, with a backup copy for corrupt layouts.
- Joystick LEDs re-enable about 60 ms after wake, and Rainbow no longer flashes its saved base colour on wake.
- RGB service rows stay out of the notification shade while accessibility is bound.
- Pavo Updates and auto-translated release notes in 25 languages; 4:3 screens shrink to fit.

## 1.0.12 — 01 Nov 2026

### Home layout and shortcuts
- Cold starts and updates no longer reflow the grid before it is measured. The fallback capacity (33) was smaller than the saved grid (44), so normalize() moved every icon and shortcut; placements now stay put.
- The layout and custom icon/label JSON are written synchronously, and the previous copy is kept as a backup. A corrupt or missing primary layout restores the backup instead of falling back to an empty default.
- Updates use the normal replace install (pm install -r / PackageInstaller), so app data is kept. Verified a test shortcut stayed at the same cell across an install -r update, and a deliberately corrupted layout restored the full 44-cell layout.

### RGB wake
- The joystick LEDs now re-enable about 60 ms after the device wakes, before the panel finishes drawing, via a background interactive watcher and direct sysfs writes.
- Rainbow no longer blinks on wake: animated modes re-enable only, the first animation frame paints the colour, and the LED enable is written once per wake.
- The RGB foreground row is suppressed while the accessibility binding keeps the process alive.

### Also includes 1.0.11
- Fan: driven through Odin's own provider, High is a hard 100%, and the curve graph reads the real duty and controller temperature.
- Zero persistent service notifications while accessibility is bound.
- Pavo Updates and its updater strings in 25 languages, with auto-translated release notes.
- Performance Tuner and Pavo Updates shrink to fit 4:3 panels.

## 1.0.11 — 01 Nov 2026

### Fan
- High is a hard 100% (50,000) and holds there with no audible stepping. Pavo now drives Odin's own fan provider directly, cancels the vendor's four-second curve loop and writes the duty node itself.
- Curve and Smart now follow the hottest CPU junction sensor. The filtered sensor could read tens of degrees low under load, which made the blower ignore a 100 C chip.
- The fan screen reads the real duty node, so the curve, graph marker and live speed agree. It previously displayed a stale 100% after the provider path was added.
- Curve/Smart damping is unchanged: the fan follows the curve up and down without hunting.

### Notifications
- The fan loop now runs inside PavoA11yService. The persistent service notifications are gone while accessibility is bound; the shade shows no Pavo rows.
- The overlay, hold, guard, controller and per-app loops start without a foreground notification under accessibility. The foreground notifications return as the fallback when accessibility is off.

### Also includes 1.0.10
- Pavo Updates and all updater strings in 25 languages.
- Release notes auto-translate to the selected app/system language, with an English fallback.
- Performance Tuner and Pavo Updates shrink to fit 4:3 panels instead of reflowing.

## 1.0.10 — 01 Nov 2026

### Languages
- The Pavo Updates app and its updater messages are translated into all 25 supported languages.
- Release notes auto-translate to the selected app/system language at runtime, with an English fallback.

### 4:3
- Performance Tuner and Pavo Updates shrink to fit narrower-than-3:2 panels instead of reflowing; the Tuner's clipped subtitle and footer are fixed.
- Strip overlay verified at 4:3 with every metric visible.

Includes all 1.0.9 changes: Pavo Updates app, OTA installs with progress/cancel, 2–24 h checks, greedy fan High at 100%, overlay preset cycling/gestures, faster pinch/drag, crash and home-role fixes.

## 1.0.9 — 01 Nov 2026

### Updates
- New built-in **Pavo Updates** app: current version, full release notes, CHECK AGAIN / INSTALL, and a 2–24 h automatic check slider.
- Over-the-air installs from the latest GitHub release, with SHA-256 verification. Silent via PServer, or the system installer without it.
- Downloading turns the CHECK AGAIN slot into a progress bar with percentage and speed, plus a CANCEL button.
- The Pavo Updates icon animates (turning refresh arrow) and shows a pulsing red dot when an update is available.
- Daily/manual check posts a notification when a new release exists.

### Overlay
- The preset button shows the active tuner profile and cycles QUIET / BALANCED / PERFORMANCE / EXTREME / CUSTOM live, remembering the custom setup.
- Double tap swaps strip/panel, triple tap locks; double-pressing the mapped controller shortcut also swaps modes.
- Strip: metric toggles, GPU live/cap, heat colours, NET toggle, RAM without %, white dividers, pinch scaling.
- Cores stay hidden in strip mode and return in panel mode only if they were on.
- Mode switches paint the last stats immediately; pinch/drag respond faster when the overlay is small.
- Toasts are debounced app-wide: a burst of presses shows only the final state.

### Fan
- High now holds the fan at 100% through the same single-shot controller as Smart/Curve.

### Fixes
- Foreground services post their notification before stopping, so an idle guard can no longer kill the launcher.
- The default home role is kept across updates.
- Toast queueing and the RAM percentage removed; launcher settings no longer duplicate the update controls.

## 1.0.8 — 01 Nov 2026

### Overlay
- Horizontal strip: auto-sizing text row, metric toggles, heat colours, NET toggle, RAM without percentage, white dividers, pinch to resize.
- Preset button: the title shows and cycles QUIET / BALANCED / PERFORMANCE / EXTREME / CUSTOM, applies live, and remembers the custom setup.
- Gestures: double tap flips strip/panel, triple tap locks the position; double-pressing the controller shortcut flips mode.
- Cores stay hidden in strip mode and return in panel mode only if they were on before.
- GPU clock now shows live/cap (e.g. 660M/1.1G).
- Mode switches paint the last stats immediately instead of waiting for the next sample.
- Toasts are debounced app-wide: a burst of actions yields one message for the final state.

### Fan
- Curve pinned to the 08:33 baseline: single-shot mode write, duty-only updates, continuous controller without the hold band.

### Fixes (rebuild)
- Foreground services now post their notification before an early stop, so starting an idle guard can no longer kill the launcher five seconds later.
- The default home role is kept across updates; the device no longer falls back to the Quickstep launcher or the chooser.

## 1.0.7 — 31 Oct 2026

### Smoothness
- 120 Hz animations for built-in app open/close, the app drawer (swipe and dock pop-up) and home paging.
- The drawer glass stays live through a slow swipe and no longer opens on a frozen frame.

### Charging
- A plain charge limit (no auto-separation) is re-armed after updates, reboots and process kills. It was being dropped, which let the pack charge past the set limit.

### Home & wallpaper
- No bundled wallpaper: a fresh install follows the wallpaper already set on the device.
- The wallpaper picker keeps the ring on the applied tile and stays controller-navigable after applying.

### Controller
- BTN_C opens the side app drawer by default.
- Icon-pack and Add-widget cards are navigable with the controller (A selects, L1/R1 switch the widget tabs).
- Recording glyph: a tap opens Screen Recorder, a long press starts a recording.

### Performance overlay
- Upload and download speed row under the GPU clock.

### Fixes
- Pavo home widgets (battery, fan, thermal) paint on the first frame their page appears.
- Right-stick drawer scrolling is driven by the display clock.
- Fan: the curve service no longer fights the firmware's fan daemon, and fan speed is no longer read from the display backlight.

## 1.0.6 — 30 Oct 2026

### New
- **First-run setup** — explains and requests each permission (home role, notifications, overlay, accessibility, battery, all files, microphone, location) with per-item **Allow/Skip** and a global **Skip all**.
- **Fan temperature source** — choose **CPU**, **GPU** or **BOTH** (the hotter of the two), with live CPU/GPU temperatures and a curve graph that follows the selection.
- **Battery temperature and power draw** on the performance overlay.

### Improved
- **Smart fan responds in under a second** — driven from the junction sensors on a 0.5 s loop, while starting silently and idling quietly (12–15 %).
- **Per-app profiles hold for the whole session** — fan and CPU/GPU profiles no longer drop back to global after ~10 s.
- **Clock hold runs in the background** — the tuner profile stays applied in games and benchmarks (150 ms refresh, thermal guard 95 °C).
- **Fresh installs start at Balanced clocks and Smart fan.**
- **GPU load and clock** now read the correct counters on Odin kernels.
- **Battery model** renders on the GPU, appears in the first frame, and keeps its charge pour near full.
- **Pavo appears in the app drawer**, so it can be opened before the home role is granted.

### Fixes
- SystemUI crash when connecting the charger (fan-mode/tile broadcast storm).
- Device Health sheet turning fully transparent over a bright home screen.

## 1.0.5 — 30 Oct 2026

### Fixes
- **SystemUI crashing while charging** — Smart mode, made temperature-responsive in 1.0.3, rewrote `fan_mode` on every 1.5 s control tick. Every write made the firmware re-notify its quick-settings fan tile, so a bad transient state kept taking SystemUI down, and the charger was the trigger that exposed it. Manual control is now claimed once and only the duty is written, and only when it changes.
- The fan still follows temperature normally: 100% under load, then stepping down with the temperature as it cools (verified on an Odin 3).
- Fan/performance pairing is still clamped, so the tile never has to render a mode the current performance state does not offer.
- **Charge pour missing near full** — the stream was skipped when the gap between the port and the water surface fell under 5 dp, so above roughly 90% the tank showed no pour, splash or ripples. A minimum stream length keeps the charge animation alive at any level.

SHA-256: `3b901bf2c41d691ac5eab5c430b848dd364945f2ecd7f454f01b07c3a80f3b89`

## 1.0.4 — 30 Oct 2026

### Improved
- **Device Health stays readable** — the sheet no longer samples a copy of the launcher behind it, so it can't turn see-through when the screen wakes on a charger or the app is reopened from Home.
- **Battery model appears immediately** — the pack is drawn in the same frame as the sheet and the first real reading snaps in, instead of a placeholder tank draining after a late poll.
- **Sharp models on the GPU** — the battery pack is no longer rasterised into a software layer and rescaled by the gyro, so its artwork stays crisp while it tilts.
- **Antialiased fan and controller models** — the rotor rim and the controller's mini display use GPU masks instead of hardware clip paths, removing stepped edges.

### Fixes
- **SystemUI crash when connecting the charger** — switching performance mode restored the previous fan mode blindly, which could leave Quiet selected in High performance (or Custom outside it). The firmware's fan tile can't render that pairing and took SystemUI down on the next fan update, which charging always triggers. Restored modes are now clamped, and an invalid pairing left on the device is repaired on launcher resume and by the battery guard.

SHA-256: `6ebad9c71f704e711e19578d2e9a2582a581399bf24059d64aa8b642a642b2fd`

## 1.0.3 — 30 Oct 2026

### New
- **FTP app** — host internal storage and SD card over the network, browse it from any FTP client, with a QR code for the address. Hosting keeps running in the background.
- **FTP in the file manager** — connecting to a host opens it as a second pane next to local storage; SWAP flips the sides.
- **Live previews** — tap a remote file to open it in the matching viewer (image, video, audio, text) without saving it first. `SAVE` copies it into the local pane instead.
- **Remote file management** — select, copy, cut and paste in both directions, rename, delete (recursively), new folder and info, just like internal/SD.
- **Storage picker on both pane titles** — Internal, SD card and the FTP host on the local side; the host's own roots plus Internal and SD card on the remote side.
- **Per-folder permissions** — FTP app → Host → Permissions: `READ + WRITE` / `READ ONLY` for each shared folder, enforced live by the server (reads always work, writes get 550).
- **ZIP support** in the file manager (browse and extract next to the archive).
- **CPU/core load on locked-down firmwares** — the overlay now falls back to idle residency when `/proc/stat` is blocked.

### Improved
- **Tuner presets follow the device** — Quiet/Balanced/Performance/Extreme are fractions of the phone's own frequency table, snapped to real steps; the chip subtitles use the live tables.
- **Fan SMART mode** now runs through the same 1.5 s temperature controller as Curve, so it ramps up and settles down with load instead of lagging.
- **Applying a preset no longer turns the fan off.**
- **Privileged access is PServer-only** — no `su`, Magisk or KernelSU anywhere; features degrade quietly when PServer is absent.
- **4:3 panels** — the file manager keeps both panes side by side and scales down; FTP dialogs stay clear of the floating keyboard.
- **Built-in apps** launched without a launcher hand-off get a readable frost instead of a transparent card.
- No platform focus block/bloom; accent rings only.
- App drawer last row is reachable again.
- 13 new strings across all 25 languages.

### Fixes
- FTP: taps on Connect and the tabs now register on the first tap.
- FTP: data connections work behind NAT or a port forward (EPSV first, PASV host fallback).
- Overlay shows `—` instead of a fake `0%` when a counter is unavailable.

SHA-256: `4b41bc7261facbad5a4170b6ed058328814d53570d714b43a4d664c86467e9ab`

## 1.0.2 — 29 Oct 2026

Pavo 1.0.2.

**Glass**
- New **Settings → Glass** panel: pick a surface — Dock, App drawer, Popup drawer, Folders, Popups, Launcher settings or Built-in apps — and tune its transparency, saturation and darkening with one set of sliders.
- Built-in app cards gained background saturation and darkening (the captured screen behind the card), 150% / 0% by default.
- The old per-menu duplicates are gone; each surface has its own tuning.
- New defaults ship with this build: dock 57 / 125% / 50%, drawer and popup drawer 53 / 50%, folders 56 / 50%, popups and launcher settings 52–56 / 50%, built-in apps 100 / 50%, dock sheen 33, glass tint 21.

**Folders**
- Dropping an app anywhere on another app now creates a folder; only the outer edges of the cell still swap positions.
- New deterministic route: long-press an app → **New folder with…** → pick the second app. The folder is created in place and opens.

**Controller**
- After closing an app, navigation returns to the icon you launched instead of starting from the status row (the screen-record icon next to Bluetooth).

**Other**
- All new strings translated across the 25 supported languages.

**Package** `com.ben.pavo` · versionCode 3 · Android 12+

## 1.0.1 — 29 Oct 2026

Pavo 1.0.1.

**Fixes**
- Icon labels scroll (marquee) again on the home screen and in the app drawer, and no longer disappear after the launcher idles.
- Clock widget: double-tap finishes resizing again — resizing only starts once the finger moves.
- Side drawer glass shows the real app or wallpaper behind it instead of a near-black slab (the readability scrim was stuck at its no-backdrop default).
- Performance overlay card: frosted glass, drawn entirely in-process — no screen capture, no refresh cost.
- Overlay Controls popup keeps its live captured backdrop and opens with the current frame.

**Also in this build**
- Backdrop scrim now follows the actual capture, so glass cards across the launcher are no longer crushed to black.
- README updated for the Pavo name, package `com.ben.pavo`, build paths and download link.

**Package** `com.ben.pavo` · versionCode 2 · Android 12+

## 1.0.0 — 29 Oct 2026

First **Pavo** release. The launcher was previously developed as “Aurora”; this is a clean 1.0.0 baseline under the new name and package (`com.ben.pavo`).

**Included**
- Device-adaptive layout from 4:3 (1280×960) to 21:9, with hardware detected through PServer.
- Console-style home: paged desktop, Liquid Glass dock, live widgets for fan, battery and thermals.
- Built-ins: Performance Tuner, Fan Control, RGB Lighting, Display, Device Health, Perf Logs, Stress Test, File Manager, Screen Recorder, Controller Remapper, Touch Mapper, Overlay & Drawer.
- Performance overlay with PServer FPS sampling, 0.25–5 s update rate, and a side app drawer with one or two columns.
- File Manager controller navigation with a data cursor and hold-to-fast-scroll.
- The launcher follows the system rotation lock and defaults to the owner’s dock, transparency and wallpaper.

**Defaults shipped**
- Dock: 7 apps, labels off, 0.8 scale, 53% transparency, 26% sheen.
- Settings transparency 52%, glass 10%.
- Bundled wallpaper applied on first launch.
