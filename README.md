<div align="center">

# Laply

**Formula 1 live timing, schedule and standings for Android, in your status bar, on your lock screen and on your home screen.**

[![Android 12+](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)](#install)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-UI-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![Material 3 Expressive](https://img.shields.io/badge/Material%203-Expressive-6750A4?logo=materialdesign&logoColor=white)](https://m3.material.io)
[![Latest release](https://img.shields.io/github/v/release/Spottq/Laply?label=download&color=E10600)](https://github.com/Spottq/Laply/releases/latest)

<img src="docs/screenshots/phone_live.png" width="240" alt="Live tab: podium, next round and classification" />
<img src="docs/screenshots/phone_schedule.png" width="240" alt="Season schedule" />
<img src="docs/screenshots/phone_standings.png" width="240" alt="Championship standings" />

</div>

## Features

- **Live timing**: the official F1 live timing feed (SignalR), with an automatic **ESPN fallback** when that feed is blocked or down, so the classification keeps updating.
- **Automatic Live Update**: follow the season once and Laply opens an ongoing Live Update when each session starts, with the leader, the lap count and the track status in a status-bar chip.
- **MetricStyle on Android 17**: the gaps between the leaders appear as large metrics in the notification. Android 16 shows a segmented progress bar and Android 12 to 15 show a regular ongoing notification.
- **Home-screen widgets**: countdown to the next session and the weekend ahead. Resize the widget from 2x1 up to full screen and it adds more detail as it grows. It uses Material 3 / Monet colours on Pixel and translucent **One UI glass** on Samsung.
- **Samsung lock-screen widgets**: next session and countdown on the lock screen, AOD and the Flip/Fold cover screen, plus a 2x2 layout on tablets and foldables.
- **Tablets and foldables**: two-pane Live layout with the circuit map and conditions next to the classification.
- **Schedule, results and standings**: the full season calendar in your time zone, session results and driver and team championships.
- **Modern Android**: Material 3 Expressive, dynamic colour (Monet) or F1 red, light/dark/system theme, predictive back and edge-to-edge.
- **Update checker**: a daily check against GitHub Releases posts a notification when a new version is out. You can turn it off, or check by hand, in Settings → About.

## Screenshots

### Live Update · Android 17 MetricStyle

<img src="docs/screenshots/live_update_metric.png" width="540" alt="Live Update notification with MetricStyle gaps and a status-bar chip" />

### Widgets

| Material 3 · Pixel | One UI glass · Samsung |
|---|---|
| <img src="docs/screenshots/widget_4x2_pixel.png" width="400" alt="4x2 widget, Material 3" /> | <img src="docs/screenshots/widget_oneui_glass_4x2.png" width="400" alt="4x2 widget, One UI glass" /> |
| <img src="docs/screenshots/widget_wide_pixel.png" width="400" alt="4x1 widget, Material 3" /> | <img src="docs/screenshots/widget_oneui_glass_2x1.png" width="400" alt="4x1 widget, One UI glass" /> |

| 2x1 · One UI | Lock screen · One UI |
|---|---|
| <img src="docs/screenshots/widget_2x1_light.png" width="260" alt="2x1 widget" /> | <img src="docs/screenshots/lock_widget.png" width="300" alt="Samsung lock-screen widget" /> |

### Tablets and foldables

<img src="docs/screenshots/tablet_live.png" width="640" alt="Tablet two-pane Live layout" />

<img src="docs/screenshots/foldable_two_pane.png" width="420" alt="Galaxy Z Fold inner display, two-pane Live layout" />

### Settings

<img src="docs/screenshots/phone_settings.png" width="240" alt="Settings" />

## Install

1. Download `Laply-x.y.apk` from the [latest release](https://github.com/Spottq/Laply/releases/latest).
2. Open it on your phone and allow installs from your browser or file manager when Android asks.
3. Allow notifications when you tap **Follow** so Live Updates can appear.

Requires Android 12 (API 31) or newer. MetricStyle needs Android 17 and the promoted Live Update chip needs Android 16. Laply checks for new releases once a day and notifies you when one is out.

## Build

You need JDK 21 and the Android SDK (platform 37).

```sh
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease      # signed with the debug key, for sideloading
```

Requires JDK 21 (Android Studio's bundled JBR works): set `JAVA_HOME`, or `org.gradle.java.home` in your user-level `~/.gradle/gradle.properties`.

## Data sources

| Data | Source |
|---|---|
| Live timing | F1 live timing, SignalR Core (`livetiming.formula1.com`), unauthenticated topics only |
| Fallback live timing | ESPN's public scoreboard / core APIs |
| Schedule, results, standings | [Jolpica](https://github.com/jolpica/jolpica-f1) (Ergast-compatible API) |
| Driver photos, team logos, circuit maps | `media.formula1.com` |
| Circuit maps (fallback) | Wikipedia / Wikimedia Commons |
| Flags | [flagcdn.com](https://flagcdn.com) |

The live feed is unofficial and undocumented. It can change or close without notice.

## Credits

The Samsung One UI widget integration builds on these MIT-licensed projects:

- [twidget](https://github.com/thatjoshguy67/twidget), © Josh Skinner: lock-screen widget provider, One UI metadata and long-press settings.
- [blur-widget-demo](https://github.com/thatjoshguy67/blur-widget-demo), © Josh Skinner: One UI Home background blur and cell-size info.
- Codex-Meter, © its authors: Samsung lock-screen widget support and the ServiceBox receiver.

## License

TBD. No license has been chosen yet, so all rights are reserved by the author for now.

## Disclaimer

Laply is an unofficial fan project and is not associated with Formula 1 companies. F1, FORMULA ONE and related marks are trademarks of Formula One Licensing B.V.
