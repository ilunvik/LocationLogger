package no.grokbot.stedslogger

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import rikka.shizuku.Shizuku

/**
 * Watches whether Shizuku runs on this phone (since 1.27) and tells the dashboard (via [AdbReporter])
 * right away when it has stopped, so the dashboard host can start it again over adb.
 *
 * Detection uses the Shizuku API: the Shizuku server sends its binder to every app that declares
 * `rikka.shizuku.ShizukuProvider` (when the app process starts and when the server starts), and
 * [Shizuku.pingBinder] tells whether that binder is alive. No Shizuku permission is requested or needed,
 * and nothing is called on the binder besides ping. A binder-dead listener notices a stop instantly.
 *
 * States: "running", "stopped", "not_installed", "unknown".
 * While stopped, a forced report is sent at once and then at most every [STOPPED_REPORT_GAP_MS]
 * (at the periodic checks, which also run in Doze via the wake-up alarm).
 */
object ShizukuWatch {
    const val RUNNING = "running"
    const val STOPPED = "stopped"
    const val NOT_INSTALLED = "not_installed"
    const val UNKNOWN = "unknown"

    private const val MANAGER_PKG = "moe.shizuku.privileged.api"
    private const val KEY_STATE = "shizuku_state"
    private const val KEY_STOPPED_REPORT_AT = "shizuku_stopped_report_at"
    private const val KEY_STATE_SINCE = "shizuku_state_since"
    private const val STOPPED_REPORT_GAP_MS = 2 * 60 * 1000L
    /** Right after our process starts, the binder may not have arrived yet: wait this long before calling it "stopped". */
    private const val STARTUP_GRACE_MS = 30_000L
    private const val DEAD_DELAY_MS = 2_000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var app: Context? = null
    @Volatile private var installed = false

    private val onReceived = Shizuku.OnBinderReceivedListener {
        app?.let { c -> main.post { check(c, "binder mottatt") } }
    }
    private val onDead = Shizuku.OnBinderDeadListener {
        app?.let { c -> main.postDelayed({ check(c, "binder døde") }, DEAD_DELAY_MS) }
    }

    /** Registers the binder listeners once per process (safe to call often). */
    @Synchronized
    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        try {
            Shizuku.addBinderReceivedListenerSticky(onReceived, main)
            Shizuku.addBinderDeadListener(onDead, main)
        } catch (e: Throwable) {
            EventLog.error(ctx, "Shizuku-lytter kunne ikke registreres", e)
        }
    }

    private fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(MANAGER_PKG, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false } catch (_: Exception) { true }

    private fun processAgeMs(): Long = try {
        SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()
    } catch (_: Throwable) { Long.MAX_VALUE }

    /** Current state, without side effects. */
    fun current(ctx: Context): String {
        installed = isInstalled(ctx)
        val alive = try { Shizuku.pingBinder() } catch (_: Throwable) { return UNKNOWN }
        return when {
            alive -> RUNNING
            !installed -> NOT_INSTALLED
            processAgeMs() < STARTUP_GRACE_MS -> UNKNOWN
            else -> STOPPED
        }
    }

    /** Last state seen by [check] (what the dashboard report carries). */
    fun lastState(ctx: Context): String =
        LogStore.prefs(ctx).getString(KEY_STATE, null) ?: UNKNOWN

    /**
     * Checks Shizuku, logs state changes to the Log tab and, if it has stopped, forces a dashboard
     * report (at once on a change, then at most every [STOPPED_REPORT_GAP_MS]). Call on the main thread.
     */
    fun check(ctx0: Context, reason: String = "periodisk") {
        val ctx = ctx0.applicationContext
        init(ctx)
        val state = current(ctx)
        val p = LogStore.prefs(ctx)
        val prev = p.getString(KEY_STATE, null)
        val now = System.currentTimeMillis()
        if (state == UNKNOWN && processAgeMs() < STARTUP_GRACE_MS) {
            // Binder not received yet just after start: look again when the grace period is over
            main.postDelayed({ check(ctx, "etter oppstart") }, STARTUP_GRACE_MS - processAgeMs() + 1000)
            return
        }
        if (state != prev) {
            p.edit().putString(KEY_STATE, state).putLong(KEY_STATE_SINCE, now).apply()
            val msg = when (state) {
                RUNNING -> if (prev == STOPPED) "Shizuku kjører igjen" else "Shizuku kjører"
                STOPPED -> "Shizuku stoppet – varsler Asus"
                NOT_INSTALLED -> "Shizuku er ikke installert"
                else -> "Shizuku-status ukjent"
            }
            EventLog.add(ctx, EventLog.SHIZUKU, "$msg ($reason)")
        }
        if (AdbReporter.url(ctx).isEmpty()) return
        when {
            state == STOPPED && (state != prev || now - p.getLong(KEY_STOPPED_REPORT_AT, 0L) >= STOPPED_REPORT_GAP_MS) -> {
                p.edit().putLong(KEY_STOPPED_REPORT_AT, now).apply()
                if (state == prev) EventLog.add(ctx, EventLog.SHIZUKU, "Shizuku er fortsatt stoppet – varsler Asus igjen")
                AdbReporter.maybeReport(ctx, force = true)
            }
            // Back again (or first known state): report so the dashboard shows it (the report signature changed)
            state != prev && state != UNKNOWN -> AdbReporter.maybeReport(ctx, force = true)
        }
    }

    /** Short line for the settings screen. */
    fun statusLine(ctx: Context): String {
        val p = LogStore.prefs(ctx)
        val since = p.getLong(KEY_STATE_SINCE, 0L)
        val t = if (since > 0) " (since " + java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(since)) + ")" else ""
        return "Shizuku: " + when (lastState(ctx)) {
            RUNNING -> "running"; STOPPED -> "stopped – dashboard notified"; NOT_INSTALLED -> "not installed"; else -> "unknown"
        } + t
    }
}
