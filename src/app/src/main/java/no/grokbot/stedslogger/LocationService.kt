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
        try { AdbReporter.maybeReport(this) } catch (e: Exception) { EventLog.error(this, "adb-rapport feilet", e) }
    }

    // Network changes: report the wireless-debugging address right away so the dashboard host can react
    // (debounced a few seconds so a burst of callbacks becomes one report; only if a URL is set)
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var netReason = "nettverksendring"
    private val netReport = Runnable {
        EventLog.add(this, EventLog.NET, "$netReason – rapporterer adb-adressen")
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
            acquireWakeLock()
            startPassive()
            startNetWatch()
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
            checkLocation()
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

    private fun acquireWakeLock() {
        // Partial wake lock (CPU only, not GPS) so the check timer keeps running with the screen off (Doze).
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocationLogger::timer").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

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

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
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
    }

    private fun stopPassive() {
        passiveListener?.let { l ->
            try { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(l) } catch (_: Exception) {}
        }
        passiveListener = null
    }

    private fun checkLocation() {
        if (busy) { EventLog.add(this, EventLog.CHECK, "Hoppet over: forrige sjekk pågår fortsatt"); return }
        if (!hasLocationPermission()) { EventLog.error(this, "Sjekk hoppet over: mangler posisjonstillatelse"); return }
        busy = true
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

    private fun startFix(lm: LocationManager) {
        if (!running) { busy = false; gpsOffAfterFix(); releaseCheckWake(); return }
        val providers = availableProviders(lm)
        if (providers.isEmpty()) {
            endCheck(toggleNote ?: "Location (GPS) is turned off")
            return
        }
        requestFix(lm, providers, 0)
    }

    /** Elapsed-realtime age of a fix in ms (works even if the fix's wall-clock time is off). */
    private fun ageMs(l: Location) =
        (android.os.SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L

    /**
     * Asks all enabled providers (GPS, network, fused) at the same time. The first fix with an
     * accuracy of <= 100 m wins at once; otherwise the most accurate fix when all providers have
     * answered or [FIX_TIMEOUT_MS] has passed. Without any fix, a recent last-known / passive fix
     * (not older than the interval) is used and marked as such; else the real reason is shown.
     */
    @SuppressLint("MissingPermission")
    private fun requestFix(lm: LocationManager, providers: List<String>, @Suppress("UNUSED_PARAMETER") index: Int) {
        val results = LinkedHashMap<String, Location?>()
        val errors = LinkedHashMap<String, String>()
        var done = false

        fun finishWith(loc: Location?, note: String?) {
            if (done) return
            done = true
            cancelPending()
            if (note != null) toggleNote = listOfNotNull(toggleNote, note).joinToString(" · ")
            if (loc != null) { gpsOffAfterFix(); onLocation(loc); return }
            // No fresh fix: a recent last-known or passive fix
            val maxAge = maxOf(intervalMs(), MAX_LAST_KNOWN_AGE_MS)
            val candidates = (providers + LocationManager.PASSIVE_PROVIDER).mapNotNull {
                try { lm.getLastKnownLocation(it) } catch (_: Exception) { null }
            } + listOfNotNull(passiveLast)
            val last = candidates.filter { ageMs(it) in 0..maxAge }.minByOrNull { ageMs(it) }
            val detail = providers.joinToString(", ") { p ->
                "$p: " + (errors[p] ?: if (results.containsKey(p)) "no fix" else "no answer in ${FIX_TIMEOUT_MS / 1000} s")
            }
            if (last != null) {
                toggleNote = listOfNotNull(toggleNote,
                    "last known fix (${last.provider}, ${ageMs(last) / 60_000} min old); no new fix: $detail").joinToString(" · ")
                gpsOffAfterFix()
                onLocation(last)
            } else {
                val why = if (!hasBackgroundPermission())
                    " – location is allowed only while the app is in use; set it to \"Allow all the time\"" else ""
                endCheck("No location found ($detail)$why")
            }
        }

        fun onResult(p: String, loc: Location?) {
            if (done) return
            results[p] = loc
            if (loc != null && (!loc.hasAccuracy() || loc.accuracy <= VisitStore.MAX_ACCURACY_M)) {
                finishWith(loc, null); return
            }
            if (results.size >= providers.size) {
                finishWith(results.values.filterNotNull().minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }, null)
            }
        }

        val t = Runnable {
            if (!done) finishWith(results.values.filterNotNull()
                .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }, null)
        }
        pendingTimeout = t
        handler.postDelayed(t, FIX_TIMEOUT_MS)

        for (provider in providers) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val cancel = CancellationSignal()
                    pendingCancels += cancel
                    lm.getCurrentLocation(provider, cancel, mainExecutor) { loc -> handler.post { onResult(provider, loc) } }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) { onResult(provider, location) }
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                        override fun onProviderEnabled(p: String) {}
                        override fun onProviderDisabled(p: String) { onResult(provider, null) }
                    }
                    pendingListeners += listener
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            } catch (e: SecurityException) {
                errors[provider] = "permission denied"
                onResult(provider, null)
            } catch (e: Exception) {
                EventLog.error(this, "Posisjonsforespørsel feilet for $provider", e)
                errors[provider] = "error: ${e.javaClass.simpleName}"
                onResult(provider, null)
            }
        }
    }

    private fun onLocation(loc: Location) {
        EventLog.add(this, EventLog.FIX, String.format(Locale.ROOT, "%.6f, %.6f · %s · ±%s m · %d s gammel",
            loc.latitude, loc.longitude, loc.provider ?: "?",
            if (loc.hasAccuracy()) Math.round(loc.accuracy).toString() else "?", ageMs(loc) / 1000))
        if (!Geocoder.isPresent()) {
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
                        handler.post { onAddress(addresses.firstOrNull(), loc) }
                    }
                    override fun onError(errorMessage: String?) {
                        handler.post { onAddress(null, loc) }
                    }
                })
            } catch (e: Exception) {
                onAddress(null, loc)
            }
        } else {
            executor.execute {
                val a = try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                } catch (e: Exception) { null }
                handler.post { onAddress(a, loc) }
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
    private fun onAddress(a: Address?, loc: Location) {
        busy = false
        releaseCheckWake()
        val raw = a?.let { formatAddress(it) }
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
