# Dynamic Island for Nothing Phone 2a

A design and implementation guide for building an iPhone-style "Dynamic Island" around the Nothing Phone 2a's centered punch-hole camera. The island stays hidden by default and expands when you tap the camera area. It can also be made always visible if you prefer that.

**Decisions made so far**
- Architecture: **hybrid**. Kotlin for the island and everything system-facing, Flutter for the settings app.
- Priority: **good animations** (spring physics, morphing shapes, staggered content).
- Skip the Accessibility Service route, because it breaks GPay and other payment apps.

---

## Table of Contents

1. Overview
2. Architecture: Hybrid (Kotlin + Flutter)
3. How It Works
4. When the Window Grows (State Machine)
5. Animation Guide
6. What the Island Can Do (Data Sources)
7. Kotlin vs Flutter: The Difference
8. Gotchas
9. GPay and Accessibility Follow-up
10. Suggested Build Order
11. References

---

## 1. Overview

The Nothing Phone 2a has a centered punch-hole camera, which makes it a good fit for this idea. Android has no built-in "island" API, so everything is assembled from a few system pieces:

1. An **overlay window** that draws the island on top of other apps.
2. A **small invisible touch zone** over the camera cutout that reveals the island on tap.
3. **Data sources** (notifications, media sessions, battery, timers, and so on) that feed the island with useful content.

---

## 2. Architecture: Hybrid (Kotlin + Flutter)

The island is always running, so its smoothness and battery use matter most. That favors native code. A Flutter engine sitting in the background all day for a tiny pill is not worth the RAM.

```
┌──────────────────────────┐        MethodChannel / SharedPreferences
│  Flutter app (settings)  │ ─────────────────────────────────────────┐
│  - onboarding            │                                          │
│  - permission screens    │                                          ▼
│  - feature toggles       │                          ┌────────────────────────────┐
│  - hide-list editor      │                          │ Kotlin foreground service  │
│  - theme / size options  │                          │  - overlay window (island) │
└──────────────────────────┘                          │  - camera touch zone       │
                                                      │  - listeners + data feeds  │
                                                      │  - Compose animations      │
                                                      └────────────────────────────┘
```

**Kotlin owns:**
- Foreground service
- Overlay window and the invisible camera touch zone
- Cutout detection
- All animations (Jetpack Compose inside the overlay)
- `NotificationListenerService`, `MediaSession`, battery, telephony, `UsageStatsManager`

**Flutter owns:**
- Onboarding and permission flow (overlay, notification access, usage access, battery exemption)
- Toggles for each feature
- The payment-app hide list
- Appearance settings (always visible vs hidden, size, animation style)
- Settings are sent to the service over a `MethodChannel`, or stored in `SharedPreferences` that the service reads.

Estimated size: the Kotlin side is roughly 500 to 800 lines for a solid first version.

---

## 3. How It Works

### 3.1 The island itself: an overlay window

- Use `WindowManager` with `TYPE_APPLICATION_OVERLAY`.
- This requires the **"Display over other apps"** permission (`SYSTEM_ALERT_WINDOW`).
- Run it from a **foreground service** so Nothing OS does not kill it. Ask the user to **disable battery optimization** for the app as well.
- Add the flags `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS` so the window can draw up into the status bar area around the camera.
- Find the exact camera position with `DisplayCutout.getBoundingRects()`, read from a view's `rootWindowInsets` after the view is attached. Do not hardcode pixel values.

```kotlin
val lp = WindowManager.LayoutParams(
    widthPx, heightPx,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT
).apply {
    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    y = cutoutTopPx
}
windowManager.addView(islandView, lp)
```

### 3.2 "Touch the camera to reveal it"

You cannot detect touches on the system UI directly. The workaround:

- Place a **small, fully transparent overlay window** (about 70x40dp) exactly over the camera cutout. In the idle state it is invisible and only listens for taps.
- **Tap:** expand the island with a spring animation.
- **Auto-collapse** after 3 to 5 seconds, or when the user taps outside.
- **Long-press or swipe-down** can open a larger panel.
- If you decide you want it always visible, draw a black pill in the same window instead of leaving it transparent.

