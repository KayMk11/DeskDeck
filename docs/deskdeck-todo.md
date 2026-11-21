# DeskDeck to-do

Work on the `productivity` branch. Upstream DroidDeck is the `upstream` remote.

## Setting up a new dev machine

1. Clone your DeskDeck repo, check out `productivity`, then run `git submodule update --init external/MaliBridge`.
2. Install Java 17 and the Android SDK. Gradle installs the platform, build tools and NDK it needs on the first run.
3. Build:
   ```bash
   python3 tools/deskdeck/use_ci_runtime.py
   ./gradlew -PskipRust=true -x verifyRuntimeInputs :app:assembleRelease
   ```
   The script fills in the prebuilt runtime inputs from upstream's CI build of the merge base, so Docker, Go, MinGW and `gh` aren't needed.
   After rebasing onto newer upstream, rerun it; it needs a `main` commit that upstream CI has built.
4. Install on the tablet: `adb install --user 0 -r app/build/outputs/apk/release/app-release.apk`. Samsung's Secure Folder blocks an install for all users.
5. Run the unit tests once. They weren't run on commit `9c3b09c0f`. The full suite needs a lot of memory.

## Next up

### A list-style launcher for keyboard and trackpad
Replace the grid with (or add) a vertical, Niagara-style list, made to be driven by keyboard and trackpad.
- Type to filter straight away, use the arrow keys to move, and Enter to open.
- A right-click on the trackpad or a mouse opens the same menu as a long press.
  Today `TileFrame` only handles touch long-press; add a check for `MotionEvent.BUTTON_SECONDARY` / `isFromSource(SOURCE_MOUSE)`.
- Keep the source badges, and keep favourites at the top.
- Files: `ui/AndroidAppsPage.kt` (the `AppEntry` model, `AppTile`, `AppMenu`), plus the `AppsGrid` settings.

### Back and the session menu
- **Back** shows a confirmation ("Quit <app>?" or "Log out of the desktop?") instead of opening the session menu.
- **A keyboard shortcut** opens the session menu. Pick one that Plasma and Linux apps don't already use, or make it configurable.
  The Meta key is forwarded to the session when "System keys" is on.
- Files: `SessionActivity.kt` (back handling and drawer opening), and `ui/SessionOverlay.kt`.

### Native resolution for Linux apps opened on their own
They still render at 720p and are letterboxed. Changing Plasma's own display setting doesn't affect them, because they run under gamescope, whose output size comes from DeskDeck's session resolution choice.
That fixed default is left over from upstream's emulator use, where a fixed resolution matters.
- A desktop app session (`SessionState.desktopApp`) should default to the window or screen size: `SessionDisplay.MATCH_SCREEN` or "follow", in `SessionActivity.resolutionChoice()`.
- Set a UI scale so text stays readable at 2560×1600, for example `QT_SCALE_FACTOR` / `GDK_SCALE` taken from the density, set in `droiddeck-session`'s `run)` branch when `DD_DESKTOP_APP=1`.

### Matching theme for apps opened on their own
They use a light theme because no Plasma session supplies the user's settings.
Export the Plasma look (`QT_QPA_PLATFORMTHEME=kde`, `XDG_CURRENT_DESKTOP=KDE`, the kdeglobals path) in `droiddeck-session`'s run branch for desktop apps.

### Getting back to the Apps grid from a session
There's no direct way back while a session runs. Add an "Apps" action to the session menu or the new shortcut, or rely on DeskDeck being the default launcher so Android's Home button opens the grid.

## Trimming the app
- **Remove the Desktop page**, including the emulators, dock, files and mode sheet.
  The Desktop quick-access button opens Plasma on a tap. A long press opens the desktop config menu (resolution, performance, drivers) with "Uninstall desktop" inside it.
  Files: `ui/DesktopPage.kt` (reuse its `modeSheet` rows and `UninstallDesktop`), and the `desktop` entry in `ui/NavTabs.kt`.
