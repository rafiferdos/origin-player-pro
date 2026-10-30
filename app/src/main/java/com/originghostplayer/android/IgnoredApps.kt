package com.originghostplayer.android

import android.content.Context
import android.content.SharedPreferences

/**
 * Apps the user never wants mirrored. They keep playing normally — [MediaProbeListener] just
 * never tracks their sessions, so they never reach OriginPlayer. Stored in SharedPreferences,
 * which the listener and the activities share since they all run in the same process.
 */
object IgnoredApps {
    private const val PREFS = "ignored_apps"
    private const val KEY_PACKAGES = "packages"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PACKAGES, emptySet()).orEmpty()

    fun isIgnored(context: Context, pkg: String): Boolean = pkg in get(context)

    fun setIgnored(context: Context, pkg: String, ignored: Boolean) {
        val next = get(context).toMutableSet()
        if (ignored) next.add(pkg) else next.remove(pkg)
        // Always write a fresh set — mutating the instance getStringSet() returned is unsupported.
        prefs(context).edit().putStringSet(KEY_PACKAGES, next).apply()
        // Drop (or pick back up) the affected app right away instead of on the next poll tick.
        MediaProbeListener.instance?.retrack()
    }
}

/**
 * When each app last played media, so the ignored-apps page can list those first. Recorded by
 * [MediaProbeListener] for every playing session it sees — ignored ones included, so they stay
 * in that section instead of vanishing from it the moment they're ignored.
 */
object MediaHistory {
    private const val PREFS = "media_history"

    /** The listener sees a playing app on every poll tick; only persist every so often. */
    private const val WRITE_INTERVAL_MS = 60_000L

    /**
     * Phase 1 (2026-10-30): history used to grow forever. Cap it — 50 apps,
     * 30 days — so a year of listening does not become an unbounded prefs file
     * and an indefinite listening record.
     */
    private const val MAX_ENTRIES = 50
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    private val lastWritten = HashMap<String, Long>()

    fun notePlaying(context: Context, pkg: String) {
        val now = System.currentTimeMillis()
        synchronized(lastWritten) {
            val last = lastWritten[pkg]
            if (last != null && now - last < WRITE_INTERVAL_MS) return
            lastWritten[pkg] = now
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(pkg, now).apply()
        if (prefs.all.size > MAX_ENTRIES + 10) prune(context)
    }

    /** Package → last-played wall-clock millis, after age/count pruning. */
    fun all(context: Context): Map<String, Long> {
        prune(context)
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (pkg, time) -> (time as? Long)?.let { pkg to it } }
            .toMap()
    }

    /** Drop entries older than [MAX_AGE_MS] and, beyond that, all but the
     *  [MAX_ENTRIES] most recently played. Safe to call repeatedly. */
    fun prune(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val entries = prefs.all
            .mapNotNull { (pkg, time) -> (time as? Long)?.let { pkg to it } }
            .sortedByDescending { it.second }
        val keep = entries
            .filter { now - it.second <= MAX_AGE_MS }
            .take(MAX_ENTRIES)
            .map { it.first }
            .toSet()
        val drop = entries.map { it.first }.filter { it !in keep }
        if (drop.isEmpty()) return
        val editor = prefs.edit()
        drop.forEach { editor.remove(it) }
        editor.apply()
        synchronized(lastWritten) { drop.forEach { lastWritten.remove(it) } }
    }

    /** Remove specific packages, e.g. apps that are no longer installed. */
    fun remove(context: Context, pkgs: Set<String>) {
        if (pkgs.isEmpty()) return
        val editor = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        pkgs.forEach { editor.remove(it) }
        editor.apply()
        synchronized(lastWritten) { pkgs.forEach { lastWritten.remove(it) } }
    }

    /** Delete all recorded history. Used by the "Clear history" action. */
    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
        synchronized(lastWritten) { lastWritten.clear() }
    }
}