**Tradeoff:** this tiny window swallows touches in that spot. That is harmless, because it is only the status bar area.

### 3.3 Alternative: Accessibility Service (NOT recommended for this project)

`TYPE_ACCESSIBILITY_OVERLAY` draws above almost everything (including the lock screen in some cases) and survives better than a normal overlay. It also lets you detect which app is in the foreground. However:

- Play Store policy on accessibility services is strict.
- **Payment and banking apps detect enabled accessibility services and may refuse to work** (see section 9).

---

## 4. When the Window Grows (State Machine)

The window size is decided in code. It stays small until something triggers a change.

| State | Size | Trigger |
|---|---|---|
| **Idle (hidden)** | Tiny transparent zone, about 70x40dp, over the camera | Default |
| **Compact** | Small pill, about 120 to 180dp wide | Event: charging starts, timer running, music playing, new notification |
| **Expanded** | Wide card, roughly 340x120dp or more | Tap on the camera zone, or tap on the compact pill |
| **Back to idle** | Shrinks again | 3 to 5 seconds of no interaction, tap outside, or entering a payment app, fullscreen video or a game |

Events like an incoming call or a charger being plugged in can auto-expand the island briefly. Tapping the camera expands it manually.

### How to resize the window without stutter

Calling `updateViewLayout()` on every animation frame can stutter. Two better approaches:

**Approach 1: `wrap_content` window, animate the content inside**
Make the window `WRAP_CONTENT` and animate the view inside it with a spring. The window follows the content size, and everything outside the pill stays touchable for the app underneath.

**Approach 2 (more robust): resize at the edges of the animation**
1. **Expanding:** first resize the window to the final expanded size instantly (it is transparent, so nothing visible changes), then animate the pill inside it.
2. **Collapsing:** animate the pill down first, then shrink the window when the animation ends.

The window only changes size twice per transition, and the smooth motion is all inside the content, which is cheap.

---

## 5. Animation Guide

Good animation is the main goal, so use **Jetpack Compose inside the overlay** for the island UI. The Nothing Phone 2a has a 120Hz display, so smooth motion is noticeable.

### 5.1 Core principles

- **Springs, not tweens.** Springs feel physical and handle interruption (tapping mid-animation) gracefully. Use a slightly bouncy damping for the expand, and a stiffer one for the collapse.
- **Morph from the camera.** The pill should start at the exact cutout size and position, so it looks like it grows out of the camera.
- **Stagger the content.** The container expands first, then the content fades and scales in about 80 to 120 ms later. On collapse, content fades out first, then the container shrinks.
- **Animate shape, not just size.** Animate the corner radius together with width and height so the pill stays properly rounded at every size.
- **Be interruptible.** Use `Animatable` so a new tap or event can redirect the animation from its current value instead of snapping.

### 5.2 Container morph (width, height, corner radius)

```kotlin
@Composable
fun Island(state: IslandState) {
    val target = when (state) {
        IslandState.Idle -> IslandSize(cutoutW, cutoutH, cutoutH / 2)
        IslandState.Compact -> IslandSize(160.dp, 36.dp, 18.dp)
        IslandState.Expanded -> IslandSize(340.dp, 120.dp, 32.dp)
    }

    val spring = spring<Dp>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
    val width by animateDpAsState(target.width, spring)
    val height by animateDpAsState(target.height, spring)
    val radius by animateDpAsState(target.radius, spring)

    Box(
        modifier = Modifier
            .size(width, height)
            .clip(RoundedCornerShape(radius))
            .background(Color.Black)
    ) {
        IslandContent(state)
    }
}
```

### 5.3 Staggered content

```kotlin
@Composable
fun IslandContent(state: IslandState) {
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            (fadeIn(tween(180, delayMillis = 90)) +
                scaleIn(initialScale = 0.85f, animationSpec = tween(220, delayMillis = 90)))
                .togetherWith(fadeOut(tween(90)))
        },
        label = "islandContent"
    ) { s ->
        when (s) {
            IslandState.Idle -> Unit
            IslandState.Compact -> CompactRow()
            IslandState.Expanded -> ExpandedCard()
        }
    }
}
```

### 5.4 Content animations worth building

