package com.originghostplayer.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * Ported from OrangeBox-src's NotificationCastListener.forceRebind()/OriginIsleApp.ensureEnabled():
 * plain requestRebind() is a no-op after a process kill (it only works on a listener explicitly
 * snoozed via requestUnbind, which a kill never does). Toggling the component's enabled state
 * fires ACTION_PACKAGE_CHANGED, which does force a rebind. We hit this exact issue testing the
 * probe after `am force-stop` — the listener stayed unbound until manually toggled.
 */
object RebindHelper {

    // Phase 1 (2026-10-30): 10 s was short enough to flap the component while
    // the system was still binding. 60 s, on elapsedRealtime (not wall clock,
    // which the user can change), and persisted so a process death does not
    // reset the throttle and cause an immediate re-toggle.
    private const val REBIND_THROTTLE_MS = 60_000L
    private const val PREFS = "rebind_helper"
    private const val KEY_LAST_REBIND_ELAPSED_MS = "last_rebind_elapsed_ms"

    @Volatile
    private var lastRebindAt = 0L

    private fun component(context: Context) = ComponentName(context, MediaProbeListener::class.java)

    /** Call periodically (app open, keep-alive service tick) — cheap no-op while connected. */
    fun forceRebindIfNeeded(context: Context) {
        if (MediaProbeListener.instance != null) return
        if (!NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) return
        val now = SystemClock.elapsedRealtime()
        if (lastRebindAt == 0L) {
            val stored = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_REBIND_ELAPSED_MS, 0L)
            // A stored value larger than now means a reboot happened in
            // between (elapsedRealtime restarts at boot) — treat it as stale.
            if (stored in 1..now) lastRebindAt = stored
        }
        if (lastRebindAt != 0L && now - lastRebindAt < REBIND_THROTTLE_MS) return

        val pm = context.packageManager
        val comp = component(context)
        val disabled = runCatching {
            pm.setComponentEnabledSetting(comp, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }.isSuccess
        val enabled = runCatching {
            pm.setComponentEnabledSetting(comp, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        }.isSuccess
        if (disabled && enabled) {
            lastRebindAt = now
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_REBIND_ELAPSED_MS, now).apply()
            runCatching { NotificationListenerService.requestRebind(comp) }
        }
    }

    /** Undo a forceRebind that only got halfway (process died between the two toggle calls). */
    fun ensureEnabled(context: Context) {
        val pm = context.packageManager
        val comp = component(context)
        runCatching {
            if (pm.getComponentEnabledSetting(comp) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(comp, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