- **Remove the Updates page and the self-updater.** It's a private fork, so any update is your own build.
  Upstream still publishes the runtime and desktop packages, so keep a "check for runtime/desktop updates" row in Settings.
  Files: `update/AppUpdates.kt`, `update/UpdateWatch.kt`, `ui/UpdatesPage.kt`, and the `updates` tab.
- **Settings links to Android settings** where Android already owns the setting: Wi-Fi, Bluetooth, display, sound, keyboard and mouse/trackpad, default home app, developer options.
  Use the `Settings.ACTION_*` intents.

## Direction: a home screen layered on Android
DeskDeck should become a layer on top of Android, not a separate world. Android, DeX and freeform windows already handle keyboard, mouse, window management and multitasking; DeskDeck follows their lead rather than reimplementing them.

### A Zune / Metro-style home screen
- **Widgets in a Niagara-style column** along the top or side. A tablet has room for it.
  Reuse `HomeWidgets.kt`, which already hosts Android app widgets.
- **Large typographic hubs:** **Apps** (the app list), **Settings** (DeskDeck's settings), and user-defined **collections**.
- **Collections** are curated groups such as *Music* (Elisa, Spotify), *Media* (Gallery, Jellyfin, Netflix) or *Productivity*, mixing Android and Linux apps.
  Build them on `HomeFolders.kt`: folders already store app keys, Linux ones included.
- **Wallpaper:** show the system wallpaper behind the home screen (`windowShowWallpaper`), with a way to set it.
  The `wallpaper/` package already exists.
- **Keyboard and trackpad first:** type to search from anywhere, arrow keys across hubs, and right-click for context menus.

### Per-app Linux windows (research)
Each Linux window as its own Android/DeX window, so Linux apps sit in the taskbar and Alt+Tab next to Android ones.
- Today `cpp/waylandcomp` composites every guest surface into a single Android surface.
  The idea is to map each `xdg_toplevel` to its own Android Activity/task: a freeform window with its own Surface, a title from the toplevel, and close or resize sent back as Wayland configure events.
- Things to check:
  - Input focus and IME per window.
  - Popups and menus (`xdg_popup` positioned relative to the parent window).
  - Xwayland apps (would need a rootless X server or per-window mapping).
  - Clipboard.
  - How many Android tasks DeX tolerates.
- Prior art: Termux:X11 (one window, for comparison), how Winlator handles windows, and Android's own `AndroidX Window` freeform APIs.
- The KWin-only session (Phase 4b) is a stepping stone: one KWin, several apps, before splitting windows out.

## Later
- **Phase 2: remove the non-Steam stores.** That's about 75k lines of Kotlin, the Rust store crate, WebRTC and the MinGW launchers. The removal order is in the original plan.
- **Phase 4b: a KWin-only "apps" session.** It would handle dialogs and multiple windows, put several apps in one session, and be the base for per-app Android windows (DeX-style).
- **"Add to Android" in Plasma's right-click menu**, as Steam has. Optional, especially if it means rebuilding the desktop package.
- **Remove Steam:** have `droiddeck-proton-run` get Proton without the Steam layout or the `proton-arm64` seed.
- **Tidy the Settings overview:** move or hide the "Steam integration" group.
- **Launch speed:** opening a standalone app takes about 3 s. Profile `droiddeck-session` (fonts, `wayland-info` polling).
- **Translations:** the strings added in this fork are English-only.

## Done (2026-11-22)
- Installs as DeskDeck alongside vanilla DroidDeck. Desktop-first tabs, and Steam is reached from the desktop.
- Logging out of Plasma returns to Android ("Return to Android"); there's no Big Picture redirect.
- Linux apps appear on the grid with Android/Linux badges. They open inside Plasma when it's running, or on their own otherwise.
- Flathub has its own tab, and "Add a program" is under Settings › Advanced.
- System utilities start hidden; there are Android/Linux filters and a "choose apps" mode for hiding and showing apps.
- Linux apps opened on their own have no gamepad overlay, use the desktop input profile, and launch faster.
- Direct touch on the screen and a normal trackpad at the same time, with two-finger trackpad scrolling (confirmed on a Tab S9).