| Content | Animation idea |
|---|---|
| Music | Animated equalizer bars, album art that scales in, progress bar with smooth easing |
| Charging | A ring that sweeps up to the battery percentage with `Canvas` `drawArc`, plus a short pulse when charging starts |
| Timer / session countdown | Ring that depletes smoothly, digits that roll with `AnimatedContent` |
| Call | Pulsing avatar, waveform, slide-in answer and decline buttons |
| Notification peek | Icon pops in first, then the text slides out from it |
| Quick actions | Buttons that scale on press with a spring |

For complex vector animations (checkmarks, loaders, success bursts), use **Lottie** (`lottie-compose`) instead of hand-coding them.

### 5.5 Gestures

- **Tap:** expand or collapse.
- **Long-press:** open the larger panel, with a haptic tick (`HapticFeedbackConstants`).
- **Swipe down:** expand. **Swipe up:** collapse.
- **Drag with rubber-band:** track the finger with `Animatable` and `pointerInput`, then let a spring settle to the nearest state on release.

### 5.6 Performance for smooth 120Hz

- Use `Modifier.graphicsLayer { }` for scale, alpha and translation, which skips layout and recomposition.
- Read animated values inside draw or graphics-layer lambdas where possible, to avoid recomposing the whole tree every frame.
- Keep the idle state completely static. No running animations when hidden.
- Avoid heavy blur or large shadows. They are expensive and often look worse on a black pill anyway.
- Keep the window as small as possible while idle.

### 5.7 Compose-in-a-service gotcha

A `ComposeView` added to `WindowManager` from a service has no lifecycle owner by default, and Compose will crash. Before `addView`, set these on the view:

- `ViewTreeLifecycleOwner`
- `ViewTreeSavedStateRegistryOwner`
- `ViewTreeViewModelStoreOwner` (if you use view models)

A small helper class that implements `LifecycleOwner` and `SavedStateRegistryOwner` inside the service solves this. It is the most common stumbling block when building overlays with Compose.

---

## 6. What the Island Can Do (Data Sources)

| Feature | API / Approach |
|---|---|
| Media controls and album art | `NotificationListenerService` plus `MediaSessionManager.getActiveSessions()` |
| Notification peeks | `NotificationListenerService` |
| Charging animation and battery | `ACTION_BATTERY_CHANGED` / `ACTION_POWER_CONNECTED` |
| Calls (caller, answer/decline) | `TelephonyCallback` / `READ_PHONE_STATE` (answering needs additional permissions) |
| Timers, stopwatch, countdowns | Your own state kept in the service |
| Navigation turn-by-turn | Maps notification, read via the notification listener |
| Quick actions (flashlight, DND, Wi-Fi) | `CameraManager.setTorchMode`, `NotificationManager` DND policy |
| Live activities (delivery, cab, sports) | Parse notifications from those apps |
| Screen recording / mic / camera indicator | `AppOpsManager` callbacks |

### Additional ideas

- A **session countdown** for apps you are limiting (ties into your existing app limiter project).
- Per-app shortcuts.
- A clipboard peek.
- **Glyph LED sync**: Nothing has a Glyph Developer Kit, so the island could trigger or mirror Glyph patterns.
- A mini calendar or "next event" view.

---

## 7. Kotlin vs Flutter: The Difference

**Kotlin (native Android)**
- Runs directly in the system window. It is light on memory, starts instantly and positions precisely around the cutout.
- Has direct access to `WindowManager`, `NotificationListenerService`, `MediaSession` and `UsageStatsManager`.
- You would write the island UI in Compose or plain Views, which is a new skill if you have only done Flutter. Compose is conceptually close to Flutter (declarative, state-driven), so the learning curve is gentle.

**Flutter**
- You already know it, and the UI and animations are quick to build.
- The overlay package (`flutter_overlay_window`) runs a separate Flutter engine in the overlay. That uses more RAM, adds startup lag and gives you less control over exact positioning above the status bar and over resize animations.
- The system listeners still need native code, so you cannot avoid Kotlin entirely.

