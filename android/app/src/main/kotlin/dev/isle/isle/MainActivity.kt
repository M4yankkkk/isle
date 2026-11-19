package dev.isle.isle

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        handleAction(intent)
        if (Settings.canDrawOverlays(this) && IslandService.current == null) {
            startForegroundService(Intent(this, IslandService::class.java).setAction("start"))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAction(intent)
    }

    private fun handleAction(intent: Intent?) {
        val action = intent?.getStringExtra("action") ?: intent?.action
        if (action in setOf("start", "preview", "pause")) {
            if (Settings.canDrawOverlays(this)) {
                startForegroundService(Intent(this, IslandService::class.java).setAction(action))
            }
        } else if (action == "stop") {
            stopService(Intent(this, IslandService::class.java))
        }
    }

    override fun onResume() { super.onResume(); IslandService.current?.refreshEnvironment() }
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "dev.isle/app")
            .setMethodCallHandler { call, result ->
                try {
                    when (call.method) {
                        "status" -> result.success(IslandService.snapshot(this))
                        "save" -> {
                            val settings = IslandSettings.save(this, call.arguments as? Map<*, *> ?: emptyMap<String, Any>())
                            IslandService.current?.applySettings()
                            result.success(settings.toMap())
                        }
                        "permission" -> {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                            result.success(null)
                        }
                        "notificationAccess" -> {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            result.success(null)
                        }
                        "usageAccess" -> { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); result.success(null) }
                        "batterySettings" -> { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); result.success(null) }
                        "notifications" -> {
                            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
                            result.success(null)
                        }
                        "start", "preview", "pause" -> {
                            check(Settings.canDrawOverlays(this)) { "Allow display over other apps first." }
                            startForegroundService(Intent(this, IslandService::class.java).setAction(call.method))
                            result.success(null)
                        }
                        "stop" -> { stopService(Intent(this, IslandService::class.java)); result.success(null) }
                        "apps" -> {
                            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            val apps = packageManager.queryIntentActivities(launcher, 0)
                                .distinctBy { it.activityInfo.packageName }.map {
                                    mapOf("package" to it.activityInfo.packageName, "name" to it.loadLabel(packageManager).toString())
                                }.sortedBy { it["name"]?.lowercase() }
                            result.success(apps)
                        }
                        else -> result.notImplemented()
                    }
                } catch (e: Exception) { result.error("ISLE", e.message ?: "Unable to complete action", null) }
            }
    }
}
