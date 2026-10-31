package no.grokbot.stedslogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Watches whether the Tailscale VPN is up on this phone (since 1.29) and tries to bring it back
 * when it is off (Tailscale does not always come up by itself after a reboot).
 *
 * Detection (no polling of its own – runs at the periodic checks, at service start and on network
 * changes): Tailscale is "running" when a VPN interface (ConnectivityManager TRANSPORT_VPN or `tun*`)
 * has an address in 100.64.0.0/10 – the same test the dashboard report uses for `tailscaleIp`.
 *
 * Restart: Tailscale's documented intent – an explicit broadcast `com.tailscale.ipn.CONNECT_VPN` to
 * `com.tailscale.ipn/.IPNReceiver`. Tailscale then starts the VPN from a WorkManager job without UI,
 * provided the VPN permission was granted earlier (otherwise Tailscale itself shows a notification).
 * At most one attempt per [RETRY_GAP_MS]; after [ATTEMPTS_BEFORE_NOTIFY] failed attempts a local
 * notification tells the user (tap opens Tailscale). Later attempts every [SLOW_RETRY_GAP_MS].
 *
 * States: "running", "stopped", "not_installed", "unknown" (grace period after boot / app start).
 */
object TailscaleWatch {
    const val RUNNING = "running"
    const val STOPPED = "stopped"
    const val NOT_INSTALLED = "not_installed"
    const val UNKNOWN = "unknown"

    const val PKG = "com.tailscale.ipn"
    private const val RECEIVER = "com.tailscale.ipn.IPNReceiver"
    private const val ACTION_CONNECT = "com.tailscale.ipn.CONNECT_VPN"

    const val KEY_KEEP_ON = "tailscale_keep_on"
    private const val KEY_STATE = "tailscale_state"
    private const val KEY_STATE_SINCE = "tailscale_state_since"
    private const val KEY_ATTEMPTS = "tailscale_attempts"
    private const val KEY_LAST_ATTEMPT = "tailscale_last_attempt"
    private const val KEY_NOTIFIED = "tailscale_notified"

    private const val RETRY_GAP_MS = 3 * 60 * 1000L
    private const val SLOW_RETRY_GAP_MS = 15 * 60 * 1000L
    private const val ATTEMPTS_BEFORE_NOTIFY = 2
    /** How long after boot Tailscale gets to come up by itself before we call it "stopped". */
    private const val BOOT_GRACE_MS = 90_000L
    private const val START_GRACE_MS = 30_000L
    /** After a start attempt, look again this long after (one delayed check, no polling). */
    private const val VERIFY_DELAY_MS = 20_000L

    private const val CHANNEL_ID = "tailscale_alert"
    private const val NOTIF_ID = 4201

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var graceCheckPosted = false

