package dev.isle.isle

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** Emulator-only integration checks. Uses synthetic media, notifications and battery state. */
class IsleSmokeRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private val results = mutableListOf<String>()
    private fun main(block: () -> Unit) = runOnMainSync(block)
    private fun await(label: String, timeout: Long = 8000, test: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        var okay = false
        while (System.currentTimeMillis() < end) {
            main { okay = test() }
            if (okay) { results += "PASS $label"; return }
            Thread.sleep(100)
        }
        error("Timed out: $label")
    }
    private fun shell(command: String) {
        uiAutomation.executeShellCommand(command).use { p ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(p).use { it.readBytes() }
        }
    }
    override fun onStart() {
        var session: MediaSession? = null
        var success = true
        try {
            startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            main {
                IslandSettings.save(targetContext, mapOf("notifications" to true, "charging" to true,
                    "media" to true, "autoHide" to false, "timeout" to 3, "alwaysVisible" to true))
                targetContext.startForegroundService(Intent(targetContext, IslandService::class.java).setAction("start"))
            }
            await("foreground service attaches") { IslandService.snapshot(targetContext)["running"] == true }
            main { IslandService.current!!.open(true) }
            await("manual expansion") { IslandService.current!!.state.presentation == Presentation.EXPANDED }
            await("timeout collapses and window shrinks", 6500) {
                val s = IslandService.current!!
                s.state.presentation != Presentation.EXPANDED &&
                    IslandService.snapshot(targetContext)["windowWidthPx"] ==
                        (s.settings.width * targetContext.resources.displayMetrics.density).toInt()
            }
            await("notification listener connected", 12000) { IslandListener.connected }
            var pauseCalled = false
            main {
                session = MediaSession(targetContext, "Isle test music").apply {
                    setCallback(object : MediaSession.Callback() {
                        override fun onPause() {
                            pauseCalled = true
                            setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0, 0f)
                                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE).build())
                        }
                    }, Handler(Looper.getMainLooper()))
                    setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Test track")
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, "Isle test artist").build())
                    setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f)
                        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE).build())
                    isActive = true
                }
            }
            await("active media metadata") { IslandService.current!!.media?.title == "Test track" }
            main { IslandService.current!!.mediaAction("toggle") }
            await("pause control targets active session") { pauseCalled && IslandService.current!!.media?.playing == false }
            main { session?.release(); session = null }
            await("destroyed session is removed") { IslandService.current!!.media == null }
            main { IslandService.current!!.collapse() }
            shell("cmd notification post -t IsleSmokeTitle isle_smoke IsleSmokeBody")
            await("notification becomes a live peek") { IslandService.current!!.peek?.title == "IsleSmokeTitle" }
            await("notification text expires", 6500) { IslandService.current!!.peek == null }
            shell("dumpsys battery unplug")
            await("battery unplug event") { !IslandService.current!!.charging }
            main { IslandService.current!!.open(true) }
            shell("dumpsys battery set ac 1")
            await("charging does not replace a manually opened panel") {
                IslandService.current!!.charging && IslandService.current!!.state.pinnedByUser && !IslandService.current!!.chargePeek
            }
            main { IslandService.current!!.collapse() }
            shell("dumpsys battery unplug")
            await("battery unplug before charging peek") { !IslandService.current!!.charging }
            shell("dumpsys battery set ac 1")
            await("charging arrival expands") { IslandService.current!!.charging && IslandService.current!!.chargePeek }
            main { targetContext.startService(Intent(targetContext, IslandService::class.java).setAction("pause")) }
            await("pause suppresses island and events") { IslandService.current!!.state.suppressed }
            main { IslandService.current!!.open(true) }
            await("paused state rejects expansion") { IslandService.current!!.state.presentation != Presentation.EXPANDED }
            main { targetContext.startService(Intent(targetContext, IslandService::class.java).setAction("pause")) }
            await("resume restores island") { !IslandService.current!!.state.suppressed }
            main { targetContext.stopService(Intent(targetContext, IslandService::class.java)) }
            await("stop removes service and window") { IslandService.current == null }
        } catch (e: Throwable) {
            success = false
            results += "FAIL " + e.stackTraceToString()
        } finally {
            main { session?.release(); targetContext.stopService(Intent(targetContext, IslandService::class.java)) }
            shell("dumpsys battery reset")
        }
        finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", "\n" + results.joinToString("\n") + "\n") })
    }
}
