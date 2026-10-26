package com.example.locationlogger

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private var service: LocationLoggerService? = null
    private var bound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as LocationLoggerService.LocalBinder
            service = localBinder.getService()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
        }
    }

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        startButton = findViewById(R.id.start_button)
        stopButton = findViewById(R.id.stop_button)

        startButton.setOnClickListener { startLogging() }
        stopButton.setOnClickListener { stopLogging() }

        // Check and request permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    PERMISSION_REQUEST_CODE
                )
            }
        }

        // Check if location is enabled
        checkLocationEnabled()

        updateStatus()
    }

    private fun checkLocationEnabled() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isEnabled = locationManager.isLocationEnabled

        if (!isEnabled) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.location_disabled))
                .setMessage("Enable location services to track your address?")
                .setPositiveButton(getString(R.string.enable_location)) { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun startLogging() {
        // Check permissions again
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, LocationLoggerService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        statusText.text = "Status: " + getString(R.string.logging_started)
        startButton.isEnabled = false
        stopButton.isEnabled = true
        Toast.makeText(this, getString(R.string.logging_started), Toast.LENGTH_SHORT).show()
    }

    private fun stopLogging() {
        service?.stopLogging()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        stopService(Intent(this, LocationLoggerService::class.java))
        statusText.text = "Status: " + getString(R.string.logging_stopped)
        startButton.isEnabled = true
        stopButton.isEnabled = false
        Toast.makeText(this, getString(R.string.logging_stopped), Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus() {
        val isRunning = isServiceRunning(LocationLoggerService::class.java)
        startButton.isEnabled = !isRunning
        stopButton.isEnabled = isRunning
        statusText.text = "Status: " + if (isRunning) {
            getString(R.string.logging_started)
        } else {
            getString(R.string.logging_stopped)
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        super.onDestroy()
    }

    companion object {
        const val PERMISSION_REQUEST_CODE = 100
    }
}
