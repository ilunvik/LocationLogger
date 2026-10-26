package com.example.locationlogger

import android.app.Service
import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocationLoggerService : Service() {
    private val binder = LocalBinder()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var geocoder: Geocoder
    private var currentAddress: String? = null
    private val logFile by lazy { File(getExternalFilesDir(null), "location.txt") }
    
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return
            
            try {
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                val address = if (addresses != null && addresses.isNotEmpty()) {
                    formatAddress(addresses[0])
                } else {
                    "Coordinates: ${location.latitude}, ${location.longitude}"
                }
                
                // Only log if address changed
                if (address != currentAddress) {
                    currentAddress = address
                    logAddress(address)
                    Log.d(TAG, "Address changed: $address")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting address", e)
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): LocationLoggerService = this@LocationLoggerService
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = FusedLocationProviderClient(this)
        geocoder = Geocoder(this, Locale.getDefault())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startLocationUpdates()
        return START_STICKY
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5 * 60 * 1000).build()
        
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "Location updates started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission not granted", e)
        }
    }

    private fun formatAddress(address: Address): String {
        val parts = mutableListOf<String>()
        
        address.thoroughfare?.let { parts.add(it) }
        address.subThoroughfare?.let { parts.add(it) }
        address.locality?.let { parts.add(it) }
        address.adminArea?.let { parts.add(it) }
        address.postalCode?.let { parts.add(it) }
        address.countryName?.let { parts.add(it) }
        
        return parts.joinToString(", ")
    }

    private fun logAddress(address: String) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val logEntry = "[$timestamp] $address\n"
            
            logFile.appendText(logEntry)
            Log.d(TAG, "Logged: $logEntry")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to log file", e)
        }
    }

    fun stopLogging() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d(TAG, "Location updates stopped")
    }

    override fun onDestroy() {
        stopLogging()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        const val TAG = "LocationLogger"
    }
}
