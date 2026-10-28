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
    private lateinit var btnPlaces: Button
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
        btnPlaces = findViewById(R.id.btnPlaces)
        btnPlaces.setOnClickListener { editPlaces() }

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

    // ---------- place list ----------

    private fun editPlaces() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val lastRaw = LogStore.prefs(this).getString(LogStore.KEY_LAST_RAW_ADDRESS, null)
        val help = TextView(this).apply {
            text = "One place per line: Name;address 1;address 2;…\n" +
                "When the looked-up address matches one of the addresses (case and extra spaces are ignored), " +
                "the name is logged instead.\n" +
                "Example: Home;Example Street 1, 0000 Town;Other Road 2, 0000 Town" +
                (lastRaw?.let { "\n\nLast address from the phone:\n$it" } ?: "")
            setTextIsSelectable(true)
            textSize = 13f
        }
        val edit = EditText(this).apply {
            setText(Places.text(this@MainActivity))
            hint = "Home;Example Street 1, 0000 Town"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = false
            minLines = 4
            maxLines = 10
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setHorizontallyScrolling(false)
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(help)
            addView(edit)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Places")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                Places.save(this, edit.text.toString())
                val n = Places.count(Places.text(this))
                Toast.makeText(this, "Saved ($n ${if (n == 1) "place" else "places"})", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .create()
        if (lastRaw != null) {
            dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Insert last address") { _, _ -> }
        }
        dialog.show()
        // Keep the dialog open when inserting the last address
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            val t = edit.text
            val pos = edit.selectionStart.coerceAtLeast(0)
            val before = t.substring(0, pos)
            val sep = if (before.isEmpty() || before.endsWith("\n") || before.endsWith(";")) "" else ";"
            t.insert(pos, sep + (lastRaw ?: ""))
        }
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
        val nPlaces = Places.count(Places.text(this))
        btnPlaces.text = if (nPlaces == 0) "Places (merge addresses) …" else "Places (merge addresses): $nPlaces …"
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
