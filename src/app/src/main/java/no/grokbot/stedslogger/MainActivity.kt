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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val REQ_FOREGROUND = 1
        private const val REQ_BACKGROUND = 2
        private const val MAX_VISITS = 300
        private const val KEY_TAB = "ui_tab"
    }

    private lateinit var status: TextView
    private lateinit var lastCheck: TextView
    private lateinit var settingsStatus: TextView
    private lateinit var visits: android.widget.LinearLayout
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val minuteTick = object : Runnable {
        override fun run() { refresh(); uiHandler.postDelayed(this, 60_000L) }
    }
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
        settingsStatus = findViewById(R.id.settingsStatus)
        findViewById<android.view.View>(R.id.tabVisits).setOnClickListener { showTab(0) }
        findViewById<android.view.View>(R.id.tabSettings).setOnClickListener { showTab(1) }
        showTab(LogStore.prefs(this).getInt(KEY_TAB, 0))
        visits = findViewById(R.id.visits)
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
        uiHandler.removeCallbacks(minuteTick)
        uiHandler.post(minuteTick) // refreshes now and every minute (running duration)
    }

    override fun onPause() {
        LogStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        uiHandler.removeCallbacks(minuteTick)
        super.onPause()
    }

    // ---------- tabs ----------

    private var tab = 0

    private fun showTab(index: Int) {
        tab = if (index == 1) 1 else 0
        findViewById<android.view.View>(R.id.pageVisits).visibility = if (tab == 0) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.pageSettings).visibility = if (tab == 1) android.view.View.VISIBLE else android.view.View.GONE
        val on = 0xFF1565C0.toInt()
        val off = 0xFF757575.toInt()
        findViewById<TextView>(R.id.tabVisitsLabel).setTextColor(if (tab == 0) on else off)
        findViewById<TextView>(R.id.tabSettingsLabel).setTextColor(if (tab == 1) on else off)
        findViewById<android.view.View>(R.id.tabVisitsLine).visibility = if (tab == 0) android.view.View.VISIBLE else android.view.View.INVISIBLE
        findViewById<android.view.View>(R.id.tabSettingsLine).visibility = if (tab == 1) android.view.View.VISIBLE else android.view.View.INVISIBLE
        if (tab == 0) {
            currentFocus?.let {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0)
                it.clearFocus()
            }
        }
        LogStore.prefs(this).edit().putInt(KEY_TAB, tab).apply()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab == 1) showTab(0) else @Suppress("DEPRECATION") super.onBackPressed()
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
        status.text = if (running) "● Running – checking every $interval min" else "○ Stopped – interval $interval min"
        val warnings = listOfNotNull(
            if (!hasForegroundLocation()) "location not allowed" else null,
            if (LogStore.gpsOffBetween(this) && !GpsToggle.hasPermission(this)) "missing permission to control GPS" else null,
            if (!isIgnoringBattery()) "battery optimization on" else null,
        )
        if (warnings.isNotEmpty()) status.text = "${status.text}\n⚠ ${warnings.joinToString(", ")} (see Settings)"
        settingsStatus.text = (if (running) "● Running – checking every $interval min" else "○ Stopped – interval $interval min") +
            "\n" + gps + "\n" + perm +
            (if (isIgnoringBattery()) "\nBattery optimization: off" else "\nBattery optimization: on (may stop logging)")
        lastCheck.text = "Last check: " + (p.getString(LogStore.KEY_LAST_CHECK, null) ?: "–")
        btnBackground.isEnabled = !hasBackgroundLocation()
        btnBattery.isEnabled = !isIgnoringBattery()
        val nPlaces = Places.count(Places.text(this))
        btnPlaces.text = if (nPlaces == 0) "Places (merge addresses) …" else "Places (merge addresses): $nPlaces …"
        Thread {
            LogStore.deleteOldTextLogsOnce(this)
            val all = VisitStore.all(this)
            val cand = VisitStore.candidate(this)
            runOnUiThread { showVisits(all, cand, running) }
        }.start()
    }

    // ---------- visits ----------

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.ENGLISH)
    private val dayFmt = SimpleDateFormat("EEE d MMM yyyy", Locale.ENGLISH)
    private val dayKeyFmt = SimpleDateFormat("yyyyMMdd", Locale.ENGLISH)

    private fun dayLabel(t: Long): String {
        val now = System.currentTimeMillis()
        return when (dayKeyFmt.format(Date(t))) {
            dayKeyFmt.format(Date(now)) -> "Today · " + dayFmt.format(Date(t))
            dayKeyFmt.format(Date(now - 86_400_000L)) -> "Yesterday · " + dayFmt.format(Date(t))
            else -> dayFmt.format(Date(t))
        }
    }

    private fun sameDay(a: Long, b: Long) = dayKeyFmt.format(Date(a)) == dayKeyFmt.format(Date(b))

    private fun formatDuration(ms: Long): String {
        val totalMin = ms / 60_000L
        if (totalMin < 1) return "< 1 min"
        val d = totalMin / 1440
        val h = (totalMin % 1440) / 60
        val m = totalMin % 60
        return listOfNotNull(
            if (d > 0) "$d d" else null,
            if (h > 0) "$h h" else null,
            if (m > 0 || (d == 0L && h == 0L)) "$m min" else null,
        ).joinToString(" ")
    }

    private fun formatDistance(m: Float): String =
        if (m < 1000f) "${Math.round(m)} m" else String.format(Locale.ENGLISH, "%.1f km", m / 1000f)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showVisits(all: List<Visit>, cand: Pair<String, Long>?, running: Boolean) {
        visits.removeAllViews()
        val now = System.currentTimeMillis()
        if (cand != null && running) {
            visits.addView(TextView(this).apply {
                text = "Seen once: ${cand.first} at ${timeFmt.format(Date(cand.second))} – becomes a visit if confirmed at the next check"
                textSize = 12f
                setTextColor(0xFF757575.toInt())
                setPadding(0, dp(4), 0, dp(4))
            })
        }
        if (all.isEmpty()) {
            visits.addView(TextView(this).apply { text = "(no visits yet)"; setPadding(0, dp(8), 0, 0) })
            return
        }
        val first = (all.size - MAX_VISITS).coerceAtLeast(0)
        var lastDay: String? = null
        for (i in all.indices.reversed()) {
            if (i < first) break
            val v = all[i]
            val prev = if (i > 0) all[i - 1] else null
            val next = if (i + 1 < all.size) all[i + 1] else null
            val isCurrent = next == null

            val day = dayKeyFmt.format(Date(v.arrival))
            if (day != lastDay) {
                lastDay = day
                visits.addView(TextView(this).apply {
                    text = dayLabel(v.arrival)
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(0xFF1565C0.toInt())
                    setPadding(0, dp(12), 0, dp(2))
                })
            }

            val arrived = "arrived " + timeFmt.format(Date(v.arrival))
            val dep = v.departure
            fun at(t: Long) = if (sameDay(v.arrival, t)) timeFmt.format(Date(t)) else
                SimpleDateFormat("EEE d MMM HH:mm", Locale.ENGLISH).format(Date(t))
            val (times, duration) = when {
                isCurrent && running -> "$arrived – now" to "${formatDuration(now - v.arrival)} so far"
                dep != null && isCurrent -> "$arrived – last seen ${at(dep)} (logging stopped)" to formatDuration(dep - v.arrival)
                dep != null -> "$arrived – left ${at(dep)}" to formatDuration(dep - v.arrival)
                next != null -> "$arrived – left ?" to "up to ${formatDuration(next.arrival - v.arrival)}"
                else -> "$arrived – left ?" to null
            }
            val details = listOfNotNull(
                duration,
                v.travelledM?.takeIf { prev != null }?.let { "travelled ${formatDistance(it.toFloat())} from previous stop" },
            ).joinToString(" · ")

            visits.addView(android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(8), dp(6), 0, dp(6))
                addView(TextView(this@MainActivity).apply {
                    text = (if (isCurrent && running) "● " else "") + v.name
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextIsSelectable(true)
                })
                addView(TextView(this@MainActivity).apply { text = times; textSize = 14f })
                if (details.isNotEmpty()) addView(TextView(this@MainActivity).apply {
                    text = details
                    textSize = 13f
                    setTextColor(0xFF616161.toInt())
                })
            })
        }
        if (first > 0) visits.addView(TextView(this).apply {
            text = "($first older visits not shown)"
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        })
    }
}
