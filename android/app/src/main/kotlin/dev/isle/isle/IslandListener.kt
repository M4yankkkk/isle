package dev.isle.isle

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

 data class MediaInfo(
    val title: String, val artist: String, val playing: Boolean, val artwork: Bitmap?,
    val actions: Long, val duration: Long, val position: Long,
    val lastPositionUpdate: Long, val playbackSpeed: Float, val controller: MediaController,
 )
 data class NotificationPeek(val key: String, val app: String, val title: String, val text: String,
    val intent: PendingIntent?, val packageName: String)

class IslandListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private val controllers = linkedMapOf<MediaController, MediaController.Callback>()
    private lateinit var sessions: MediaSessionManager
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { replaceControllers(it.orEmpty()) }
    private var attached = false
    private var lastPeekKey = ""
    private var lastPeekText = ""
    companion object {
        var connected = false; private set
        var media: MediaInfo? = null; private set
    }
    override fun onListenerConnected() {
        connected = true
        sessions = getSystemService(MediaSessionManager::class.java)
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, ComponentName(this, IslandListener::class.java), handler)
            attached = true
            replaceControllers(sessions.getActiveSessions(ComponentName(this, IslandListener::class.java)))
        } catch (_: SecurityException) { clearMedia() }
    }
    private fun replaceControllers(next: List<MediaController>) {
        controllers.forEach { (c, cb) -> c.unregisterCallback(cb) }
        controllers.clear()
        next.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = publishMedia()
                override fun onPlaybackStateChanged(state: PlaybackState?) = publishMedia()
                override fun onSessionDestroyed() {
                    controllers.remove(c)?.let { c.unregisterCallback(it) }
                    publishMedia()
                }
            }
            controllers[c] = cb
            c.registerCallback(cb, handler)
        }
        publishMedia()
    }
    private fun isValidMediaController(c: MediaController): Boolean {
        val pkg = c.packageName
        if (pkg == "com.nothing.hearthstone" || pkg == "com.android.server.telecom" ||
            pkg == "android" || pkg == "com.android.systemui") return false

        val state = c.playbackState?.state ?: return false
        if (state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_ERROR) {
            return false
        }

        val title = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val isPlaying = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
        return isPlaying || (!title.isNullOrBlank() && title != "Now playing")
    }

    private fun publishMedia() {
        val activeStates = setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING
        )
        val valid = controllers.keys.filter { isValidMediaController(it) }
        val c = valid.firstOrNull { it.playbackState?.state in activeStates }
            ?: valid.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
        media = c?.let {
            val m = it.metadata
            val pb = it.playbackState
            var image = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            if (image == null) {
                val uriStr = m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    ?: m?.getString(MediaMetadata.METADATA_KEY_ART_URI)
                if (uriStr != null) {
                    try {
                        contentResolver.openInputStream(android.net.Uri.parse(uriStr))?.use { stream ->
                            image = android.graphics.BitmapFactory.decodeStream(stream)
                        }
                    } catch (_: Exception) {}
                }
            }
            // Retain up to 240px cover for crisp rendering on high-DPI displays.
            val thumbnail = image?.let { b ->
                if (b.width > 240 || b.height > 240) {
                    val scale = 240f / maxOf(b.width, b.height)
                    Bitmap.createScaledBitmap(b, (b.width * scale).toInt().coerceAtLeast(1), (b.height * scale).toInt().coerceAtLeast(1), true)
                } else b
            }
            val duration = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val position = pb?.position ?: 0L
            val lastUpdate = pb?.lastPositionUpdateTime ?: 0L
            val speed = pb?.playbackSpeed?.takeIf { s -> s > 0f } ?: 1f
            val isPlaying = pb?.state in activeStates

            val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE)?.take(160)
                ?: if (isPlaying) "Now playing" else ""
            val artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.take(160) ?: it.packageName

            if (title.isBlank() && !isPlaying) return@let null

            MediaInfo(
                title, artist,
                isPlaying, thumbnail,
                pb?.actions ?: 0L, duration, position, lastUpdate, speed, it
            )
        }
        IslandService.current?.mediaChanged()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val host = IslandService.current ?: return
        val settings = IslandSettings.load(this)
        val n = sbn.notification
        if (!settings.notifications || sbn.packageName == packageName || sbn.isOngoing ||
            n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.category == Notification.CATEGORY_TRANSPORT ||
            n.visibility == Notification.VISIBILITY_SECRET || sbn.packageName in packageSet(settings.mutedApps)) return
        val ranking = Ranking()
        if (currentRanking.getRanking(sbn.key, ranking) && ranking.importance < android.app.NotificationManager.IMPORTANCE_DEFAULT) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(160).orEmpty()
        val body = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(300).orEmpty()
        if (title.isBlank() && body.isBlank()) return
        if (lastPeekKey == sbn.key && lastPeekText == title + body) return
        lastPeekKey = sbn.key; lastPeekText = title + body
        val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            catch (_: Exception) { sbn.packageName }
        host.notificationPosted(NotificationPeek(sbn.key, label, title, body, n.contentIntent, sbn.packageName))
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { IslandService.current?.notificationRemoved(sbn.key) }
    private fun clearMedia() {
        controllers.forEach { (c, cb) -> c.unregisterCallback(cb) }; controllers.clear()
        media = null
        IslandService.current?.mediaChanged()
    }
    override fun onListenerDisconnected() {
        connected = false
        if (attached) { sessions.removeOnActiveSessionsChangedListener(sessionsChanged); attached = false }
        clearMedia()
        IslandService.current?.notificationAccessLost()
    }
    override fun onDestroy() { onListenerDisconnected(); super.onDestroy() }
}
