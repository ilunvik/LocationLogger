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
        private const val TAG = "LocationLogger"
        const val ACTION_START = "no.grokbot.stedslogger.START"
        const val ACTION_STOP = "no.grokbot.stedslogger.STOP"
        const val ACTION_CHECK_NOW = "no.grokbot.stedslogger.CHECK_NOW"
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
    private var pendingCancel: CancellationSignal? = null
    private var pendingListener: LocationListener? = null
    private var pendingTimeout: Runnable? = null

    // True while we have switched the system location on for the current check
    private var gpsToggledForCheck = false
    private var toggleNote: String? = null

    private fun intervalMs() = LogStore.intervalMin(this) * 60 * 1000L

    private val tick = object : Runnable {
        override fun run() {
            lastTickAt = System.currentTimeMillis()
            checkLocation()
            handler.postDelayed(this, intervalMs())
        }
    }

    /** Reschedule the next tick relative to the last one, using the current interval. */
    private fun reschedule() {
        if (!running) return
        handler.removeCallbacks(tick)
        val due = lastTickAt + intervalMs() - System.currentTimeMillis()
        if (due <= 0) handler.post(tick) else handler.postDelayed(tick, due)
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
        val last = LogStore.prefs(this).getString(LogStore.KEY_LAST_ADDRESS, null)
        return "Checking every ${LogStore.intervalMin(this)} min · $gps" + (last?.let { "\nLast: $it" } ?: "")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
        }
        if (!hasLocationPermission()) {
            Log.w(TAG, "No location permission, stopping")
            // Must still call startForeground quickly after startForegroundService
            try { goForeground("Missing location permission") } catch (_: Exception) {}
            stopEverything()
            return START_NOT_STICKY
        }
        try {
            goForeground(statusText())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        LogStore.prefs(this).edit().putBoolean(LogStore.KEY_RUNNING, true).apply()
        if (!running) {
            running = true
            acquireWakeLock()
            LogStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
            handler.post(tick)
        } else if (intent?.action == ACTION_CHECK_NOW) {
            checkLocation()
        }
        return START_STICKY
    }

    private fun stopEverything() {
        LogStore.prefs(this).edit().putBoolean(LogStore.KEY_RUNNING, false).apply()
        running = false
        handler.removeCallbacksAndMessages(null)
        LogStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        cancelPending()
        gpsOffAfterFix()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
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
        LogStore.prefs(this).edit().putString(LogStore.KEY_LAST_CHECK, "${now("HH:mm")}: $msg$note").apply()
    }

    // ---------- location ----------

    private fun cancelPending() {
        pendingTimeout?.let { handler.removeCallbacks(it) }
        pendingTimeout = null
        pendingCancel?.cancel()
        pendingCancel = null
        pendingListener?.let { l ->
            try { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(l) } catch (_: Exception) {}
        }
        pendingListener = null
    }

    /** Turns system location off again only if it was off before this check (we turned it on). */
    private fun gpsOffAfterFix() {
        if (!gpsToggledForCheck) return
        gpsToggledForCheck = false
        if (!GpsToggle.set(this, false)) Log.w(TAG, "Could not turn location off")
    }

    private fun endCheck(msg: String) {
        gpsOffAfterFix()
        busy = false
        setLastCheck(msg)
    }

    private fun availableProviders(lm: LocationManager) = buildList {
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) add(LocationManager.GPS_PROVIDER)
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
    }

    private fun checkLocation() {
        if (busy || !hasLocationPermission()) return
        busy = true
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
                    if (!GpsToggle.set(this, true)) toggleNote = "Could not turn GPS on"
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
        if (!running) { busy = false; gpsOffAfterFix(); return }
        val providers = availableProviders(lm)
        if (providers.isEmpty()) {
            endCheck(toggleNote ?: "Location (GPS) is turned off")
            return
        }
        requestFix(lm, providers, 0)
    }

    @SuppressLint("MissingPermission")
    private fun requestFix(lm: LocationManager, providers: List<String>, index: Int) {
        if (index >= providers.size) {
            // Fallback: a recent last-known location
            val last = providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }
                .filter { System.currentTimeMillis() - it.time < MAX_LAST_KNOWN_AGE_MS }
                .maxByOrNull { it.time }
            if (last != null) { gpsOffAfterFix(); onLocation(last) } else endCheck("No location found")
            return
        }
        val provider = providers[index]
        var done = false
        val finish: (Location?) -> Unit = { loc ->
            if (!done) {
                done = true
                cancelPending() // release the one-shot request right away
                if (loc != null) { gpsOffAfterFix(); onLocation(loc) } else requestFix(lm, providers, index + 1)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cancel = CancellationSignal()
                pendingCancel = cancel
                val t = Runnable { if (!done) finish(null) }
                pendingTimeout = t
                handler.postDelayed(t, FIX_TIMEOUT_MS)
                lm.getCurrentLocation(provider, cancel, mainExecutor) { loc -> handler.post { finish(loc) } }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        finish(location)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                pendingListener = listener
                val t = Runnable { if (!done) finish(null) }
                pendingTimeout = t
                handler.postDelayed(t, FIX_TIMEOUT_MS)
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            Log.w(TAG, "location request failed for $provider", e)
            finish(null)
        }
    }

    private fun onLocation(loc: Location) {
        if (!Geocoder.isPresent()) {
            setLastCheck("Geocoding is not available on this phone")
            busy = false
            return
        }
        val geocoder = Geocoder(this, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                geocoder.getFromLocation(loc.latitude, loc.longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        handler.post { onAddress(addresses.firstOrNull()) }
                    }
                    override fun onError(errorMessage: String?) {
                        handler.post { onAddress(null) }
                    }
                })
            } catch (e: Exception) {
                onAddress(null)
            }
        } else {
            executor.execute {
                val a = try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                } catch (e: Exception) { null }
                handler.post { onAddress(a) }
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

    private fun onAddress(a: Address?) {
        busy = false
        val address = a?.let { formatAddress(it) }
        if (address == null) {
            setLastCheck("No address found (requires internet) – retrying in ${LogStore.intervalMin(this)} min")
            return
        }
        val p = LogStore.prefs(this)
        val last = p.getString(LogStore.KEY_LAST_ADDRESS, null)
        if (address == last) {
            setLastCheck("Same address ($address)")
            return
        }
        val line = "${now("yyyy-MM-dd HH:mm")} | $address"
        if (LogStore.append(this, line)) {
            p.edit().putString(LogStore.KEY_LAST_ADDRESS, address).apply()
            setLastCheck("New address saved: $address")
            updateNotification(statusText())
        } else {
            setLastCheck("Could not write to ${LogStore.FILE_NAME}")
        }
    }
}
