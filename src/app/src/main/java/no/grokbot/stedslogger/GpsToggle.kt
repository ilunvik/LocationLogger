package no.grokbot.stedslogger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.util.Log

/**
 * Turns the system (master) location toggle on/off. Requires WRITE_SECURE_SETTINGS,
 * which can only be granted via adb:
 *   adb shell pm grant no.grokbot.stedslogger android.permission.WRITE_SECURE_SETTINGS
 */
object GpsToggle {
    private const val TAG = "LocationLogger"
    private const val MODE_HIGH_ACCURACY = 3 // Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
    private const val MODE_OFF = 0           // Settings.Secure.LOCATION_MODE_OFF

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isLocationOn(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /** Returns true if the write calls went through (the actual state may take a moment to change). */
    fun set(ctx: Context, on: Boolean): Boolean {
        if (!hasPermission(ctx)) return false
        val cr = ctx.contentResolver
        var ok = false
        // 1) Master toggle: Settings.Secure.LOCATION_MODE (3 = high accuracy / 0 = off).
        //    On Android 9+ LocationManagerService treats LOCATION_MODE as the source of truth for
        //    "location enabled", and the Quick Settings tile follows it.
        try {
            @Suppress("DEPRECATION")
            ok = Settings.Secure.putInt(cr, Settings.Secure.LOCATION_MODE, if (on) MODE_HIGH_ACCURACY else MODE_OFF)
        } catch (e: Exception) { Log.w(TAG, "LOCATION_MODE write failed", e) }
        // 2) Older Android (8.x): per-provider string LOCATION_PROVIDERS_ALLOWED (+gps,+network / -gps,-network).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            try {
                @Suppress("DEPRECATION")
                val key = Settings.Secure.LOCATION_PROVIDERS_ALLOWED
                val sign = if (on) "+" else "-"
                Settings.Secure.putString(cr, key, "${sign}gps")
                Settings.Secure.putString(cr, key, "${sign}network")
                ok = true
            } catch (e: Exception) { Log.w(TAG, "LOCATION_PROVIDERS_ALLOWED write failed", e) }
        }
        // 3) Android 9+: also try LocationManager.setLocationEnabledForUser (system API, via reflection).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isLocationOn(ctx) != on) {
            try {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val m = LocationManager::class.java.getMethod(
                    "setLocationEnabledForUser", Boolean::class.javaPrimitiveType, UserHandle::class.java
                )
                m.invoke(lm, on, Process.myUserHandle())
                ok = true
            } catch (e: Throwable) { Log.w(TAG, "setLocationEnabledForUser failed", e) }
        }
        return ok
    }
}
