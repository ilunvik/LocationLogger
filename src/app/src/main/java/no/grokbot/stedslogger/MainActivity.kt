package no.grokbot.stedslogger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_FOREGROUND = 1
        private const val REQ_BACKGROUND = 2
        private const val MAX_LINES = 200
    }

    private lateinit var status: TextView
    private lateinit var lastCheck: TextView
    private lateinit var filePath: TextView
    private lateinit var log: TextView
    private lateinit var btnBackground: Button
    private lateinit var btnBattery: Button
    private lateinit var chkGpsOff: CheckBox
    private lateinit var editInterval: EditText

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        lastCheck = findViewById(R.id.lastCheck)
        filePath = findViewById(R.id.filePath)
        log = findViewById(R.id.log)
        btnBackground = findViewById(R.id.btnBackground)
        btnBattery = findViewById(R.id.btnBattery)

        findViewById<Button>(R.id.btnStart).setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            startService(android.content.Intent(this, LocationService::class.java).setAction(LocationService.ACTION_STOP))
            LogStore.prefs(this).edit().putBoolean(LogStore.KEY_RUNNING, false).apply()
            refresh()
        }
        findViewById<Button>(R.id.btnNow).setOnClickListener {
            if (!hasForegroundLocation()) { onStartClicked(); return@setOnClickListener }
            LocationService.start(this, LocationService.ACTION_CHECK_NOW)
            Toast.makeText(this, "Checking location …", Toast.LENGTH_SHORT).show()
        }
        btnBackground.setOnClickListener { askBackgroundLocation() }
        btnBattery.setOnClickListener { askIgnoreBatteryOptimizations() }

        chkGpsOff = findViewById(R.id.chkGpsOff)
        chkGpsOff.isChecked = LogStore.gpsOffBetween(this)
        chkGpsOff.setOnCheckedChangeListener { _, checked ->
            LogStore.prefs(this).edit().putBoolean(LogStore.KEY_GPS_OFF_BETWEEN, checked).apply()
        }
        editInterval = findViewById(R.id.editInterval)
        editInterval.setText(LogStore.intervalMin(this).toString())
        editInterval.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { applyInterval(); true } else false
        }
        editInterval.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) applyInterval() }
        findViewById<Button>(R.id.btnApplyInterval).setOnClickListener { applyInterval() }
        findViewById<Button>(R.id.btnMinus).setOnClickListener { setInterval(currentEdited() - 1) }
        findViewById<Button>(R.id.btnPlus).setOnClickListener { setInterval(currentEdited() + 1) }
    }

    override fun onResume() {
        super.onResume()
        LogStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        refresh()
    }

    override fun onPause() {
        LogStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    // ---------- settings ----------

    private fun currentEdited(): Int =
        editInterval.text.toString().trim().toIntOrNull() ?: LogStore.intervalMin(this)

    private fun applyInterval() = setInterval(currentEdited())

    private fun setInterval(value: Int) {
        val v = value.coerceIn(LogStore.MIN_INTERVAL_MIN, LogStore.MAX_INTERVAL_MIN)
        if (v != value) {
            Toast.makeText(this, "Must be between ${LogStore.MIN_INTERVAL_MIN} and ${LogStore.MAX_INTERVAL_MIN} minutes", Toast.LENGTH_SHORT).show()
        }
        editInterval.setText(v.toString())
        editInterval.setSelection(editInterval.text.length)
        val old = LogStore.intervalMin(this)
        if (v != old) {
            LogStore.prefs(this).edit().putInt(LogStore.KEY_INTERVAL_MIN, v).apply()
            Toast.makeText(this, "Now checking every $v min", Toast.LENGTH_SHORT).show()
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editInterval.windowToken, 0)
    }

    // ---------- permissions ----------

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun hasForegroundLocation() =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hasBackgroundLocation() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun isIgnoringBattery(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun onStartClicked() {
        val needed = mutableListOf<String>()
        if (!hasForegroundLocation()) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_FOREGROUND)
            return
        }
        startLogging()
        if (!hasBackgroundLocation()) askBackgroundLocation()
    }

    private fun startLogging() {
        if (!hasForegroundLocation()) return
        try {
            LocationService.start(this)
            LogStore.prefs(this).edit().putBoolean(LogStore.KEY_RUNNING, true).apply()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start: ${e.message}", Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    private fun askBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasBackgroundLocation()) {
            Toast.makeText(this, "Location is already allowed all the time", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasForegroundLocation()) { onStartClicked(); return }
        val label = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            packageManager.backgroundPermissionOptionLabel.toString() else "Allow all the time"
        AlertDialog.Builder(this)
            .setTitle("Background location")
            .setMessage(
                "For LocationLogger to check your address at the chosen interval even when the screen is off, " +
                    "choose \"$label\" on the next screen."
            )
            .setPositiveButton("Continue") { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BACKGROUND)
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    @SuppressLint("BatteryLife")
    private fun askIgnoreBatteryOptimizations() {
        if (isIgnoringBattery()) {
            Toast.makeText(this, "Battery optimization is already turned off", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_FOREGROUND -> {
                if (hasForegroundLocation()) {
                    startLogging()
                    if (!hasBackgroundLocation()) askBackgroundLocation()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Location needed")
                        .setMessage("LocationLogger needs location access to find your address. You can grant access in Settings.")
                        .setPositiveButton("Open settings") { _, _ -> openAppSettings() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            REQ_BACKGROUND -> {
                if (!hasBackgroundLocation()) {
                    AlertDialog.Builder(this)
                        .setTitle("Not allowed all the time")
                        .setMessage("Without \"Allow all the time\", Android may stop logging in the background and after a reboot. " +
                            "Open Settings › Permissions › Location and choose \"Allow all the time\".")
                        .setPositiveButton("Open settings") { _, _ -> openAppSettings() }
                        .setNegativeButton("Not now", null)
                        .show()
                }
                if (LogStore.prefs(this).getBoolean(LogStore.KEY_RUNNING, false)) startLogging()
            }
        }
        refresh()
    }

    // ---------- UI ----------

    private fun refresh() {
        val p = LogStore.prefs(this)
        val running = p.getBoolean(LogStore.KEY_RUNNING, false)
        val perm = when {
            !hasForegroundLocation() -> "Location: not allowed"
            !hasBackgroundLocation() -> "Location: only while the app is in use"
            else -> "Location: allowed all the time"
        }
        val interval = LogStore.intervalMin(this)
        val gps = when {
            !LogStore.gpsOffBetween(this) -> "GPS: not controlled by the app (must be turned on)"
            !GpsToggle.hasPermission(this) ->
                "GPS: Missing permission to control GPS (grant WRITE_SECURE_SETTINGS via adb) – only requesting location"
            else -> "GPS: turned on when needed, and restored after each check"
        }
        status.text = (if (running) "● Running – checking every $interval min" else "○ Stopped – interval $interval min") +
            "\n" + gps + "\n" + perm +
            (if (isIgnoringBattery()) "\nBattery optimization: off" else "\nBattery optimization: on (may stop logging)")
        lastCheck.text = "Last check: " + (p.getString(LogStore.KEY_LAST_CHECK, null) ?: "–")
        btnBackground.isEnabled = !hasBackgroundLocation()
        btnBattery.isEnabled = !isIgnoringBattery()
        filePath.text = "File: " + LogStore.describeLocation(this)
        Thread {
            val text = LogStore.readAll(this)
            val lines = text.lines().filter { it.isNotBlank() }.takeLast(MAX_LINES).reversed()
            runOnUiThread {
                log.text = if (lines.isEmpty()) "(no entries yet)" else lines.joinToString("\n")
            }
        }.start()
    }
}
