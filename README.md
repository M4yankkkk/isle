# Isle

A native camera island for Nothing Phone 2a, with Flutter settings. First app build:
**0.1.0**. Kotlin owns the overlay, animations, settings storage, and system feeds;
Flutter runs only the settings activity.

## Included

- Device-calibrated **110 × 34 dp** pill, centered at vertical offset 0.
- Independent 30 dp transparent touch extension below the pill. Android retains
  status-bar and notification-shade touch handling.
- Spring expansion from the pill to a panel **below the status bar**, staggered
  content entrance, interrupted transitions, swipe-up/Close/outside-tap dismissal,
  configurable timeout, and reduced-motion mode.
- Live battery level and a brief charging arrival when power connects.
- Media session selection (playing first), title/artist/artwork, and supported
  previous/play/pause/next actions. Requires notification access.
- Optional notification peeks; off by default. App muting, duplicate filtering,
  expiry, removal handling, and opening the original notification intent. Android
  can redact sensitive content. Text stays in memory, not in logs or storage.
- Native persistent settings, calibrated size/position controls, always-visible or
  hidden idle mode, haptics, calibration guides, app pickers, and diagnostics.
- Foreground notification with Pause/Resume and Stop. Pause survives service
  recreation. No boot receiver or accessibility service.
- Landscape, screen-off, and lock-screen suppression. Optional app hide list using
  usage access (one-second checks only while screen is available and unpaused).

## Install on Phone 2a

```sh
flutter pub get
flutter build apk --release --target-platform android-arm64
adb install -r build/app/outputs/flutter-apk/app-release.apk
```

The local release build uses the existing debug signing key so it upgrades the
prototype. Configure a private release key before distributing publicly.

1. Open **Isle** and grant **Display over other apps** if needed.
2. Tap **Turn on Isle**, then tap just below the camera.
3. In **Access**, enable media & notification access for music controls. Notification
   peeks have a separate opt-in switch on the Island tab.
4. Enable the control notification for quick Pause/Stop access.
5. Appearance changes save automatically. The fitted defaults are already applied.

The emulator / physical-device checklist is in [docs/DEVICE_TEST.md](docs/DEVICE_TEST.md).

## Behavior and limits

The black pill remains over the camera; the expanded content rests below the
status bar. The window grows before expansion and shrinks after collapse; the
window manager is not called on every animation frame. Idle animations do not run.
The transparent touch extension intercepts the small rectangle beneath the pill.

User-opened content takes priority over incoming charging/notification peeks.
Suppression discards peeks; they do not replay on unlock or leaving a hidden app.
Media changes update the active session; destroyed sessions are removed.

App hiding is **best effort**, not a payment-screen guarantee. Usage queries can be
late, and multi-window scenarios can be ambiguous. Pause Isle for payment flows.
Automatic fullscreen/game detection is not included: add those apps to the hide
list or pause. Nothing OS may still stop the service; battery optimization settings
are available in Access. Force-stop and reboot require opening Isle again.

This is the first app iteration, not the entire long-term spec. Timers, calls,
navigation/delivery parsing, Glyph sync, and app-limiter integration remain deferred.

## Layout

- `lib/main.dart`: settings, onboarding, calibration, app pickers.
- `IslandSettings.kt`: validated native preference schema and storage.
- `IslandState.kt`: presentation and suppression transitions.
- `IslandService.kt`: window/lifecycle management, gestures, battery, visibility.
- `IslandOverlay.kt`: Compose rendering and interrupted spring animation.
- `IslandListener.kt`: notification and media session callbacks.
- `MainActivity.kt`: Flutter/native bridge and Android settings routes.

## Verification

```sh
flutter analyze
flutter test
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

Emulator-only integration runner (it changes synthetic battery state and app settings):

```sh
adb -s emulator-5554 install -r build/app/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell appops set dev.isle.isle SYSTEM_ALERT_WINDOW allow
adb -s emulator-5554 shell pm grant dev.isle.isle android.permission.POST_NOTIFICATIONS
adb -s emulator-5554 shell cmd notification allow_listener dev.isle.isle/dev.isle.isle.IslandListener
adb -s emulator-5554 shell am instrument -w dev.isle.isle.test/dev.isle.isle.IsleSmokeRunner
```

Run these adb commands from the project root (after leaving `android`).
The integration harness is in the test APK only, not in the app APK.

Platform references:
[overlay window restrictions](https://developer.android.com/reference/android/view/WindowManager.LayoutParams),
[media session access](https://developer.android.com/reference/android/media/session/MediaSessionManager),
[background activity starts](https://developer.android.com/guide/components/activities/secure-bal).
