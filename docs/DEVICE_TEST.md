# Phone 2a acceptance check — Isle 0.1.0

## Fit and motion

- Install over the prototype. Pill should retain the agreed 110 × 34 dp fit at +0.
- Tap just below the camera: the panel grows from the pill and settles below the
  status bar; the camera pill stays in place.
- Tap/open/close rapidly during the transition. No jump or stuck oversized window.
- Close with the button, swipe up, outside tap, and the timeout.
- Turn on calibration guides to inspect idle bounds after closing. Controls outside
  the small touch extension should respond as before.
- Try reduced motion and larger Android text. Check panel text and buttons fit.

## Data

- Unplug then plug in power: battery updates and charging appears briefly.
- Enable media/notification access. Play a song, pause/resume/skip, switch players,
  and close the player. Confirm the correct session and artwork are displayed.
- Enable notification peeks and receive a non-sensitive message. Check expiry,
  opening the source app, dismissal/removal, and muting that app.
- Open the island manually, then receive a notification: it must not replace what
  you are interacting with.

## Visibility and lifecycle

- Lock/unlock and rotate both ways: no island or touch interception while hidden.
- Pause from the notification, switch apps, and return. Resume works; Stop removes
  both the island and its touch area.
- Enable usage access and app hiding. Add a normal test app and check hide/restore.
  This check is not a claim of guaranteed payment-app compatibility.
- Change fit and toggles, close/reopen settings, and verify persistence.
- Deny/revoke each permission: the UI should show current permission status; the
  island must not crash or remain attached without overlay permission.
- Swipe settings away: native service continues without a background Flutter engine.

## Report

Use Access → Copy diagnostics, plus a brief description of the issue. A screen
recording is useful for motion problems. Diagnostics exclude notification text and
media titles, but include configured app package lists.
