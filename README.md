# Aurora

A landscape-first home screen for the **AYN Odin 3** (`NX789J`), built as a pure-platform
Android app (no AndroidX, no Gradle). It replaces the stock launcher with a console-style
home: a large clock, a paged desktop, a clear Liquid Glass dock and matching app drawers.

| Home | Fan Control | Performance Tuner |
| --- | --- | --- |
| ![Home](docs/home.png) | ![Fan Control](docs/fan-control.png) | ![Performance Tuner](docs/performance-tuner.png) |

| Stress Test | Performance Log |
| --- | --- |
| ![Stress Test](docs/stress-test.png) | ![Performance Log](docs/performance-log.png) |

## Features

- Landscape-first console home for the AYN Odin 3; pure platform, no AndroidX or Gradle.
- Clock, date, weather and live status (CPU, network, battery, Bluetooth, sound).
- Paged icon grid with folders, drag and drop, resizable widgets and a grid-snapped clock.
- Liquid Glass dock with full and popup app drawers, built for controller-first navigation.
- Wallpaper card with live, video and recent wallpapers; IN USE marker and long-press remove.
- Wallpaper-derived accent across home, dock, drawer, built-in apps and the overlay.
- OLED protection: 1 px spiral pixel shift and a 10% idle dim, without freezing live data.
- Performance overlay with CPU/GPU/RAM/FPS, per-core telemetry, lock and direct controls.
- Tuning, display, fan, RGB, controller, touch, file and media apps built in.
- 25 languages; resolution, scaling and orientation aware.

## Wallpaper

On the Odin 3 the stock wallpaper is the framework default particle image
(`framework-res` → `drawable-nodpi/default_wallpaper.png`, 1920×1080). The system never writes
it to `/data/system/users/0/wallpaper`, so a normal app calling
`WallpaperManager.getDrawable()` only receives an Android-14+ *colour-extracted gradient*
fallback — not the real wallpaper. Aurora therefore ships a copy of the device wallpaper
(`res/drawable-nodpi/device_wallpaper.png`, 284 KB) and uses it as the default backdrop so the
home screen matches the lock screen exactly. You can override it at any time with
**Settings → Choose image…** (any PNG/JPEG on device), or switch to the generated **Aurora**
backdrop.

## Weather

