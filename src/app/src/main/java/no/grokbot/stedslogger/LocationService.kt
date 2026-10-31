package no.grokbot.stedslogger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class LocationService : Service() {

    companion object {
        private const val NET_DEBOUNCE_MS = 4000L
        private const val TAG = "LocationLogger"
        const val ACTION_START = "no.grokbot.stedslogger.START"
        const val ACTION_STOP = "no.grokbot.stedslogger.STOP"
        const val ACTION_CHECK_NOW = "no.grokbot.stedslogger.CHECK_NOW"
        const val ACTION_ALARM = "no.grokbot.stedslogger.ALARM"
        /** Wall-clock time (ms) of the last periodic tick, kept across service restarts. */
        const val KEY_LAST_TICK_AT = "last_tick_at"
        /** A tick earlier than this before it is due is skipped (alarm and Handler may both fire). */
        private const val TICK_SLACK_MS = 30 * 1000L
        private const val CHECK_WAKE_MS = 90 * 1000L

        /** The running service (main thread only), so the alarm receiver can trigger a check in it. */
        @Volatile var instance: LocationService? = null
            private set
        private const val CHANNEL_ID = "stedslogger"
        private const val NOTIF_ID = 1
        private const val GPS_ON_WAIT_MS = 6 * 1000L
        private const val FIX_TIMEOUT_MS = 60 * 1000L
        private const val MAX_LAST_KNOWN_AGE_MS = 10 * 60 * 1000L

        // ---- Tiered location (1.28): reuse a cached fix, else Wi-Fi/cell, GPS only when needed ----
        /** Step 1: a cached fix (any app's) is reused if it is at most this old ... */
        const val REUSE_MAX_AGE_MS = 5 * 60 * 1000L
        /** ... and at least this accurate. */
        const val REUSE_MAX_ACC_M = 100f
        /** Step 2: single balanced-power (Wi-Fi/cell) fix; accepted if at least this accurate. */
        const val BALANCED_MAX_ACC_M = 100f
        const val BALANCED_TIMEOUT_MS = 20 * 1000L
        /** Step 3: high-accuracy (GPS) fix, never longer than this. */
        const val GPS_TIMEOUT_MS = 45 * 1000L
        /**
         * A fix coarser than this that would change the place (leave the current visit / new candidate)
         * is double-checked with GPS before it is used, so a Wi-Fi fix that lands on a neighbour's
         * address does not end a visit.
         */
        const val PRECISE_ACC_M = 30f
        /** A fused "current location" may be served from a cache this fresh. */
        private const val CURRENT_MAX_AGE_MS = 30 * 1000L
        private const val CACHED_WAIT_MS = 3 * 1000L

        /** Retry delay when the service could not go foreground (the alarm starts it again). */
        private const val RETRY_START_MS = 5 * 60_000L
        const val SRC_REUSED = "gjenbrukt"
        const val SRC_NETWORK = "nettverk"
        const val SRC_GPS = "GPS"

        private const val KEY_LAST_USED_FIX_TIME = "last_used_fix_time"
        const val KEY_GPS_DAY = "gps_use_day"
        const val KEY_GPS_COUNT = "gps_use_count"

        /** GPS (high-accuracy) requests made today, for Settings. */
        fun gpsUsesToday(ctx: Context): Int {
            val p = LogStore.prefs(ctx)
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
            return if (p.getString(KEY_GPS_DAY, null) == today) p.getInt(KEY_GPS_COUNT, 0) else 0
        }

        fun start(ctx: Context, action: String = ACTION_START) {
            val i = Intent(ctx, LocationService::class.java).setAction(action)
            ctx.startForegroundService(i)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var wakeLock: PowerManager.WakeLock? = null
    private var busy = false
    private var running = false
    private var lastTickAt = 0L

    // In-flight one-shot request (cancelled/removed as soon as the check finishes or times out)
    private val pendingCancels = mutableListOf<CancellationSignal>()
    private val pendingListeners = mutableListOf<LocationListener>()
    private var pendingTimeout: Runnable? = null

    // Passive listener while logging: remembers fixes other apps / the system obtain, as a fallback
    private var passiveListener: LocationListener? = null
    @Volatile private var passiveLast: Location? = null

    // True while we have switched the system location on for the current check
    private var gpsToggledForCheck = false
    private var toggleNote: String? = null

    private fun intervalMs() = LogStore.intervalMin(this) * 60 * 1000L

    /**
     * Periodic tick. Driven mainly by [CheckAlarm] (wakes the phone in Doze); the Handler is a
     * secondary path. Both go through the same wall-clock "due" test, so a check never runs twice.
     */
    private val tick = Runnable { onTick() }

    private fun onTick() {
        if (!running) return
        val now = System.currentTimeMillis()
        if (lastTickAt > 0 && now - lastTickAt < intervalMs() - TICK_SLACK_MS && now >= lastTickAt) {
            scheduleNext() // not due yet (the other path already ran this tick)
            return
        }
        lastTickAt = now
        LogStore.prefs(this).edit().putLong(KEY_LAST_TICK_AT, now).apply()
        EventLog.add(this, EventLog.CHECK, "Periodisk sjekk startet (hvert ${LogStore.intervalMin(this)}. min)")
        checkLocation()
        scheduleNext()
        try { ShizukuWatch.check(this) } catch (e: Exception) { EventLog.error(this, "Shizuku-sjekk feilet", e) }
        try { TailscaleWatch.check(this) } catch (e: Exception) { EventLog.error(this, "Tailscale-sjekk feilet", e) }
        try { AdbReporter.maybeReport(this) } catch (e: Exception) { EventLog.error(this, "adb-rapport feilet", e) }
    }

    // Network changes: report the wireless-debugging address right away so the dashboard host can react
    // (debounced a few seconds so a burst of callbacks becomes one report; only if a URL is set)
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var netReason = "nettverksendring"
    private val netReport = Runnable {
        EventLog.add(this, EventLog.NET, "$netReason – rapporterer adb-adressen")
        // 1.29: a network change is also when Tailscale goes up/down (cheap: reads interfaces only)
        try { TailscaleWatch.check(this, "nettverksendring") } catch (e: Exception) { EventLog.error(this, "Tailscale-sjekk feilet", e) }
        try { AdbReporter.maybeReport(this, force = true) } catch (e: Exception) { EventLog.error(this, "adb-rapport feilet", e) }
    }
    private fun scheduleNetReport(delayMs: Long = NET_DEBOUNCE_MS, reason: String = "Nettverksendring") {
        netReason = reason
        handler.removeCallbacks(netReport); handler.postDelayed(netReport, delayMs)
    }

    private fun startNetWatch() {
        if (netCallback != null) return
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            private var wasWifi: Boolean? = null
            override fun onAvailable(network: android.net.Network) = scheduleNetReport(reason = "Nettverk tilgjengelig")
            override fun onLost(network: android.net.Network) = scheduleNetReport(reason = "Nettverk mistet")
            override fun onLinkPropertiesChanged(network: android.net.Network, lp: android.net.LinkProperties) =
                scheduleNetReport(reason = "Nettverksadresser endret (${lp.interfaceName ?: "?"})")
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                // Only react when the default network switches between Wi-Fi and something else (not signal changes)
                val wifi = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                if (wasWifi != null && wasWifi != wifi) scheduleNetReport(reason = if (wifi) "Byttet til Wi-Fi" else "Byttet fra Wi-Fi")
                wasWifi = wifi
            }
        }
        try { cm.registerDefaultNetworkCallback(cb); netCallback = cb } catch (e: Exception) { EventLog.error(this, "Kunne ikke lytte på nettverksendringer", e) }
    }

    private fun stopNetWatch() {
        netCallback?.let { cb ->
            try { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        netCallback = null
    }

    /** Called by [CheckAlarmReceiver] on the main thread. */
    fun onAlarm() {
        holdCheckWake() // keep the CPU up while the tick starts (released when the check ends)
        handler.post { onTick(); if (!busy) releaseCheckWake() }
    }

    /** Next tick at lastTickAt + interval: an alarm (wakes the phone) plus a Handler backup. */
    private fun scheduleNext() {
        if (!running) return
        val at = lastTickAt + intervalMs()
        CheckAlarm.schedule(this, at)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, (at - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    /** Reschedule the next tick relative to the last one, using the current interval. */
    private fun reschedule() {
        if (!running) return
        handler.removeCallbacks(tick)
        val due = lastTickAt + intervalMs() - System.currentTimeMillis()
        if (due <= 0) handler.post(tick) else scheduleNext()
        updateNotification(statusText())
    }

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            LogStore.KEY_INTERVAL_MIN -> reschedule()
            LogStore.KEY_GPS_OFF_BETWEEN -> updateNotification(statusText())
        }
    }

    private fun statusText(): String {
        val gps = when {
            !LogStore.gpsOffBetween(this) -> "GPS not controlled by the app"
            !GpsToggle.hasPermission(this) -> "Missing permission to control GPS"
            else -> "GPS turned on when needed and restored"
        }
        val last = VisitStore.current(this)?.name
        return "Checking every ${LogStore.intervalMin(this)} min · $gps" + (last?.let { "\nAt: $it" } ?: "")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { EventLog.add(this, EventLog.SERVICE, "Logging stoppet"); stopEverything(); return START_NOT_STICKY }
        }
        if (!hasLocationPermission()) {
            EventLog.error(this, "Mangler posisjonstillatelse – tjenesten stopper")
            // Must still call startForeground quickly after startForegroundService
            try { goForeground("Missing location permission") } catch (_: Exception) {}
            stopEverything()
            return START_NOT_STICKY
        }
        try {
            goForeground(statusText())
        } catch (e: Exception) {
            EventLog.error(this, "startForeground feilet", e)
            // Keep the schedule alive: the next alarm tries again instead of logging stopping for good
            if (LogStore.prefs(this).getBoolean(LogStore.KEY_RUNNING, false)) {
                try { CheckAlarm.schedule(this, System.currentTimeMillis() + RETRY_START_MS) } catch (_: Exception) {}
            }
            stopSelf()
            return START_NOT_STICKY
        }
        LogStore.prefs(this).edit().putBoolean(LogStore.KEY_RUNNING, true).apply()
        if (!running) {
            EventLog.add(this, EventLog.SERVICE, "Tjenesten startet (" + when (intent?.action) {
                null -> "omstart av Android"
                ACTION_CHECK_NOW -> "Sjekk nå"
                ACTION_ALARM -> "alarm etter at tjenesten var drept"
                else -> "start"
            } + ", app ${appVersion()})")
            running = true
            instance = this
            executor.execute { try { LogStore.deleteOldTextLogsOnce(this) } catch (e: Exception) { Log.w(TAG, "text log cleanup failed", e) } }
            startPassive()
            startNetWatch()
            try { ShizukuWatch.init(this) } catch (e: Exception) { EventLog.error(this, "Shizuku-lytter feilet", e) }
            // 1.33: new incoming MMS (popup / read-aloud) – needs READ_SMS, otherwise a no-op
            try { MmsWatch.register(this) } catch (e: Exception) { EventLog.error(this, "MMS-lytter feilet", e) }
            try { TailscaleWatch.check(this, "tjenesten startet") } catch (e: Exception) { EventLog.error(this, "Tailscale-sjekk feilet", e) }
            scheduleNetReport() // report once on service start (boot, update, restart)
            LogStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
            // Continue the earlier schedule if the service was restarted (boot, update, killed)
            lastTickAt = LogStore.prefs(this).getLong(KEY_LAST_TICK_AT, 0L)
                .takeIf { it <= System.currentTimeMillis() } ?: 0L
            if (intent?.action == ACTION_CHECK_NOW) lastTickAt = 0L
            handler.post(tick)
        } else if (intent?.action == ACTION_CHECK_NOW) {
            EventLog.add(this, EventLog.CHECK, "Manuell sjekk (Sjekk nå)")
            holdCheckWake()
            checkLocation(manual = true)
        } else if (intent?.action == ACTION_ALARM) {
            onAlarm()
        }
        return START_STICKY
    }

    private fun stopEverything() {
        // A fresh start checks right away (a restart after boot/kill continues the schedule)
        LogStore.prefs(this).edit().putBoolean(LogStore.KEY_RUNNING, false).remove(KEY_LAST_TICK_AT).apply()
        running = false
        instance = null
        CheckAlarm.cancel(this)
        stopPassive()
        stopNetWatch()
        handler.removeCallbacksAndMessages(null)
        LogStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        cancelPending()
        gpsOffAfterFix()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        releaseCheckWake()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    override fun onDestroy() {
        if (running) EventLog.add(this, EventLog.SERVICE, "Tjenesten avsluttet av Android (onDestroy)")
        if (instance === this) instance = null
        // The alarm is kept if logging is still on: it restarts the service if Android killed it
        releaseCheckWake()
        stopPassive()
        stopNetWatch()
        handler.removeCallbacksAndMessages(null)
        LogStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        cancelPending()
        gpsOffAfterFix()
        wakeLock?.let { if (it.isHeld) it.release() }
        executor.shutdownNow()
        super.onDestroy()
    }

    // 1.28: no permanent wake lock any more. The wake-up alarm (CheckAlarm) drives the checks in Doze,
    // and each check holds a short wake lock with a timeout (released as soon as the check ends).

    // Short wake lock with a timeout for one check (fix + geocoding); harmless if the phone ignores it.
    private var checkWake: PowerManager.WakeLock? = null

    private fun holdCheckWake() {
        try {
            val wl = checkWake ?: (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationLogger::check")
                .apply { setReferenceCounted(false) }
            checkWake = wl
            wl.acquire(CHECK_WAKE_MS)
        } catch (e: Exception) { Log.w(TAG, "check wake lock failed", e) }
    }

    private fun releaseCheckWake() {
        try { checkWake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Location logging", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while LocationLogger is running"
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle("LocationLogger is running")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    /** Which foreground-service type the service runs as now ("location", "specialUse" or "" before Q). */
    private var fgsType = ""

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+: a "location" foreground service may only be started while the app is in
            // the foreground (while-in-use rule). Started from the alarm, boot or an app update it
            // throws SecurityException. Fall back to "specialUse": the service keeps running, and
            // location still works because the app has background location ("Allow all the time").
            try {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                if (fgsType != "location") Log.i(TAG, "foreground service type: location")
                fgsType = "location"
                return
            } catch (e: SecurityException) {
                if (fgsType != "specialUse") EventLog.add(this, EventLog.SERVICE,
                    "Startet fra bakgrunnen: kjører som forgrunnstjeneste av type specialUse (Android 14+ tillater ikke type location her; bakgrunnsposisjon brukes)")
            }
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            fgsType = "specialUse"
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            fgsType = "location"
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        if (!running) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    private fun now(pattern: String) = SimpleDateFormat(pattern, Locale.getDefault()).format(Date())

    private fun setLastCheck(msg: String) {
        val note = toggleNote?.takeIf { it != msg }?.let { " · $it" } ?: ""
        LogStore.prefs(this).edit().putString(LogStore.KEY_LAST_CHECK, "${now("HH:mm")}: $msg$note")
            .putLong(LogStore.KEY_LAST_CHECK_AT, System.currentTimeMillis()).apply()
        EventLog.add(this, EventLog.CHECK, "Resultat: $msg$note")
    }

    // ---------- location ----------

    private fun cancelPending() {
        pendingTimeout?.let { handler.removeCallbacks(it) }
        pendingTimeout = null
        pendingCancels.forEach { try { it.cancel() } catch (_: Exception) {} }
        pendingCancels.clear()
        fusedCancels.forEach { try { it.cancel() } catch (_: Exception) {} }
        fusedCancels.clear()
        stepTimeouts.forEach { handler.removeCallbacks(it) }
        stepTimeouts.clear()
        fixToken++
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        pendingListeners.forEach { l -> try { lm.removeUpdates(l) } catch (_: Exception) {} }
        pendingListeners.clear()
    }

    /** Turns system location off again only if it was off before this check (we turned it on). */
    private fun gpsOffAfterFix() {
        if (!gpsToggledForCheck) return
        gpsToggledForCheck = false
        if (GpsToggle.set(this, false)) EventLog.add(this, EventLog.CHECK, "GPS slått av igjen")
        else EventLog.error(this, "Kunne ikke slå av GPS igjen")
    }

    private fun endCheck(msg: String) {
        gpsOffAfterFix()
        busy = false
        setLastCheck(msg)
        releaseCheckWake()
    }

    private fun enabled(lm: LocationManager, p: String) =
        try { lm.allProviders.contains(p) && lm.isProviderEnabled(p) } catch (_: Exception) { false }

    private fun availableProviders(lm: LocationManager) = buildList {
        if (enabled(lm, LocationManager.GPS_PROVIDER) &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) add(LocationManager.GPS_PROVIDER)
        if (enabled(lm, LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && enabled(lm, LocationManager.FUSED_PROVIDER))
            add(LocationManager.FUSED_PROVIDER)
    }

    private fun hasBackgroundPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Starts a passive listener (no extra battery use) that remembers the newest fix any app gets. */
    @SuppressLint("MissingPermission")
    private fun startPassive() {
        if (passiveListener != null || !hasLocationPermission()) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!lm.allProviders.contains(LocationManager.PASSIVE_PROVIDER)) return
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) { passiveLast = location }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        try {
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 60_000L, 0f, l, Looper.getMainLooper())
            passiveListener = l
        } catch (e: Exception) { EventLog.error(this, "Passiv posisjonslytter feilet", e) }
        // Fused passive request (Play services): fixes Google / other apps get anyway, at no extra cost
        fusedClient()?.let { fc ->
            val cb = object : LocationCallback() {
                override fun onLocationResult(r: LocationResult) { r.lastLocation?.let { fusedPassiveLast = it } }
            }
            try {
                fc.requestLocationUpdates(
                    LocationRequest.Builder(Priority.PRIORITY_PASSIVE, 60_000L).setMinUpdateIntervalMillis(30_000L).build(),
                    cb, Looper.getMainLooper())
                fusedPassiveCb = cb
            } catch (e: Exception) { EventLog.error(this, "Passiv fused-lytter feilet", e) }
        }
        EventLog.add(this, EventLog.CHECK, "Posisjonskilde: " +
            (if (fusedClient() != null) "Google Play-tjenester (fused)" else "Android LocationManager (uten Play-tjenester)") +
            " · passiv lytter på")
    }

    private fun stopPassive() {
        passiveListener?.let { l ->
            try { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(l) } catch (_: Exception) {}
        }
        passiveListener = null
        fusedPassiveCb?.let { cb -> try { fused?.removeLocationUpdates(cb) } catch (_: Exception) {} }
        fusedPassiveCb = null
    }

    private fun checkLocation(manual: Boolean = false) {
        if (busy) { EventLog.add(this, EventLog.CHECK, "Hoppet over: forrige sjekk pågår fortsatt"); return }
        if (!hasLocationPermission()) { EventLog.error(this, "Sjekk hoppet over: mangler posisjonstillatelse"); return }
        busy = true
        manualCheck = manual
        holdCheckWake()
        toggleNote = null
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (LogStore.gpsOffBetween(this)) {
            if (!GpsToggle.hasPermission(this)) {
                // Fallback: old behaviour, just request a fix without toggling
                toggleNote = "Missing permission to control GPS (grant WRITE_SECURE_SETTINGS via adb)"
            } else {
                // 1) Remember whether system location was OFF before this check
                val wasOffBefore = !GpsToggle.isLocationOn(this)
                // 4) Only turn it OFF afterwards if it was OFF before (never override the user's own state)
                gpsToggledForCheck = wasOffBefore
                if (wasOffBefore) {
                    // 2) Turn it ON for this check
                    if (!GpsToggle.set(this, true)) { toggleNote = "Could not turn GPS on"; EventLog.error(this, "Kunne ikke slå på GPS") }
                    else EventLog.add(this, EventLog.CHECK, "GPS var av – slått på for sjekken")
                    waitForLocationOn(lm, System.currentTimeMillis())
                    return
                }
            }
        }
        startFix(lm)
    }

    /** Polls until the system reports location as enabled (max GPS_ON_WAIT_MS), then requests the fix. */
    private fun waitForLocationOn(lm: LocationManager, startedAt: Long) {
        if (GpsToggle.isLocationOn(this) && availableProviders(lm).isNotEmpty()) {
            handler.postDelayed({ startFix(lm) }, 500) // let providers settle
        } else if (System.currentTimeMillis() - startedAt > GPS_ON_WAIT_MS) {
            if (toggleNote == null) toggleNote = "GPS was not turned on (Android did not accept the change)"
            startFix(lm)
        } else {
            handler.postDelayed({ waitForLocationOn(lm, startedAt) }, 250)
        }
    }

    // ---------- tiered location (1.28) ----------

    private var fused: FusedLocationProviderClient? = null
    private var fusedChecked = false
    private var fusedPassiveCb: LocationCallback? = null
    @Volatile private var fusedPassiveLast: Location? = null
    private val fusedCancels = mutableListOf<CancellationTokenSource>()
    private val stepTimeouts = mutableListOf<Runnable>()
    /** Bumped by [cancelPending]; callbacks from an older check are ignored. */
    private var fixToken = 0
    private var manualCheck = false
    private var lastSource: String? = null

    /** Fused location client if Google Play services is available, else null (LocationManager is used). */
    private fun fusedClient(): FusedLocationProviderClient? {
        if (!fusedChecked) {
            fusedChecked = true
            fused = try {
                if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS)
                    LocationServices.getFusedLocationProviderClient(this) else null
            } catch (_: Throwable) { null }
        }
        return fused
    }

    private fun hasFine() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun acc(l: Location) = if (l.hasAccuracy()) l.accuracy else Float.MAX_VALUE

    private fun lastUsedFixTime() = LogStore.prefs(this).getLong(KEY_LAST_USED_FIX_TIME, 0L)

    /** A fix is only used once: a cached fix must be newer than the one used at the previous check. */
    private fun isNew(l: Location) = l.time > lastUsedFixTime()

    private fun markUsed(l: Location) {
        LogStore.prefs(this).edit().putLong(KEY_LAST_USED_FIX_TIME, maxOf(l.time, lastUsedFixTime())).apply()
    }

    private fun countGpsUse() {
        val p = LogStore.prefs(this)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        val n = (if (p.getString(KEY_GPS_DAY, null) == today) p.getInt(KEY_GPS_COUNT, 0) else 0) + 1
        p.edit().putString(KEY_GPS_DAY, today).putInt(KEY_GPS_COUNT, n).apply()
        EventLog.add(this, EventLog.CHECK, "GPS slått på for én posisjon (maks ${GPS_TIMEOUT_MS / 1000} s) – $n. gang i dag")
    }

    private fun startFix(lm: LocationManager) {
        if (!running) { busy = false; gpsOffAfterFix(); releaseCheckWake(); return }
        if (availableProviders(lm).isEmpty() && fusedClient() == null) {
            endCheck(toggleNote ?: "Location (GPS) is turned off")
            return
        }
        val token = fixToken
        // Step 1: reuse a fresh, accurate cached fix (free)
        collectCached(lm) { cached ->
            if (token != fixToken || !running) return@collectCached
            val reuse = cached.filter { ageMs(it) in 0..REUSE_MAX_AGE_MS && acc(it) <= REUSE_MAX_ACC_M && isNew(it) }
                .minByOrNull { ageMs(it) }
            if (reuse != null && !manualCheck) { finishFix(reuse, SRC_REUSED); return@collectCached }
            if (reuse == null) EventLog.add(this, EventLog.CHECK, "Ingen fersk lagret posisjon (≤ ${REUSE_MAX_AGE_MS / 60000} min, ±${REUSE_MAX_ACC_M.toInt()} m) – spør nettverk (Wi-Fi/mobil)")
            // Step 2: one balanced-power fix (Wi-Fi / cell)
            requestCurrent(highAccuracy = false) { net ->
                if (token != fixToken || !running) return@requestCurrent
                if (net != null && acc(net) <= BALANCED_MAX_ACC_M && isNew(net)) { finishFix(net, SRC_NETWORK); return@requestCurrent }
                val why = if (net == null) "ingen nettverksposisjon" else "nettverksposisjon for unøyaktig (±${Math.round(acc(net))} m)"
                if (!hasFine()) { fallback(lm, cached + listOfNotNull(net), "$why; GPS ikke tillatt (bare omtrentlig posisjon)"); return@requestCurrent }
                EventLog.add(this, EventLog.CHECK, "$why – prøver GPS")
                // Step 3: GPS, with a hard timeout
                requestCurrent(highAccuracy = true) { gps ->
                    if (token != fixToken || !running) return@requestCurrent
                    val best = listOfNotNull(gps, net).filter { isNew(it) }.minByOrNull { acc(it) }
                    when {
                        best != null && best === gps -> finishFix(gps, SRC_GPS)
                        best != null -> finishFix(best, SRC_NETWORK)
                        else -> fallback(lm, cached, "$why; ingen GPS-posisjon innen ${GPS_TIMEOUT_MS / 1000} s")
                    }
                }
            }
        }
    }

    private fun finishFix(loc: Location, source: String) {
        cancelPending()
        onLocation(loc, source) // system location is restored (gpsOffAfterFix) once the check is done
    }

    /** No good new fix: a recent cached one not used before (as before 1.28), else the check fails. */
    private fun fallback(lm: LocationManager, cached: List<Location>, detail: String) {
        cancelPending()
        val maxAge = maxOf(intervalMs(), MAX_LAST_KNOWN_AGE_MS)
        val all = cached + availableProviders(lm).plus(LocationManager.PASSIVE_PROVIDER).mapNotNull {
            try { lm.getLastKnownLocation(it) } catch (_: Exception) { null }
        } + listOfNotNull(passiveLast, fusedPassiveLast)
        val last = all.filter { ageMs(it) in 0..maxAge && isNew(it) }.minByOrNull { ageMs(it) }
        if (last != null) {
            toggleNote = listOfNotNull(toggleNote,
                "last known fix (${last.provider}, ${ageMs(last) / 60_000} min old); no new fix: $detail").joinToString(" · ")
            onLocation(last, SRC_REUSED)
        } else {
            val why = if (!hasBackgroundPermission())
                " – location is allowed only while the app is in use; set it to \"Allow all the time\"" else ""
            endCheck("No location found ($detail)$why")
        }
    }

    /** All cached fixes: fused last location, LocationManager last-known per provider, passive listeners. */
    @SuppressLint("MissingPermission")
    private fun collectCached(lm: LocationManager, done: (List<Location>) -> Unit) {
        val local = (availableProviders(lm) + LocationManager.PASSIVE_PROVIDER).distinct().mapNotNull {
            try { lm.getLastKnownLocation(it) } catch (_: Exception) { null }
        } + listOfNotNull(passiveLast, fusedPassiveLast)
        val fc = fusedClient()
        if (fc == null) { done(local); return }
        var finished = false
        val t = Runnable { if (!finished) { finished = true; done(local) } }
        stepTimeouts += t
        handler.postDelayed(t, CACHED_WAIT_MS)
        try {
            fc.lastLocation.addOnCompleteListener { task ->
                handler.post {
                    if (finished) return@post
                    finished = true
                    handler.removeCallbacks(t)
                    val l = if (task.isSuccessful) task.result else null
                    done(local + listOfNotNull(l))
                }
            }
        } catch (e: Exception) { handler.removeCallbacks(t); finished = true; done(local) }
    }

    /**
     * One current fix: balanced power (Wi-Fi/cell) or high accuracy (GPS), via fused if available,
     * else LocationManager NETWORK / GPS. Always ends within the step's timeout (null = no fix);
     * the request is cancelled then, so GPS is never left on.
     */
    @SuppressLint("MissingPermission")
    private fun requestCurrent(highAccuracy: Boolean, done: (Location?) -> Unit) {
        val timeout = if (highAccuracy) GPS_TIMEOUT_MS else BALANCED_TIMEOUT_MS
        if (highAccuracy) { countGpsUse(); holdCheckWake() }
        var finished = false
        val cancels = mutableListOf<() -> Unit>()
        fun finish(l: Location?) {
            if (finished) return
            finished = true
            cancels.forEach { try { it() } catch (_: Exception) {} }
            done(l)
        }
        val t = Runnable { finish(null) }
        stepTimeouts += t
        handler.postDelayed(t, timeout + 2_000L)
        val fc = fusedClient()
        try {
            if (fc != null) {
                val cts = CancellationTokenSource()
                fusedCancels += cts
                cancels += { cts.cancel() }
                val req = CurrentLocationRequest.Builder()
                    .setPriority(if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                    .setDurationMillis(timeout)
                    .setMaxUpdateAgeMillis(CURRENT_MAX_AGE_MS)
                    .build()
                fc.getCurrentLocation(req, cts.token).addOnCompleteListener { task ->
                    handler.post {
                        if (!task.isSuccessful && task.exception != null)
                            EventLog.error(this, "Fused-posisjon feilet (${if (highAccuracy) "GPS" else "nettverk"})", task.exception)
                        finish(if (task.isSuccessful) task.result else null)
                    }
                }
                return
            }
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val provider = when {
                highAccuracy && enabled(lm, LocationManager.GPS_PROVIDER) && hasFine() -> LocationManager.GPS_PROVIDER
                enabled(lm, LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && enabled(lm, LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                else -> null
            }
            if (provider == null || (highAccuracy && provider != LocationManager.GPS_PROVIDER)) { finish(null); return }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cancel = CancellationSignal()
                pendingCancels += cancel
                cancels += { cancel.cancel() }
                lm.getCurrentLocation(provider, cancel, mainExecutor) { loc -> handler.post { finish(loc) } }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) { finish(location) }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) { finish(null) }
                }
                pendingListeners += listener
                cancels += { lm.removeUpdates(listener) }
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            EventLog.error(this, "Posisjonsforespørsel nektet", e); finish(null)
        } catch (e: Exception) {
            EventLog.error(this, "Posisjonsforespørsel feilet", e); finish(null)
        }
    }

    /** Elapsed-realtime age of a fix in ms (works even if the fix's wall-clock time is off). */
    private fun ageMs(l: Location) =
        (android.os.SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L

    /**
     * True if [loc] is coarse (> [PRECISE_ACC_M]), not from GPS, and its [place] differs from the current
     * visit – i.e. it would start a candidate or a new visit, where precision matters.
     */
    private fun needsGpsConfirm(loc: Location, source: String, place: String): Boolean {
        if (source == SRC_GPS || !loc.hasAccuracy() || loc.accuracy <= PRECISE_ACC_M || !hasFine()) return false
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (fusedClient() == null && !enabled(lm, LocationManager.GPS_PROVIDER)) return false
        val cur = VisitStore.current(this)?.name ?: return false
        return !Places.same(Places.parse(Places.text(this)), cur, place)
    }

    private fun onLocation(loc: Location, source: String) {
        markUsed(loc)
        EventLog.add(this, EventLog.FIX, String.format(Locale.ROOT, "%s · ±%s m · %d s gammel · %.6f, %.6f · %s",
            source, if (loc.hasAccuracy()) Math.round(loc.accuracy).toString() else "?", ageMs(loc) / 1000,
            loc.latitude, loc.longitude, loc.provider ?: "?"))
        lastSource = source
        if (!Geocoder.isPresent()) {
            gpsOffAfterFix()
            setLastCheck("Geocoding is not available on this phone")
            busy = false
            releaseCheckWake()
            return
        }
        val geocoder = Geocoder(this, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                geocoder.getFromLocation(loc.latitude, loc.longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        handler.post { onAddress(addresses.firstOrNull(), loc, source) }
                    }
                    override fun onError(errorMessage: String?) {
                        handler.post { onAddress(null, loc, source) }
                    }
                })
            } catch (e: Exception) {
                onAddress(null, loc, source)
            }
        } else {
            executor.execute {
                val a = try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                } catch (e: Exception) { null }
                handler.post { onAddress(a, loc, source) }
            }
        }
    }

    private fun formatAddress(a: Address): String? {
        val street = listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ").trim()
        val city = listOfNotNull(a.postalCode, a.locality ?: a.subAdminArea).joinToString(" ").trim()
        val parts = listOf(street, city).filter { it.isNotEmpty() }
        if (street.isEmpty()) {
            // No street name: fall back to the full address line, if any
            val line = a.getAddressLine(0)?.trim()
            if (!line.isNullOrEmpty()) return line
        }
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }

    /**
     * Visit rule (since 1.7; see [VisitStore.onCheck]):
     * 1. The geocoded address is "corrected" with the place list ([Places]).
     * 2. A new place becomes a visit only when seen on two consecutive successful checks;
     *    its arrival is the first of those two checks.
     * 3. While you stay, each check moves the visit's departure forward.
     * 4. Travelled distance = sum of the legs between consecutive accurate fixes (<= 100 m) from
     *    the last fix at the previous stop to the first sighting of the new one, including
     *    unconfirmed candidates and fixes without an address.
     * Checks with no fix or no address are skipped. Visits are stored in the app
     * (visits.json).
     */
    private fun onAddress(a: Address?, loc: Location, source: String) {
        val raw = a?.let { formatAddress(it) }
        // 1.28: a coarse fix that would change the place is double-checked with GPS first
        if (raw != null && running && needsGpsConfirm(loc, source, Places.resolve(this, raw))) {
            val place = Places.resolve(this, raw)
            EventLog.add(this, EventLog.CHECK, "Mulig stedsbytte ($place) med grov posisjon ±${Math.round(loc.accuracy)} m ($source) – bekrefter med GPS")
            holdCheckWake()
            requestCurrent(highAccuracy = true) { g ->
                if (g != null && (!g.hasAccuracy() || g.accuracy < loc.accuracy)) onLocation(g, SRC_GPS)
                else {
                    EventLog.add(this, EventLog.CHECK, "GPS-bekreftelse ga ingen bedre posisjon – bruker $source-posisjonen")
                    applyAddress(raw, loc)
                }
            }
            return
        }
        applyAddress(raw, loc)
    }

    private fun applyAddress(raw: String?, loc: Location) {
        gpsOffAfterFix()
        busy = false
        releaseCheckWake()
        if (raw == null) {
            try { VisitStore.onFixOnly(this, loc) } catch (e: Exception) { EventLog.error(this, "Turoppdatering feilet", e) }
            setLastCheck("No address found (requires internet) – retrying in ${LogStore.intervalMin(this)} min")
            return
        }
        val p = LogStore.prefs(this)
        val place = Places.resolve(this, raw)
        val shown = if (place == raw) raw else "$place ($raw)"
        p.edit().putString(LogStore.KEY_LAST_RAW_ADDRESS, raw).apply()
        val outcome = try {
            VisitStore.onCheck(this, place, System.currentTimeMillis(), loc)
        } catch (e: Exception) {
            EventLog.error(this, "Kunne ikke lagre besøket", e)
            setLastCheck("Could not save the visit: ${e.message}")
            return
        }
        EventLog.add(this, EventLog.VISIT, when (outcome) {
            VisitStore.Outcome.SAME_PLACE -> "Fortsatt på: $shown"
            VisitStore.Outcome.CANDIDATE -> "Nytt sted sett én gang: $shown"
            VisitStore.Outcome.NEW_VISIT -> "Nytt besøk: $shown"
        })
        when (outcome) {
            VisitStore.Outcome.SAME_PLACE -> setLastCheck("Still at $shown")
            VisitStore.Outcome.CANDIDATE ->
                setLastCheck("New place seen once: $shown – becomes a visit if it is the same at the next check")
            VisitStore.Outcome.NEW_VISIT -> {
                setLastCheck("Arrived at $shown")
                updateNotification(statusText())
            }
        }
    }
}