    fun keepOn(ctx: Context): Boolean = LogStore.prefs(ctx).getBoolean(KEY_KEEP_ON, true)
    fun setKeepOn(ctx: Context, on: Boolean) { LogStore.prefs(ctx).edit().putBoolean(KEY_KEEP_ON, on).apply() }

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false } catch (_: Exception) { true }

    private fun processAgeMs(): Long = try {
        SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()
    } catch (_: Throwable) { Long.MAX_VALUE }

    /** Milliseconds left of the grace period (0 = over). */
    private fun graceLeftMs(): Long =
        maxOf(BOOT_GRACE_MS - SystemClock.elapsedRealtime(), START_GRACE_MS - processAgeMs(), 0L)

    /** Current state, without side effects. Reads network interfaces (cheap, no I/O beyond that). */
    fun current(ctx: Context): String {
        val up = try { AdbReporter.ownIps(ctx).first != null } catch (_: Exception) { return UNKNOWN }
        return when {
            up -> RUNNING
            !isInstalled(ctx) -> NOT_INSTALLED
            graceLeftMs() > 0 -> UNKNOWN
            else -> STOPPED
        }
    }

    /** Last state seen by [check] (what the dashboard report carries). */
    fun lastState(ctx: Context): String = LogStore.prefs(ctx).getString(KEY_STATE, null) ?: UNKNOWN

    /**
     * Checks Tailscale, logs changes, tries to start it when stopped (rate limited), notifies the user
     * after repeated failures, and forces a dashboard report on a change. Call on the main thread.
     */
    fun check(ctx0: Context, reason: String = "periodisk") {
        val ctx = ctx0.applicationContext
        val state = current(ctx)
        val p = LogStore.prefs(ctx)
        val prev = p.getString(KEY_STATE, null)
        val now = System.currentTimeMillis()
        if (state == UNKNOWN && graceLeftMs() > 0) {
            if (!graceCheckPosted) {
                graceCheckPosted = true
                main.postDelayed({ graceCheckPosted = false; check(ctx, "etter oppstart") }, graceLeftMs() + 1000)
            }
            return
        }
        if (state != prev) {
            val e = p.edit().putString(KEY_STATE, state).putLong(KEY_STATE_SINCE, now)
            if (state != STOPPED) e.putInt(KEY_ATTEMPTS, 0).putLong(KEY_LAST_ATTEMPT, 0L).putBoolean(KEY_NOTIFIED, false)
            e.apply()
            val msg = when (state) {
                RUNNING -> if (prev == STOPPED) "Tailscale er oppe igjen" else "Tailscale kjører"
                STOPPED -> "Tailscale er av (ingen tailnet-adresse)"
                NOT_INSTALLED -> "Tailscale er ikke installert"
                else -> "Tailscale-status ukjent"
            }
            EventLog.add(ctx, EventLog.TAILSCALE, "$msg ($reason)")
            if (state == RUNNING) cancelNotification(ctx)
        }
        if (state == STOPPED && keepOn(ctx)) tryStart(ctx)
        // Report on every real change (back up again: at once, so the dashboard sees it)
        if (state != prev && state != UNKNOWN && AdbReporter.url(ctx).isNotEmpty()) {
            if (state == RUNNING && prev == STOPPED) {
                // The tunnel needs a moment before the dashboard is reachable through it
                main.postDelayed({ AdbReporter.maybeReport(ctx, force = true) }, 5000)
            } else AdbReporter.maybeReport(ctx, force = true)
        }
    }

    private fun tryStart(ctx: Context) {
        val p = LogStore.prefs(ctx)
        val now = System.currentTimeMillis()
        val attempts = p.getInt(KEY_ATTEMPTS, 0)
        val gap = if (attempts >= ATTEMPTS_BEFORE_NOTIFY) SLOW_RETRY_GAP_MS else RETRY_GAP_MS
        val last = p.getLong(KEY_LAST_ATTEMPT, 0L)
        if (attempts >= ATTEMPTS_BEFORE_NOTIFY && !p.getBoolean(KEY_NOTIFIED, false)) {
            notifyUser(ctx, attempts)
            p.edit().putBoolean(KEY_NOTIFIED, true).apply()
        }
        if (last in 1..now && now - last < gap) return
        val ok = try {
            ctx.sendBroadcast(Intent(ACTION_CONNECT)
                .setComponent(ComponentName(PKG, RECEIVER))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
            true
        } catch (e: Exception) { EventLog.error(ctx, "Kunne ikke sende start-signal til Tailscale", e); false }
        p.edit().putInt(KEY_ATTEMPTS, attempts + 1).putLong(KEY_LAST_ATTEMPT, now).apply()
        if (ok) EventLog.add(ctx, EventLog.TAILSCALE, "Ber Tailscale koble til (forsøk ${attempts + 1})")
        // One delayed look to see whether it came up (not a poll: a single check per attempt)
        main.postDelayed({ check(ctx, "etter startforsøk") }, VERIFY_DELAY_MS)
    }

    private fun notifyUser(ctx: Context, attempts: Int) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Tailscale off", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Shown when Tailscale is off and could not be started automatically"
                })
            }
            val open = ctx.packageManager.getLaunchIntentForPackage(PKG)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: Intent(ctx, MainActivity::class.java)
            val pi = PendingIntent.getActivity(ctx, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_location)
                .setContentTitle("Tailscale er av")
                .setContentText("Kom ikke opp etter $attempts forsøk – trykk for å åpne Tailscale")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, n)
            EventLog.add(ctx, EventLog.TAILSCALE, "Tailscale fortsatt av etter $attempts forsøk – varsel vist")
        } catch (e: Exception) { EventLog.error(ctx, "Tailscale-varsel feilet", e) }
    }

    private fun cancelNotification(ctx: Context) {
        try { ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) } catch (_: Exception) {}
    }

    /** Short line for the settings screen. */
    fun statusLine(ctx: Context): String {
        val p = LogStore.prefs(ctx)
        val since = p.getLong(KEY_STATE_SINCE, 0L)
        val t = if (since > 0) " (since " + java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(since)) + ")" else ""
        val att = p.getInt(KEY_ATTEMPTS, 0)
        return "Tailscale: " + when (lastState(ctx)) {
            RUNNING -> "running"
            STOPPED -> "off" + (if (keepOn(ctx)) " – start attempts: $att" else " – not restarted (setting off)")
            NOT_INSTALLED -> "not installed"
            else -> "unknown"
        } + t
    }
}