| | Kotlin | Flutter |
|---|---|---|
| Memory while idle | Low | Higher (extra engine) |
| Startup of the overlay | Instant | Noticeable lag |
| Precise cutout positioning | Full control | Limited by the package |
| System APIs | Direct | Needs native bridge anyway |
| Your current skill | New | Already known |
| Best for | Island, service, listeners | Settings, onboarding, toggles |

**Verdict: hybrid.** Island and system-facing code in Kotlin, settings in Flutter.

A fast shortcut for a first look: the `flutter_overlay_window` route lets you see a working island in an evening using only Dart. Expect to rewrite the overlay part in Kotlin if you keep the project.

---

## 8. Gotchas

- Hide or shrink the island in **landscape**, **fullscreen video**, and **games**.
- **Android 12+ blocks "untrusted touches"** that pass through overlays, so do not make the expanded island cover large areas unnecessarily.
- Notification access, overlay permission, usage access and battery exemption all need **manual grants**, so build a clean onboarding screen in the Flutter app.
- Keep the idle state cheap: **no polling, only event callbacks**.
- Nothing OS can be aggressive with background apps, so the foreground service plus battery exemption is required.

---

## 9. GPay and Accessibility Follow-up

### Background
Your earlier app-pause/limiter app used an Accessibility Service. That caused issues in **GPay**, so you switched to the official method that Digital Wellbeing uses.

### Why it happened
GPay and other banking/UPI apps check whether an accessibility service is enabled, because such services can read the screen and tap on the user's behalf. That check is what broke the limiter.

### Will the island cause the same problem?
**Mostly no, as long as you skip the Accessibility route.** The recommended approach does not use accessibility at all:

- **Island window:** a normal `TYPE_APPLICATION_OVERLAY` window (needs "Display over other apps").
- **Media and notifications:** `NotificationListenerService`, a separate permission that payment apps do not check for.
- **Foreground app detection:** `UsageStatsManager`, the same kind of official API Digital Wellbeing uses.

### Remaining small risk: overlays
Some banking apps set `filterTouchesWhenObscured` or flag "screen overlay detected" to block tapjacking. Two things keep this low:

1. The idle state is a tiny transparent window over the camera cutout, not covering any app content. Android only drops touches that pass through an overlay, so GPay's buttons keep working.
2. The risk can be removed entirely by **hiding or removing the island while a payment app is in the foreground**. Keep a small list (GPay, PhonePe, Paytm, banking apps) and check it with `UsageStatsManager`. You likely already have that permission from the limiter. The Flutter settings app can let you edit this list.

### Rule: never expand over payment screens
A large expanded panel is what would trigger touch blocking, so the island should never expand while one of those apps is open.

### Caveat
There is no guarantee of how GPay behaves on Nothing OS without testing. Once a prototype exists:

1. Enable the island.
2. Open GPay and go through a payment flow.
3. Add any apps that misbehave to the hide list (a one-line fix).

---

## 10. Suggested Build Order

1. **Kotlin project setup:** foreground service with the overlay permission flow.
2. **Cutout detection:** read `DisplayCutout.getBoundingRects()` and place the invisible touch zone.
3. **Compose in the overlay:** add the lifecycle owner helper (section 5.7) and render a static black pill.
4. **State machine:** Idle, Compact, Expanded, with the resize-at-the-edges window logic (section 4).
5. **Animations:** spring morph from the cutout, staggered content, interruptible taps (section 5).
6. **Data sources, one at a time:** battery/charging first, then media, then notifications, then timers and the session countdown.
7. **Payment-app auto-hide** using `UsageStatsManager`.
8. **Flutter settings app** connected via `MethodChannel`: toggles, hide list, appearance options.
9. **Onboarding** in Flutter for all permissions: overlay, notification access, usage access, battery exemption.
10. **Polish:** gestures, haptics, Lottie animations, Glyph integration.
11. **Test** on GPay and other payment apps, in landscape, fullscreen video and games, and tune the hide list.

---

## 11. References

- Open-source Android dynamic island apps on GitHub (search "dynamicSpot") are worth skimming for structure.
- Nothing Glyph Developer Kit for Glyph integration.
- Jetpack Compose animation docs: `animateDpAsState`, `AnimatedContent`, `Animatable`, `spring`.
- `lottie-compose` for complex vector animations.
