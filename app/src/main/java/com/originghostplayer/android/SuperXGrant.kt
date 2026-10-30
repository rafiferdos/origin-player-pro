package com.originghostplayer.android

import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs

/**
 * Ported from OrangeBox-src's OriginIslandBuilder.grantScenes(): invokes the hidden
 * NotificationManager.setSuperXInfosSceneList(List, List, List, List) to whitelist this
 * (spoofed) package for every known SuperX scene. Proven to work for the AMap-spoofed app's
 * NAVIGATION scene — testing here whether OriginPlayer's native media treatment is actually
 * gated by this same island whitelist, rather than being an independent system.
 */
object SuperXGrant {

    private const val TAG = "SuperXGrant"
    private const val PREFS = "superx_grant"
    private const val KEY_BOOT_TIME_MS = "boot_time_ms"

    // NOTE: "DELIEVERY" and "METTING" look like typos, but they are NOT fixed
    // here on purpose. The same list is ported verbatim in OriginIsland's
    // SUPERX_SCENES, so vivo's system most likely expects these exact tokens.
    // Changing them would be a behaviour change, not a typo fix — verified
    // 2026-10-30 against origin-island-pro's OriginIslandConstants.kt.
    private val SUPERX_SCENES = listOf(
        "NAVIGATION", "MOVIE", "HEALTH_REGISTER", "TAXI", "TAKEOUT", "DELIEVERY",
        "CAR_STATE", "METTING", "TRAIN", "FLIGHT", "INCALLING", "VOIPCALL",
        "TIMER", "RIDE_GUIDE", "CRITICAL",
    )

    /** Phase 1 (2026-10-30): an attempt in this process is enough — the old
     *  code re-ran hidden-API reflection on every activity/service start. */
    @Volatile
    private var attemptedInProcess = false

    fun grant(context: Context) {
        if (attemptedInProcess) return
        attemptedInProcess = true
        val appContext = context.applicationContext
        // A successful grant is remembered for this boot, so a new process in
        // the same boot does not repeat it either. A failed attempt is NOT
        // remembered across processes, so a transient failure can retry once
        // per process instead of being stuck for the whole boot.
        val bootTimeMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastBootTimeMs = prefs.getLong(KEY_BOOT_TIME_MS, 0L)
        if (lastBootTimeMs != 0L && abs(bootTimeMs - lastBootTimeMs) < 60_000L) {
            Log.d(TAG, "grant already done this boot, skipping")
            return
        }
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm == null) {
            Log.w(TAG, "NotificationManager unavailable")
            return
        }
        try {
            val method = NotificationManager::class.java.getMethod(
                "setSuperXInfosSceneList",
                List::class.java, List::class.java, List::class.java, List::class.java,
            )
            val scenes = ArrayList(SUPERX_SCENES)
            val enabled = ArrayList<String>()
            val pkgs = ArrayList<String>()
            val allowed = ArrayList<String>()
            repeat(scenes.size) {
                enabled.add("true")
                pkgs.add(appContext.packageName)
                allowed.add("true")
            }
            method.invoke(nm, scenes, enabled, pkgs, allowed)
            prefs.edit().putLong(KEY_BOOT_TIME_MS, bootTimeMs).apply()
            Log.d(TAG, "setSuperXInfosSceneList granted for ${appContext.packageName}")
        } catch (t: Throwable) {
            Log.w(TAG, "setSuperXInfosSceneList unavailable", t)
        }
    }
}
