<h1 align="center">Aurora</h1>

<p align="center">
  <b>A landscape-first Android launcher for the AYN Odin 3</b><br>
  Pure platform · no AndroidX · no Gradle
</p>

<p align="center">
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache%202.0-blue.svg"></a>
  <img alt="Android 12+" src="https://img.shields.io/badge/Android-12%2B-3ddc84?logo=android&logoColor=white">
  <img alt="AYN Odin 3" src="https://img.shields.io/badge/AYN-Odin%203-c8102e">
</p>

<p align="center">
  <a href="docs/home.png"><img src="docs/home.png" width="32%" alt="Home"></a>
  <a href="docs/fan-control.png"><img src="docs/fan-control.png" width="32%" alt="Fan Control"></a>
  <a href="docs/performance-tuner.png"><img src="docs/performance-tuner.png" width="32%" alt="Performance Tuner"></a>
</p>
<p align="center">
  <a href="docs/stress-test.png"><img src="docs/stress-test.png" width="49%" alt="Stress Test"></a>
  <a href="docs/performance-log.png"><img src="docs/performance-log.png" width="49%" alt="Performance Log"></a>
</p>
<p align="center"><sub>Home · Fan Control · Performance Tuner · Stress Test · Performance Log</sub></p>

Aurora replaces the stock launcher with a console-style home: a large clock, a paged desktop,
a clear Liquid Glass dock and matching app drawers. It is built directly against the Android
platform (`Activity`, `View`, `Canvas`, `RenderEffect`) with `aapt2` → `javac` → `d8`.

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

## Build

| Requirement | Version |
| --- | --- |
| JDK | 21 (Eclipse Temurin) |
| Android SDK | platform 35, build-tools 35.0.0 |
| Android NDK | r27c (for the native remapper/touchmap helpers) |

The default toolchain lives under `$HOME/.local/opt`; no system packages are required.
Override with `ANDROID_SDK_ROOT`, `ANDROID_NDK_ROOT` and `JAVA_HOME`.

```bash
cd aurora
./build.sh          # -> Aurora.apk
```

`build.sh` runs `aapt2 compile/link` → `javac` → `d8` → `zipalign` → `apksigner`.

Release signing uses a local keystore that is **never committed**:

```bash
AURORA_KEYSTORE=/path/to/release.jks \
AURORA_KEYSTORE_PASS=... \
AURORA_KEY_PASS=... \
./build.sh
```

Without those variables it falls back to `keystore.jks` (created on first use).

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

Either accept the prompt shown the first time you swipe down on the home, or:

```bash
adb shell settings put secure enabled_accessibility_services \
    com.ben.aurora/com.ben.aurora.AuroraA11yService
adb shell settings put secure accessibility_enabled 1
```

Disable it again in **Settings → Accessibility → Aurora**.

## Configuration

### Wallpaper

On the Odin 3 the stock wallpaper is the framework default particle image
(`framework-res` → `drawable-nodpi/default_wallpaper.png`, 1920×1080). The system never writes
it to `/data/system/users/0/wallpaper`, so a normal app calling `WallpaperManager.getDrawable()`
only receives an Android-14+ *colour-extracted gradient* fallback — not the real wallpaper.
Aurora ships a copy (`res/drawable-nodpi/device_wallpaper.png`, 284 KB) and uses it as the
default backdrop so the home screen matches the lock screen.

Change it at any time with **Settings → Change wallpaper**: choose a still, a video from
`Movies/Aurora Wallpapers`, an installed live wallpaper, or one of the recent history tiles.
Custom images and video frames also set the Android wallpaper.

### Weather

The clock widget shows the current temperature and WMO condition. Data comes from
[Open-Meteo](https://open-meteo.com/) (free, no API key). Location resolution:

1. If **Use device location** is enabled and coarse-location permission is granted, Aurora asks
   the network provider for a fix (last-known first, then a single current-location request).
2. Otherwise it resolves an approximate location from the IP (`ipapi.co`, falling back to
   `ipwho.is`).

The result is cached for 30 minutes and refreshed on resume, every 30 minutes, by tapping the
weather line, or via **Settings → Refresh weather now**. Only the coordinates are sent to the
weather service; if all providers fail the cached reading stays on screen.

## Roadmap

The core launcher is feature-complete. Possible future work:

1. **Widget reconfiguration** — re-open a provider's configure activity for an existing widget
   (currently configuration runs when the widget is first added).
2. **Icon-pack fallbacks** — support `iconback`/`iconmask` for apps missing from a pack's
   `appfilter.xml` (mapped icons already work).
3. **Quickstep integration** — the private-API route for the genuine Pixel close animation.

## Known limitations

### Animation

The opening animation uses the platform scale-up transition from the tapped icon. The closing /
go-home animation is owned by the system Shell, not by third-party launchers (Android exposes
only a gesture contract), so Aurora settles the desktop back in when it resumes without dimming
or a flash.

### Pixel close animation

This is a Quickstep/Shell feature, not a normal launcher API. A launcher only gets it when it is
the system's **recents/Quickstep provider** and implements Launcher3's `RemoteAnimationRunner` /
`GestureNavContract` interfaces. That is why only Launcher3-derived launchers can use the
**QuickSwitch** Magisk/KernelSU module to enable it.

| Option | Gets the real Pixel close animation? |
| --- | --- |
| Aurora as-is | No — open animation only (platform scale-up) |
| Magisk/KernelSU module that systemizes Aurora | No — the Quickstep contract is still missing |
| **QuickSwitch** + a Launcher3-based launcher (Lawnchair) | **Yes** — the launcher becomes the system Quickstep/recents provider |
| Port Aurora onto Launcher3's Quickstep interfaces + systemize + set as recents provider | Yes in principle, but a large private-API port that must be redone per Android release |

The practical rooted route is **Lawnchair + QuickSwitch** (or another Launcher3-based launcher).
Aurora cannot be selected there because it does not implement the Quickstep provider.

### Other

- The bundled device wallpaper does not follow later wallpaper changes; use
  **Settings → Change wallpaper** instead.
- Settings and layout persist in `SharedPreferences("aurora")`; **Reset style** returns the
  appearance to defaults but keeps your layout and per-app customisations.
- Weather needs internet. Device location or IP geolocation is used; the latter is city-level
  and can be wrong behind a VPN.
- The clock/date/weather block is a built-in grid widget. Third-party widgets still need their
  provider's service if it requires one.
- Device-specific: relies on `sensorLandscape` and the Odin 3's 1920×1080 panel. It runs on
  other Android 12+ devices, but the layout is tuned for this screen.

## Tested on

| | |
| --- | --- |
| Device | AYN Odin 3 (`NX789J`) |
| OS | Android 15 (SDK 35) |
| Build | `Odin3_V1.0.0.187_20260616_193307_user` |
| SoC | Snapdragon 8 Elite / Adreno 830 |
| Display | 1920×1080 landscape, 60/120 Hz, HDR10/HLG |

## Licence

Aurora is released under the **Apache License 2.0** — see [LICENSE](LICENSE).

Part of the liquid-glass shader is adapted from
[Kyant0/backdrop](https://github.com/Kyant0/backdrop) (Apache-2.0); see [NOTICE](NOTICE) for
the attribution.

## Credits

- [Kyant0/backdrop](https://github.com/Kyant0/backdrop) for the AGSL liquid-glass lens.
- AYN for the Odin 3 hardware documentation used while tuning the panel.
