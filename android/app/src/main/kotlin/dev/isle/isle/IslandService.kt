package dev.isle.isle

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.*
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.session.PlaybackState
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.*
import androidx.savedstate.*
import kotlin.math.abs

class IslandService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windows: WindowManager
    private var pillParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var pillRoot: FrameLayout? = null
    private var panelRoot: FrameLayout? = null
    private var pillComposition: ComposeView? = null
    private var panelComposition: ComposeView? = null
    var settings by mutableStateOf(IslandSettings()); private set
    var state by mutableStateOf(IslandState()); private set
    var battery by mutableIntStateOf(-1); private set
    var charging by mutableStateOf(false); private set
    var media by mutableStateOf<MediaInfo?>(null); private set
    var peek by mutableStateOf<NotificationPeek?>(null); private set
    var chargePeek by mutableStateOf(false); private set
    var torchOn by mutableStateOf(false); private set
    var ringerMode by mutableIntStateOf(AudioManager.RINGER_MODE_NORMAL); private set
    private val cameraManager by lazy { getSystemService(CameraManager::class.java) }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            torchOn = enabled
        }
    }
    var panelWidthDp by mutableFloatStateOf(385f); private set
    var panelTopDp by mutableFloatStateOf(48f); private set
    var panelHeightDp by mutableFloatStateOf(224f); private set
    private var paused = false
    private var statusBar = 0
    private var cutoutY: Int? = null
    private var foregroundPackage = ""
    private var lastUsageQuery = 0L
    private var batteryInitialized = false
    private var receiverRegistered = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var gestureCancelled = false
    private val close = Runnable { collapse() }
    private val expirePeek = Runnable { peek = null; chargePeek = false; if (!state.pinnedByUser) collapse() }
    private val usageCheck = object : Runnable {
        override fun run() {
            if (!shouldCheckUsage()) return
            readForegroundApp()
            refreshVisibility()
            handler.postDelayed(this, 1000)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                battery = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
                val power = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                val newlyConnected = batteryInitialized && power && !charging
                charging = power; batteryInitialized = true
                if (newlyConnected && settings.charging && canShow() && !state.pinnedByUser) {
                    chargePeek = true; peek = null
                    handler.removeCallbacks(expirePeek); handler.postDelayed(expirePeek, settings.timeout * 1000L)
                    if (!state.pinnedByUser) open(false)
                } else if (state.presentation != Presentation.EXPANDED) state = state.settle(hasActivity())
            } else {
                refreshEnvironment()
            }
        }
    }
    companion object {
        var current: IslandService? = null; private set
        private var lastError = ""
        fun usageGranted(context: Context): Boolean = context.getSystemService(AppOpsManager::class.java)
            .checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        fun snapshot(context: Context): Map<String, Any> {
            val s = current
            val values = IslandSettings.load(context)
            val location = IntArray(2); s?.pillRoot?.getLocationOnScreen(location)
            val notifications = context.getSystemService(NotificationManager::class.java)
            val activeRoot = if (s?.state?.presentation == Presentation.EXPANDED) s?.panelRoot ?: s?.pillRoot else s?.pillRoot
            val activeParams = if (s?.state?.presentation == Presentation.EXPANDED) s?.panelParams ?: s?.pillParams else s?.pillParams
            return mapOf(
                "build" to "0.1.0", "permission" to Settings.canDrawOverlays(context),
                "notificationAccess" to notifications.isNotificationListenerAccessGranted(ComponentName(context, IslandListener::class.java)),
                "notificationPermission" to notifications.areNotificationsEnabled(),
                "listenerConnected" to IslandListener.connected, "usageAccess" to usageGranted(context),
                "batteryExempt" to context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
                "running" to (s?.pillRoot != null), "paused" to (s?.paused ?: false),
                "suppressed" to (s?.state?.suppressed ?: false),
                "presentation" to (s?.state?.presentation?.name ?: "STOPPED"),
                "battery" to (s?.battery ?: -1), "charging" to (s?.charging ?: false),
                "settings" to values.toMap(), "error" to lastError,
                "windowWidthPx" to (activeRoot?.width ?: 0), "windowHeightPx" to (activeRoot?.height ?: 0),
                "geometry" to (s?.let { "${activeParams?.width} × ${activeParams?.height}px at ${location[0]},${location[1]}; status bar ${it.statusBar}px; panel top ${it.panelTopDp}dp" } ?: "Detached"),
                "device" to "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}",
            )
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        current = this; lastError = ""
        saved.performAttach(); saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        windows = getSystemService(WindowManager::class.java)
        settings = IslandSettings.load(this)
        paused = getSharedPreferences("isle", MODE_PRIVATE).getBoolean("paused", false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("isle", "Isle controls", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, serviceNotification())
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_BATTERY_CHANGED)
        })
        receiverRegistered = true
        try {
            cameraManager.registerTorchCallback(torchCallback, handler)
            ringerMode = audioManager.ringerMode
        } catch (_: Exception) {}
    }
    private fun serviceNotification(): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, IslandService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 3, Intent(this, IslandService::class.java).setAction("pause"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "isle").setSmallIcon(R.drawable.ic_isle)
            .setContentTitle(if (paused) "Isle is paused" else "Isle is on")
            .setContentText(if (paused) "The island and touch area are hidden." else "Tap just below the camera. Pause anytime here.")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, if (paused) "Resume" else "Pause", pause).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) { fail("Display over other apps permission is missing."); return START_NOT_STICKY }
        if (intent?.action == "pause") {
            paused = !paused
            getSharedPreferences("isle", MODE_PRIVATE).edit().putBoolean("paused", paused).apply()
        } else if (intent?.action == "start") {
            paused = false
            getSharedPreferences("isle", MODE_PRIVATE).edit().putBoolean("paused", false).apply()
        }
        getSystemService(NotificationManager::class.java).notify(1, serviceNotification())
        try {
            if (pillRoot == null) attach()
            applySettings()
            if (intent?.action == "preview") open(true)
        } catch (e: Exception) { fail("Unable to attach island: ${e.message}") }
        return START_STICKY
    }
    fun applySettings() {
        settings = IslandSettings.load(this)
        if (!settings.notifications || peek?.packageName in packageSet(settings.mutedApps)) peek = null
        if (!settings.charging) chargePeek = false
        mediaChanged()
        refreshEnvironment()
        if (state.presentation == Presentation.EXPANDED) scheduleClose()
        place()
    }
    fun mediaChanged() {
        media = if (settings.media) IslandListener.media else null
        if (state.presentation != Presentation.EXPANDED) state = state.settle(hasActivity())
        place()
    }
    fun notificationPosted(value: NotificationPeek) {
        if (!settings.notifications || !canShow() || state.pinnedByUser) return
        peek = value; chargePeek = false
        handler.removeCallbacks(expirePeek); handler.postDelayed(expirePeek, settings.timeout * 1000L)
        open(false)
    }
    fun notificationRemoved(key: String) {
        if (peek?.key == key) { peek = null; if (!state.pinnedByUser) collapse() }
    }
    fun notificationAccessLost() { peek = null; mediaChanged() }
    private fun hasActivity() = (settings.charging && charging) || media?.playing == true
    private fun deviceAvailable(): Boolean = getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
        resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    private fun shouldCheckUsage() = settings.autoHide && usageGranted(this) && deviceAvailable() && !paused
    private fun readForegroundApp() {
        val now = System.currentTimeMillis()
        try {
            val events = getSystemService(UsageStatsManager::class.java).queryEvents(
                if (lastUsageQuery == 0L) now - 300_000 else lastUsageQuery - 1000, now)
            val e = UsageEvents.Event()
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) foregroundPackage = e.packageName.orEmpty()
            }
            lastUsageQuery = now
        } catch (_: SecurityException) { foregroundPackage = "" }
    }
    private fun canShow(): Boolean = !paused && deviceAvailable() && Settings.canDrawOverlays(this) &&
        !(settings.autoHide && usageGranted(this) && foregroundPackage in packageSet(settings.hideApps))
    fun refreshEnvironment() {
        handler.removeCallbacks(usageCheck)
        if (shouldCheckUsage()) { readForegroundApp(); handler.postDelayed(usageCheck, 1000) }
        else { lastUsageQuery = 0L; foregroundPackage = "" }
        refreshVisibility()
    }
    private fun refreshVisibility() {
        if (!Settings.canDrawOverlays(this)) { fail("Overlay permission was revoked."); return }
        val suppress = !canShow()
        if (suppress && !state.suppressed) {
            peek = null; chargePeek = false
            handler.removeCallbacks(close); handler.removeCallbacks(expirePeek)
        }
        state = state.suppress(suppress, hasActivity())
        pillRoot?.visibility = if (suppress) View.GONE else View.VISIBLE
        panelRoot?.visibility = if (suppress) View.GONE else View.VISIBLE
    }
    fun open(user: Boolean) {
        android.util.Log.i("Isle", "open() user=$user, canShow=${canShow()}, paused=$paused")
        if (shouldCheckUsage()) readForegroundApp()
        if (!canShow()) { refreshVisibility(); return }
        if (user) { peek = null; chargePeek = false }
        val panP = panelParams
        val panFrame = panelRoot
        if (panP != null && panFrame != null) {
            panP.flags = (panP.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()) or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && settings.glassmorphism) {
                panP.flags = panP.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                panP.setBlurBehindRadius(dp(36))
            }
            try { windows.updateViewLayout(panFrame, panP) } catch (_: Exception) {}
        }
        state = state.open(user)
        if (settings.haptics && user) pillRoot?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        scheduleClose()
    }
    fun collapse() {
        android.util.Log.i("Isle", "collapse() called")
        if (settings.haptics && state.presentation == Presentation.EXPANDED) {
            pillRoot?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        handler.removeCallbacks(close)
        state = state.settle(hasActivity())
    }
    fun animationFinished() {
        android.util.Log.i("Isle", "animationFinished() state=${state.presentation}")
        if (state.presentation != Presentation.EXPANDED) {
            val panP = panelParams
            val panFrame = panelRoot
            if (panP != null && panFrame != null) {
                panP.flags = (panP.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    panP.flags = panP.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                    panP.setBlurBehindRadius(0)
                }
                try { windows.updateViewLayout(panFrame, panP) } catch (_: Exception) {}
            }
        }
    }
    fun scheduleClose() {
        handler.removeCallbacks(close)
        handler.postDelayed(close, settings.timeout * 1000L)
    }
    fun mediaAction(action: String) {
        val c = media?.controller ?: return
        try {
            when (action) {
                "previous" -> {
                    val actions = c.playbackState?.actions ?: 0L
                    if (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) {
                        c.transportControls.skipToPrevious()
                    } else if (actions and PlaybackState.ACTION_REWIND != 0L) {
                        c.transportControls.rewind()
                    } else {
                        c.transportControls.skipToPrevious()
                    }
                }
                "next" -> {
                    val actions = c.playbackState?.actions ?: 0L
                    if (actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) {
                        c.transportControls.skipToNext()
                    } else if (actions and PlaybackState.ACTION_FAST_FORWARD != 0L) {
                        c.transportControls.fastForward()
                    } else {
                        c.transportControls.skipToNext()
                    }
                }
                "toggle" -> {
                    val state = c.playbackState?.state
                    if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) {
                        c.transportControls.pause()
                    } else {
                        c.transportControls.play()
                    }
                }
            }
        } catch (_: Exception) { lastError = "This media app did not accept the control." }
        scheduleClose()
    }
    fun mediaSeek(positionMs: Long) {
        val c = media?.controller ?: return
        try {
            c.transportControls.seekTo(positionMs)
        } catch (_: Exception) {}
        scheduleClose()
    }
    fun toggleTorch() {
        try {
            val id = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull() ?: return
            val next = !torchOn
            cameraManager.setTorchMode(id, next)
            torchOn = next
        } catch (_: Exception) {}
        scheduleClose()
    }
    fun toggleRinger() {
        try {
            val current = audioManager.ringerMode
            val next = when (current) {
                AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            audioManager.ringerMode = next
            ringerMode = audioManager.ringerMode
        } catch (_: SecurityException) {
            try {
                val next = if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                    AudioManager.RINGER_MODE_VIBRATE
                } else {
                    AudioManager.RINGER_MODE_NORMAL
                }
                audioManager.ringerMode = next
                ringerMode = next
            } catch (_: Exception) {}
        } catch (_: Exception) {}
        scheduleClose()
    }
    fun openMusicApp() {
        val pm = packageManager
        val intent = pm.getLaunchIntentForPackage("com.spotify.music")
            ?: pm.getLaunchIntentForPackage("com.google.android.apps.youtube.music")
            ?: pm.getLaunchIntentForPackage("com.google.android.youtube")
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)
        try {
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            collapse()
        } catch (_: Exception) {}
    }
    fun openClockApp() {
        try {
            val intent = Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            collapse()
        } catch (_: Exception) {
            try {
                val fallback = packageManager.getLaunchIntentForPackage("com.google.android.deskclock")
                    ?: packageManager.getLaunchIntentForPackage("com.android.deskclock")
                fallback?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (fallback != null) startActivity(fallback)
                collapse()
            } catch (_: Exception) {}
        }
    }
    fun openNotification() {
        val intent = peek?.intent ?: return
        collapse(); peek = null
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val options = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                    if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                    else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                intent.send(this, 0, null, null, null, null, options.toBundle())
            } else intent.send()
        } catch (_: PendingIntent.CanceledException) { lastError = "That notification is no longer available." }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun readGeometry() {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windows.maximumWindowMetrics
            statusBar = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            cutoutY = metrics.windowInsets.displayCutout?.boundingRects?.filter { it.top < metrics.bounds.height() / 3 }
                ?.minByOrNull { abs(it.centerX() - metrics.bounds.centerX()) }?.centerY()
        } else {
            statusBar = pillRoot?.rootWindowInsets?.systemWindowInsetTop ?: dp(24)
        }
    }
    private fun attach() {
        readGeometry()
        val pillP = WindowManager.LayoutParams(dp(settings.width), dp(settings.height + settings.touchExtension),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            alpha = 1.0f
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        pillParams = pillP

        val pFrame = object : FrameLayout(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    downX = event.rawX; downY = event.rawY; downAt = SystemClock.uptimeMillis()
                    gestureCancelled = false
                    android.util.Log.i("Isle", "pFrame DOWN at (${downX}, ${downY})")
                }
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) gestureCancelled = true
                if (event.actionMasked == MotionEvent.ACTION_UP && !gestureCancelled) {
                    val dx = abs(event.rawX - downX)
                    val dy = event.rawY - downY
                    val slop = ViewConfiguration.get(context).scaledTouchSlop
                    android.util.Log.i("Isle", "pFrame UP at (${event.rawX}, ${event.rawY}), dx=$dx, dy=$dy, slop=$slop, elapsed=${SystemClock.uptimeMillis() - downAt}")
                    if ((dx < slop && abs(dy) < slop && SystemClock.uptimeMillis() - downAt < 600) || (dy > dp(24) && dx < dp(40))) {
                        if (state.presentation == Presentation.EXPANDED) collapse() else open(true)
                    }
                }
                return true
            }
        }
        pFrame.setViewTreeLifecycleOwner(this); pFrame.setViewTreeSavedStateRegistryOwner(this)
        val pContent = ComposeView(this)
        pillComposition = pContent
        pFrame.addView(pContent, FrameLayout.LayoutParams(-1, -1))
        pContent.setContent { PillOverlay(this) }
        pillRoot = pFrame

        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        panelWidthDp = ((screenWidth - dp(32)) / density).coerceAtLeast(320f)
        val yPos = ((cutoutY ?: dp(24)) - dp(20) + dp(settings.offset)).coerceAtLeast(0)
        panelTopDp = maxOf(settings.height + 8f, (statusBar - yPos) / density + 10f)
        panelHeightDp = 224f + (resources.configuration.fontScale.coerceAtLeast(1f) - 1f) * 100f

        val panP = WindowManager.LayoutParams(((panelWidthDp + 8f) * density).toInt(), ((panelTopDp + panelHeightDp + 8f) * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            alpha = 1.0f
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        }
        panelParams = panP

        val panFrame = object : FrameLayout(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                android.util.Log.i("Isle", "panFrame touch: ${event.actionMasked} at (${event.rawX}, ${event.rawY}), y=${event.y}")
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { collapse(); return false }
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    downX = event.rawX; downY = event.rawY; downAt = SystemClock.uptimeMillis()
                    gestureCancelled = false
                    handler.removeCallbacks(close)
                }
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    gestureCancelled = true
                    val cancel = MotionEvent.obtain(event); cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel); cancel.recycle()
                    scheduleClose()
                    return true
                }
                val dy = event.rawY - downY
                if (!gestureCancelled && event.actionMasked == MotionEvent.ACTION_MOVE && dy < -dp(28)) {
                    gestureCancelled = true
                    val cancel = MotionEvent.obtain(event); cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel); cancel.recycle()
                    collapse(); return true
                }
                if (gestureCancelled) return true
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (event.y < panelTopDp * resources.displayMetrics.density) { collapse(); return true }
                    scheduleClose()
                }
                return super.dispatchTouchEvent(event)
            }
        }
        panFrame.setViewTreeLifecycleOwner(this); panFrame.setViewTreeSavedStateRegistryOwner(this)
        val panContent = ComposeView(this)
        panelComposition = panContent
        panFrame.addView(panContent, FrameLayout.LayoutParams(-1, -1))
        panContent.setContent { PanelOverlay(this) }
        panelRoot = panFrame
        windows.addView(panFrame, panP)
        windows.addView(pFrame, pillP)

        pFrame.setOnApplyWindowInsetsListener { _, insets ->
            val previous = cutoutY
            readGeometry()
            if (previous != cutoutY) pFrame.post { place() }
            insets
        }
        pFrame.requestApplyInsets()
    }
    private fun place() {
        val pill = pillRoot ?: return
        val panel = panelRoot ?: return
        val pP = pillParams ?: return
        val panP = panelParams ?: return
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val yPos = ((cutoutY ?: dp(24)) - dp(20) + dp(settings.offset)).coerceAtLeast(0)
        pP.y = yPos
        panP.y = yPos
        panelWidthDp = ((screenWidth - dp(32)) / density).coerceAtLeast(320f)
        panelTopDp = maxOf(settings.height + 8f, (statusBar - yPos) / density + 10f)
        panelHeightDp = if (peek != null) {
            224f + (resources.configuration.fontScale.coerceAtLeast(1f) - 1f) * 100f
        } else {
            220f
        }

        pP.width = dp(settings.width)
        pP.height = dp(settings.height + settings.touchExtension)
        val maxShiftPill = ((screenWidth - pP.width) / 2).coerceAtLeast(0)
        pP.x = dp(settings.horizontal).coerceIn(-maxShiftPill, maxShiftPill)

        panP.width = ((panelWidthDp + 8f) * density).toInt()
        panP.height = ((panelTopDp + 240f + 16f) * density).toInt()
        val maxShiftPanel = ((screenWidth - panP.width) / 2).coerceAtLeast(0)
        panP.x = dp(settings.horizontal).coerceIn(-maxShiftPanel, maxShiftPanel)

        try {
            windows.updateViewLayout(pill, pP)
            windows.updateViewLayout(panel, panP)
        } catch (e: Exception) { fail("Window update failed: ${e.message}") }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        collapse(); readGeometry(); refreshEnvironment()
    }
    private fun fail(message: String) { lastError = message; stopSelf() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) unregisterReceiver(receiver)
        try { cameraManager.unregisterTorchCallback(torchCallback) } catch (_: Exception) {}
        pillComposition?.disposeComposition(); pillComposition = null
        panelComposition?.disposeComposition(); panelComposition = null
        pillRoot?.let { if (it.isAttachedToWindow) windows.removeViewImmediate(it) }; pillRoot = null
        panelRoot?.let { if (it.isAttachedToWindow) windows.removeViewImmediate(it) }; panelRoot = null
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        if (current === this) current = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