The clock widget shows the current temperature and WMO condition. Data comes from
[Open-Meteo](https://open-meteo.com/) (free, no API key). Location resolution:

1. If **Use device location** is enabled and the coarse-location permission is granted, Aurora
   asks the network provider for a fix (last-known fix first, then a single current-location
   request).
2. Otherwise it resolves an approximate location from the IP (`ipapi.co`, falling back to
   `ipwho.is`).

The result is cached for 30 minutes and refreshed on resume / every 30 minutes / by tapping the
weather line or **Settings → Refresh weather now**. Only the coordinates are sent to the weather
service. If all providers fail, the cached reading (if any) stays on screen.

## Build

Prerequisites, installed under `$HOME/.local/opt` (no system packages required):

- **JDK 21** at `~/.local/opt/jdk` (Eclipse Temurin).
- **Android SDK** at `~/.local/opt/android-sdk` with `platforms;android-35` and
  `build-tools;35.0.0`.

```bash
cd aurora
./build.sh          # -> Aurora.apk
```

`build.sh` runs `aapt2 compile/link` → `javac` → `d8` → `zipalign` → `apksigner`. Override
paths with `ANDROID_SDK_ROOT` / `JAVA_HOME` if your toolchain lives elsewhere.

## Install

```bash
export PATH=$HOME/.local/opt/platform-tools:$PATH
adb install -r Aurora.apk
```

### Make Aurora the home app

```bash
adb shell cmd package set-home-activity com.ben.aurora/.MainActivity
```

### Revert to the stock launcher

```bash
adb shell cmd package set-home-activity com.android.launcher3/.uioverrides.QuickstepLauncher
```

### Enable the notification-shade gesture (optional)

Either use the prompt shown the first time you swipe down on the home, or:

```bash
adb shell settings put secure enabled_accessibility_services \
    com.ben.aurora/com.ben.aurora.AuroraA11yService
adb shell settings put secure accessibility_enabled 1
```

Disable it again in **Settings → Accessibility → Aurora**.

## Project layout

```
AndroidManifest.xml              package/orientation/HOME intent filter, a11y service
build.sh                         manual aapt2/javac/d8/apksigner build
res/                             drawables, strings, theme, a11y config, device wallpaper
src/com/ben/aurora/
  MainActivity.java              desktop, header, dock, drawer, folders, menus, dialogs, gestures
  Pager.java                     horizontal snapping page pager
  GridPage.java                  one home page: multi-widget/cell layout and drop highlight
  ItemView.java                  icon + label cell with tap / long-press / drag handling
  Store.java                     persisted pages, dock, AppWidgets and per-app overrides (JSON)
  Item.java                      desktop item model (app / web shortcut / folder)
  IconPack.java                  appfilter.xml/drawable.xml catalogue + themed bitmap resolver
  WidgetFrame.java               long-press edit takeover and drag wrapper for AppWidgets
  WallpaperView.java             wallpaper sources (device/custom/aurora), grain, blur, scrim
  DockView.java                  clear-glass dock (BitmapShader, built-in shades, specular, noise)
  GlassPanel.java                shared clear/frosted glass card (backdrop sample + built-in recipe)
  Motion.java                    shared springy scale/pop helpers for cards and dialogs
  DrawerScrimView.java           full-screen dim with a rounded cut-out behind the drawer card
  MagicLampView.java             macOS genie mesh warp for drawers, menus and settings submenus
  ScreenCaptureBackdrop.java     fast raw screencap -> quarter-res blur for the floating card
  GlassSheen.java                legacy lighting-only pass (no longer used by the drawer)
  LiquidGlass.java               Apple-style refracting rim + specular + inner shadow pass
  Backdrop.java                  live blurred screen snapshot sampled by the glass surfaces
  Menu.java                      anchored popup menu
  Weather.java                   Open-Meteo + device/IP location, WMO condition mapping
  Apps.java                      launcher app query/dedupe, icons, folder previews, app-info
  SysStats.java                  /proc/stat CPU load + TrafficStats throughput
  AuroraA11yService.java         optional global-action helper (notification drawer)
  Blur.java                      box blur
  IconView.java                  icon rendering helper (legacy drawer cell)
  BatteryGlyph.java              battery pill glyph
  HeaderStatusGlyph.java         Bluetooth and notification-sound header glyphs
  FanActivity.java               built-in fan presets, curve editor and live rear fan model
  FanProfile.java                fan-only profile encoding for global/per-app contexts
  FanProfileStore.java           persisted per-app fan overrides
  PerAppFanService.java          foreground watcher applying per-app/global fan profiles
  FanCurve.java                  fan curve parsing and temperature interpolation
  FanCurveView.java              draggable temperature-to-duty graph
  FanBackView.java               animated rear chassis, fan blades and airflow
  ThermalBoardView.java          CPU/RAM mainboard outline for the tuner thermal card
  FanCurveService.java           foreground service applying the curve from peak temperature
  RgbActivity.java               built-in RGB Lighting colour wheel, live joystick previews, modes
  RgbControl.java                SN3112 joystick LED sysfs transport (PServer/direct)
  RgbService.java                foreground service for animated and screen-follow modes
  ColorWheelView.java            touch HSV colour wheel with saturation radius
  JoystickPreviewView.java       outlined joystick with live axes and RGB ring
  ScreenAmbient.java             PServer raw-screencap dominant-colour sampler
  ControllerMapActivity.java     controller remapping / turbo / combination editor
  OdinOutlineView.java           live Odin face/control outline with pressed-state glow
  ControllerMapConfig.java       remap profile and Odin evdev button catalogue
  ControllerRemapManager.java    selects/starts root or rootless remapping
  RootlessRemap.java             accessibility key filter + Java mapping/turbo/combo engine
  RootlessUinput.java            app-UID /system/bin/uinput virtual controller helper
  ../native/aurora-remap.c       evdev grab + uinput virtual-controller daemon
  ColorWheel.java                HSV/RGB colour wheel used by the clock/date picker
  WeatherGlyph.java              self-drawn weather condition icon
  Prefs.java                     persisted settings
  Ui.java                        dp/rounded-drawable helpers
```

## Roadmap

The core launcher is feature-complete. Possible future work:

1. **Widget reconfiguration** — re-open a provider's configure activity for an existing widget
   (currently configuration runs when the widget is first added).
2. **Icon-pack fallbacks** — support `iconback`/`iconmask` for apps missing from a pack's
   `appfilter.xml` (mapped icons already work).
3. **Quickstep integration** — the private-API route for the genuine Pixel close animation
   (see the table below).

## Known limitations

- The bundled device wallpaper does not follow *later* wallpaper changes; use
  **Settings → Choose image…** or Aurora to change the backdrop.
- Launchable-app support, app/web shortcuts, drag-and-drop, AppWidgets, icon packs and custom
  icons are implemented.
- Settings and the desktop layout persist in `SharedPreferences("aurora")`; **Reset style**
  returns the appearance to defaults but keeps your layout and per-app customisations.
- **App open/close and swipe-up-to-home motion:** the opening animation uses the platform
  scale-up transition from the tapped icon. The closing / go-home animation is owned by the
  system Shell, not by third-party launchers (Android exposes only a gesture contract), so
  Aurora settles the desktop back in when it resumes without dimming or a flash. On this
  device the system transition is already fairly close to Pixel's.
- **Getting the real Pixel shrink-to-icon close animation:** this is a Quickstep/Shell
  feature, not a normal launcher API. A launcher only gets it when it is the system's
  **recents/Quickstep provider** and implements Launcher3's `RemoteAnimationRunner` /
  `GestureNavContract` interfaces. That is why only Launcher3-derived launchers (e.g.
  Lawnchair) can use the **QuickSwitch** Magisk/KernelSU module to enable it. Systemizing
  Aurora with a module does **not** by itself grant the animation; Aurora would need a
  Quickstep integration layer (large, private-API, Android-version-specific). See the
  "Pixel close animation" note below.

### Pixel close animation — root / module options

| Option | Gets the real Pixel close animation? |
| --- | --- |
| Aurora as-is | No — open animation only (platform scale-up) |
| Magisk/KernelSU module that systemizes Aurora | No — the Quickstep contract is still missing |
| **QuickSwitch** + a Launcher3-based launcher (Lawnchair) | **Yes** — the launcher becomes the system Quickstep/recents provider, so the Shell animates apps back into their icons |
| Port Aurora onto Launcher3's Quickstep interfaces + systemize it + set it as recents provider | Yes in principle, but it is a large private-API port that must be redone per Android release |

The practical rooted route is therefore **Lawnchair + QuickSwitch** (or another
Launcher3-based Quickstep launcher). QuickSwitch installs as a Magisk/KernelSU/APatch module
and points the system's recents provider at the launcher; Aurora cannot be selected there
because it does not implement the Quickstep provider.

- Weather needs internet. Location comes from the device (if the permission is granted) or from
  IP geolocation, which is city-level and can be wrong behind a VPN. If all providers fail the
  last cached reading stays on screen.
- The clock/date/weather block is a built-in **grid widget** (cell-snapped, resizable and
  removable) that sits alongside real AppWidgets. Third-party widgets still need the widget
  provider's own service to be running if it requires one.
- Device-specific: relies on `sensorLandscape` and the Odin 3's 1920×1080 panel. It runs on
  other Android 12+ devices but the layout is tuned for this screen.

## Tested on

AYN Odin 3 · Android 15 (SDK 35) · build `Odin3_V1.0.0.187_20260616_193307_user` ·
Snapdragon 8 Elite / Adreno 830 · 1920×1080 landscape.

## Licence

Aurora is released under the **Apache License 2.0** — see [LICENSE](LICENSE).

Part of the liquid-glass shader is adapted from
[Kyant0/backdrop](https://github.com/Kyant0/backdrop) (Apache-2.0); see
[NOTICE](NOTICE) for the attribution.

## Credits

- [Kyant0/backdrop](https://github.com/Kyant0/backdrop) for the AGSL liquid-glass lens.
- AYN for the Odin 3 hardware documentation used while tuning the panel.
