package dev.isle.isle

import android.content.Context

/** Native storage is the single source of truth; Flutter does not need to stay alive. */
data class IslandSettings(
    val width: Int = 110,
    val height: Int = 34,
    val offset: Int = 0,
    val horizontal: Int = 0,
    val touchExtension: Int = 30,
    val alwaysVisible: Boolean = true,
    val charging: Boolean = true,
    val media: Boolean = true,
    val notifications: Boolean = false,
    val reducedMotion: Boolean = false,
    val haptics: Boolean = true,
    val outline: Boolean = false,
    val autoHide: Boolean = false,
    val hideApps: String = "com.google.android.apps.nbu.paisa.user,com.phonepe.app,net.one97.paytm",
    val mutedApps: String = "",
    val timeout: Int = 5,
    val fontStyle: String = "ntype",
) {
    fun toMap(): Map<String, Any> = mapOf(
        "width" to width, "height" to height, "offset" to offset, "horizontal" to horizontal,
        "touchExtension" to touchExtension, "alwaysVisible" to alwaysVisible, "charging" to charging,
        "media" to media, "notifications" to notifications, "reducedMotion" to reducedMotion,
        "haptics" to haptics, "outline" to outline, "autoHide" to autoHide, "hideApps" to hideApps,
        "mutedApps" to mutedApps, "timeout" to timeout, "fontStyle" to fontStyle,
    )
    companion object {
        fun fromMap(m: Map<*, *>): IslandSettings {
            val d = IslandSettings()
            fun int(k: String, v: Int, lo: Int, hi: Int) = ((m[k] as? Number)?.toInt() ?: v).coerceIn(lo, hi)
            fun bool(k: String, v: Boolean) = m[k] as? Boolean ?: v
            return IslandSettings(
                int("width", d.width, 16, 220), int("height", d.height, 8, 80),
                int("offset", d.offset, -40, 160), int("horizontal", d.horizontal, -80, 80),
                int("touchExtension", d.touchExtension, 0, 80), bool("alwaysVisible", d.alwaysVisible),
                bool("charging", d.charging), bool("media", d.media), bool("notifications", d.notifications),
                false, bool("haptics", d.haptics), bool("outline", d.outline),
                bool("autoHide", d.autoHide), (m["hideApps"] as? String ?: d.hideApps).take(10000),
                (m["mutedApps"] as? String ?: d.mutedApps).take(10000), int("timeout", d.timeout, 3, 15),
                (m["fontStyle"] as? String ?: d.fontStyle),
            )
        }
        fun load(context: Context): IslandSettings {
            val prefs = context.getSharedPreferences("isle", Context.MODE_PRIVATE)
            if (prefs.getBoolean("reducedMotion", false)) {
                prefs.edit().putBoolean("reducedMotion", false).apply()
            }
            return fromMap(prefs.all)
        }
        fun save(context: Context, updates: Map<*, *>): IslandSettings {
            val merged = load(context).toMap().toMutableMap()
            updates.forEach { (k, v) -> if (k is String && v != null && k in merged) merged[k] = v }
            val value = fromMap(merged)
            context.getSharedPreferences("isle", Context.MODE_PRIVATE).edit().apply {
                value.toMap().forEach { (k, v) -> when (v) {
                    is Int -> putInt(k, v)
                    is Boolean -> putBoolean(k, v)
                    is String -> putString(k, v)
                } }
            }.apply()
            return value
        }
    }
}

fun packageSet(value: String): Set<String> = value.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
