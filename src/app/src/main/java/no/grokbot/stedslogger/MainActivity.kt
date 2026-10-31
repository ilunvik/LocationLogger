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
        private const val REQ_PICK_IMAGE = 3
        private const val REQ_ICEBOX = 4
        private const val REQ_COMMS = 5
        private const val REQ_REC_AUDIO = 6
        const val EXTRA_TAB = "tab"
        const val TAB_COMMS = 3
        private const val STATE_PICK_ID = "pick_slot_id"
        private const val STATE_PICK_LABEL = "pick_slot_label"
        private const val MAX_VISITS = 300
        private const val KEY_TAB = "ui_tab"
        /** A manual check gives up waiting for a result after this long (two 60 s fix attempts + GPS on). */
        private const val MANUAL_TIMEOUT_MS = 3 * 60_000L
        /** How long the result of a manual check stays in the status line. */
        private const val MANUAL_RESULT_MS = 2 * 60_000L
        private const val STATE_MANUAL_START = "manual_start"
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
    private lateinit var editPlaces: EditText
    private lateinit var placesSaved: TextView
    private lateinit var btnInsertLast: Button
    private var placesJustSaved = false
    /** Slot waiting for the photo picker result. */
    private var pickSlot: StreetView.Slot? = null
    private lateinit var chkGpsOff: CheckBox
    private lateinit var editInterval: EditText

    /** Start time of the last "Check now" (0 = none); its progress/result is shown in the status line. */
    private var manualStart = 0L
    private var manualError: String? = null
    private val manualExpire = Runnable { refresh() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        lastCheck = findViewById(R.id.lastCheck)
        settingsStatus = findViewById(R.id.settingsStatus)
        findViewById<android.view.View>(R.id.tabVisits).setOnClickListener {
            if (tab != 1 || confirmLeavePlaces { showTab(0) }) showTab(0)
        }
        findViewById<android.view.View>(R.id.tabSettings).setOnClickListener { showTab(1) }
        findViewById<android.view.View>(R.id.tabLog).setOnClickListener {
            if (tab != 1 || confirmLeavePlaces { showTab(2) }) showTab(2)
        }
        findViewById<android.view.View>(R.id.tabComms).setOnClickListener {
            if (tab != 1 || confirmLeavePlaces { showTab(TAB_COMMS) }) showTab(TAB_COMMS)
        }
        logTab = LogTab(this)
        commsTab = CommsTab(this) { askCommPermissions() }
        showTab(intent?.getIntExtra(EXTRA_TAB, -1)?.takeIf { it >= 0 } ?: LogStore.prefs(this).getInt(KEY_TAB, 0))
        visits = findViewById(R.id.visits)
        btnBackground = findViewById(R.id.btnBackground)
        btnBattery = findViewById(R.id.btnBattery)
        manualStart = savedInstanceState?.getLong(STATE_MANUAL_START, 0L) ?: 0L
        savedInstanceState?.getString(STATE_PICK_ID)?.let { id ->
            pickSlot = StreetView.Slot(id, savedInstanceState.getString(STATE_PICK_LABEL) ?: "")
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            startService(android.content.Intent(this, LocationService::class.java).setAction(LocationService.ACTION_STOP))
            LogStore.prefs(this).edit().putBoolean(LogStore.KEY_RUNNING, false).apply()
            refresh()
        }
        findViewById<Button>(R.id.btnNow).setOnClickListener {
            if (!hasForegroundLocation()) { onStartClicked(); return@setOnClickListener }
            manualStart = System.currentTimeMillis()
            manualError = try {
                LocationService.start(this, LocationService.ACTION_CHECK_NOW); null
            } catch (e: Exception) { "could not start the check: ${e.message}" }
            refresh()
        }
        btnBackground.setOnClickListener { askBackgroundLocation() }
        btnBattery.setOnClickListener { askIgnoreBatteryOptimizations() }
        setupPlaces(savedInstanceState != null)
        setupStreetView()
        setupDashboard()
        setupComms()
        updatePlaceThumbs()

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
        if (tab == 2) logTab.load()
        if (tab == TAB_COMMS) commsTab.load()
        try { MmsWatch.register(this) } catch (_: Exception) {} // 1.33: no-op until READ_SMS is granted
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val t = intent.getIntExtra(EXTRA_TAB, -1)
        if (t >= 0 && (tab != 1 || !placesDirty())) showTab(t)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_MANUAL_START, manualStart)
        pickSlot?.let { outState.putString(STATE_PICK_ID, it.id); outState.putString(STATE_PICK_LABEL, it.label) }
    }

    /**
     * Line for the status while a manual check runs ("Checking now …") and, for
     * [MANUAL_RESULT_MS] afterwards, its result (the service's last-check text). Null when idle.
     */
    private fun manualLine(p: SharedPreferences): String? {
        if (manualStart == 0L) return null
        val now = System.currentTimeMillis()
        uiHandler.removeCallbacks(manualExpire)
        manualError?.let { err ->
            if (now - manualStart < MANUAL_RESULT_MS) {
                uiHandler.postDelayed(manualExpire, manualStart + MANUAL_RESULT_MS - now)
                return "Check now: $err"
            }
            manualStart = 0L; manualError = null; return null
        }
        val doneAt = p.getLong(LogStore.KEY_LAST_CHECK_AT, 0L)
        if (doneAt >= manualStart) {
            if (now - doneAt < MANUAL_RESULT_MS) {
                uiHandler.postDelayed(manualExpire, doneAt + MANUAL_RESULT_MS - now)
                val res = p.getString(LogStore.KEY_LAST_CHECK, null) ?: "done"
                return "Check now – $res"
            }
            manualStart = 0L; return null
        }
        if (now - manualStart < MANUAL_TIMEOUT_MS) {
            uiHandler.postDelayed(manualExpire, manualStart + MANUAL_TIMEOUT_MS - now)
            return "Checking now …"
        }
        if (now - manualStart < MANUAL_TIMEOUT_MS + MANUAL_RESULT_MS) {
            uiHandler.postDelayed(manualExpire, manualStart + MANUAL_TIMEOUT_MS + MANUAL_RESULT_MS - now)
            return "Check now: no result after ${MANUAL_TIMEOUT_MS / 60_000} min"
        }
        manualStart = 0L; return null
    }

    override fun onPause() {
        LogStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        uiHandler.removeCallbacks(minuteTick)
        uiHandler.removeCallbacks(manualExpire)
        super.onPause()
    }

    // ---------- tabs ----------

    private var tab = 0
    private lateinit var logTab: LogTab
    private lateinit var commsTab: CommsTab

    private fun showTab(index: Int) {
        tab = if (index in 0..3) index else 0
        val pages = listOf(R.id.pageVisits, R.id.pageSettings, R.id.pageLog, R.id.pageComms)
        val labels = listOf(R.id.tabVisitsLabel, R.id.tabSettingsLabel, R.id.tabLogLabel, R.id.tabCommsLabel)
        val lines = listOf(R.id.tabVisitsLine, R.id.tabSettingsLine, R.id.tabLogLine, R.id.tabCommsLine)
        val on = 0xFF1565C0.toInt()
        val off = 0xFF757575.toInt()
        for (i in 0..3) {
            findViewById<android.view.View>(pages[i]).visibility = if (tab == i) android.view.View.VISIBLE else android.view.View.GONE
            findViewById<TextView>(labels[i]).setTextColor(if (tab == i) on else off)
            findViewById<android.view.View>(lines[i]).visibility = if (tab == i) android.view.View.VISIBLE else android.view.View.INVISIBLE
        }
        if (tab == 2) logTab.load()
        if (tab == TAB_COMMS) commsTab.load()
        if (tab != 1) {
            currentFocus?.let {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0)
                it.clearFocus()
            }
        }
        LogStore.prefs(this).edit().putInt(KEY_TAB, tab).apply()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab == 1) { if (confirmLeavePlaces { showTab(0) }) showTab(0) }
        else if (tab == 2 || tab == TAB_COMMS) showTab(0)
        else @Suppress("DEPRECATION") super.onBackPressed()
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

    private fun setupPlaces(restored: Boolean) {
        editPlaces = findViewById(R.id.editPlaces)
        placesSaved = findViewById(R.id.placesSaved)
        if (!restored) editPlaces.setText(Places.text(this)) // otherwise the EditText restores its own text
        editPlaces.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updatePlacesState() }
        })
        findViewById<Button>(R.id.btnSavePlaces).setOnClickListener { savePlaces() }
        btnInsertLast = findViewById(R.id.btnInsertLast)
        btnInsertLast.setOnClickListener {
            val lastRaw = LogStore.prefs(this).getString(LogStore.KEY_LAST_RAW_ADDRESS, null) ?: return@setOnClickListener
            val t = editPlaces.text
            val pos = editPlaces.selectionStart.coerceAtLeast(0)
            val before = t.substring(0, pos)
            val sep = if (before.isEmpty() || before.endsWith("\n") || before.endsWith(";")) "" else ";"
            t.insert(pos, sep + lastRaw)
            editPlaces.requestFocus()
        }
        updatePlacesState()
    }

    private fun setupStreetView() {
        val sha1 = StreetView.certSha1Display(this) ?: "(could not read)"
        findViewById<TextView>(R.id.streetViewHelp).text =
            "Shows a Street View image on each stop with coordinates; tap it to open Street View there in the Google Maps app " +
            "(defrosted with Ice Box first if it is frozen; a browser is used if Maps cannot be opened). " +
            "Needs your own Google API key (stored only on this phone):\n" +
            "1. Google Cloud Console → create a project (billing required)\n" +
            "2. Enable \"Street View Static API\"\n" +
            "3. Credentials → Create API key\n" +
            "4. Restrict it to Android apps: package $packageName, SHA-1 $sha1\n" +
            "5. Restrict the API to Street View Static API"
        showBrowserStatus()
        findViewById<Button>(R.id.btnBrowser).setOnClickListener {
            BrowserOpener.pick(this, auto = true) { showBrowserStatus() }
        }
        val edit = findViewById<EditText>(R.id.editApiKey)
        val keyStatus = findViewById<TextView>(R.id.apiKeyStatus)
        fun show() { keyStatus.text = if (StreetView.apiKey(this) != null) "Key saved" else "No key" }
        show()
        findViewById<Button>(R.id.btnSaveApiKey).setOnClickListener {
            val k = edit.text.toString()
            if (k.isBlank() && StreetView.apiKey(this) != null) {
                AlertDialog.Builder(this)
                    .setMessage("Remove the saved API key?")
                    .setPositiveButton("Remove") { _, _ -> StreetView.saveKey(this, ""); show(); refresh() }
                    .setNegativeButton("Cancel", null)
                    .show()
                return@setOnClickListener
            }
            StreetView.saveKey(this, k)
            edit.setText("")
            edit.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(edit.windowToken, 0)
            show()
            refresh()
        }
    }

    private fun setupDashboard() {
        val keep = findViewById<android.widget.CheckBox>(R.id.checkKeepAdbWifi)
        keep.isChecked = AdbReporter.keepWifiOn(this)
        keep.setOnCheckedChangeListener { _, on ->
            AdbReporter.setKeepWifiOn(this, on)
            if (on) Thread { AdbReporter.ensureWifiDebugging(this); runOnUiThread { refresh() } }.start() else refresh()
        }
        val keepTs = findViewById<android.widget.CheckBox>(R.id.checkKeepTailscale)
        keepTs.isChecked = TailscaleWatch.keepOn(this)
        keepTs.setOnCheckedChangeListener { _, on ->
            TailscaleWatch.setKeepOn(this, on)
            if (on) TailscaleWatch.check(this, "innstilling slått på")
            refresh()
        }
        val ipEdit = findViewById<EditText>(R.id.editDashboardIp)
        ipEdit.setText(AdbReporter.fallbackIp(this))
        findViewById<Button>(R.id.btnSaveDashboardIp).setOnClickListener {
            val ip = ipEdit.text.toString().trim()
            if (ip.isNotEmpty() && !AdbReporter.isIpLiteral(ip)) {
                Toast.makeText(this, "Enter an IP address (not a host name)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AdbReporter.saveFallbackIp(this, ip)
            EventLog.add(this, EventLog.ADB, if (ip.isEmpty()) "Dashbord-IP (reserve) fjernet" else "Dashbord-IP (reserve) satt til $ip")
            ipEdit.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(ipEdit.windowToken, 0)
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            refresh()
        }
        val edit = findViewById<EditText>(R.id.editDashboardUrl)
        edit.setText(AdbReporter.url(this))
        findViewById<Button>(R.id.btnSaveDashboardUrl).setOnClickListener {
            val u = edit.text.toString().trim()
            if (u.isNotEmpty() && !(u.startsWith("https://") || u.startsWith("http://"))) {
                Toast.makeText(this, "The URL must start with https://", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AdbReporter.saveUrl(this, u)
            edit.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(edit.windowToken, 0)
            if (u.isNotEmpty()) {
                findViewById<TextView>(R.id.dashboardIpStatus).text = run {
            val host = try { Uri.parse(AdbReporter.url(this)).host } catch (_: Exception) { null }
            val learned = host?.let { AdbReporter.learnedIp(this, it) }
            (if (AdbReporter.fallbackIp(this).isEmpty()) "Fallback IP: not set" else "Fallback IP: ${AdbReporter.fallbackIp(this)}") +
                "\nLearned IP: " + (learned ?: "none yet (learned after the next successful report)")
        }
        findViewById<TextView>(R.id.dashboardStatus).text = "Saved – reporting…"
                AdbReporter.maybeReport(this, force = true)
                edit.postDelayed({ refresh() }, 9000)
            } else refresh()
        }
    }

    /** Image slot of a stop: its Place if the name is in the place list, else its rounded coordinates. */
    private fun slotFor(name: String, lat: Double, lon: Double, places: Map<String, String>): StreetView.Slot =
        Places.placeName(places, name)?.let { StreetView.placeSlot(it) } ?: StreetView.spotSlot(lat, lon)

    /**
     * Square image (96 dp, rounded, right of the stop's text) for a stop: the Place's / spot's stored image, or Street View.
     * Tap opens Street View in the Google Maps app (via Ice Box if frozen; browser as fallback); long-press opens the image menu.
     */
    private fun streetViewImage(slot: StreetView.Slot, lat: Double, lon: Double): android.view.View? {
        if (StreetView.isHidden(this, slot)) return null
        if (StreetView.apiKey(this) == null && !StreetView.hasStored(this, slot)) return null
        if (StreetView.knownNone(slot)) return null
        val img = android.widget.ImageView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                dp(96), dp(96)
            ).apply { marginStart = dp(8) }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFFE0E0E0.toInt())
            val r = dp(8).toFloat()
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, r)
            }
            clipToOutline = true
            contentDescription = "Street View – open in Google Maps"
            tag = slot.id
            setOnClickListener {
                // 1.31: Street View in the Google Maps app (defrosted by Ice Box if frozen); browser as fallback
                BrowserOpener.openStreetView(this@MainActivity, lat, lon, StreetView.heading(this@MainActivity, slot),
                    StreetView.panoUrl(this@MainActivity, slot, lat, lon), REQ_ICEBOX)
            }
            setOnLongClickListener { imageMenu(slot, lat, lon); true }
        }
        val mem = StreetView.cached(slot)
        if (mem != null) { img.setImageBitmap(mem); return img }
        StreetView.load(this, slot, lat, lon) { bmp ->
            // The row may have been rebuilt meanwhile: only touch this view if it still shows this slot
            if (img.tag != slot.id) return@load
            if (bmp != null) img.setImageBitmap(bmp) else img.visibility = android.view.View.GONE
        }
        return img
    }

    /** Long-press menu for a stop's image (also on the stop row, so a removed image can come back). */
    private fun imageMenu(slot: StreetView.Slot, lat: Double, lon: Double) {
        val done: (String?) -> Unit = { err ->
            if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            refresh()
            updatePlaceThumbs()
        }
        val isPlace = slot.id.startsWith("p_")
        AlertDialog.Builder(this)
            .setTitle(if (isPlace) "Image for ${slot.label}" else "Image for this spot")
            .setItems(arrayOf("Choose photo…", "Other Street View angle", "Use Street View from this stop", "Remove image")) { _, which ->
                when (which) {
                    0 -> {
                        pickSlot = slot
                        try {
                            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                                .addCategory(Intent.CATEGORY_OPENABLE), REQ_PICK_IMAGE)
                        } catch (e: Exception) { Toast.makeText(this, "No app to pick a photo", Toast.LENGTH_SHORT).show() }
                    }
                    1 -> StreetView.nextAngle(this, slot, lat, lon, done)
                    2 -> StreetView.fromStop(this, slot, lat, lon, done)
                    3 -> StreetView.remove(this, slot, done)
                }
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_IMAGE) return
        val slot = pickSlot ?: return
        pickSlot = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        StreetView.fromPhoto(this, slot, uri) { err ->
            if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            refresh()
            updatePlaceThumbs()
        }
    }

    /** Small thumbnails of the Places that have a stored image, under the place list in Settings. */
    private fun updatePlaceThumbs() {
        val box = findViewById<android.widget.LinearLayout>(R.id.placeThumbs)
        box.removeAllViews()
        for (name in Places.names(Places.text(this))) {
            val bmp = StreetView.thumbnail(this, StreetView.placeSlot(name)) ?: continue
            box.addView(android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
                addView(android.widget.ImageView(this@MainActivity).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(48), dp(48))
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(bmp)
                })
                addView(TextView(this@MainActivity).apply {
                    text = name
                    textSize = 13f
                    setPadding(dp(8), 0, 0, 0)
                })
            })
        }
    }

    private fun placesDirty() = editPlaces.text.toString().trimEnd() != Places.text(this)

    private fun placesCountText(n: Int) = "$n ${if (n == 1) "place" else "places"}"

    /** The feedback next to Save: "Saved (N places)" after saving, "Not saved" while edited. */
    private fun updatePlacesState() {
        placesSaved.text = when {
            placesDirty() -> "Not saved"
            placesJustSaved -> "Saved (${placesCountText(Places.count(Places.text(this)))})"
            else -> ""
        }
    }

    private fun savePlaces() {
        Places.save(this, editPlaces.text.toString())
        placesJustSaved = true
        updatePlacesState()
        updatePlaceThumbs()
        refresh()
    }

    /** Leaving the Settings tab with unsaved place edits: ask first. Returns true if the caller may leave. */
    private fun confirmLeavePlaces(leave: () -> Unit): Boolean {
        if (!placesDirty()) return true
        AlertDialog.Builder(this)
            .setTitle("Unsaved places")
            .setMessage("The place list has changes that are not saved.")
            .setPositiveButton("Save") { _, _ -> savePlaces(); leave() }
            .setNegativeButton("Discard") { _, _ ->
                editPlaces.setText(Places.text(this)); placesJustSaved = false; updatePlacesState(); leave()
            }
            .setNeutralButton("Keep editing", null)
            .show()
        return false
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
        if (requestCode == REQ_ICEBOX) { BrowserOpener.onPermissionResult(this); showBrowserStatus(); return }
        if (requestCode == REQ_COMMS || requestCode == REQ_REC_AUDIO) { onCommPermissionsResult(requestCode, permissions, grantResults); return }
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

    // ---------- calls and SMS (1.32) ----------

    /** Runtime permissions for the Samtaler tab, popup / read-aloud, lookup and recordings. */
    private fun commPermissions(): List<String> = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.RECEIVE_MMS)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.WRITE_CONTACTS)
        add(Recordings.permission())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun permLabel(p: String) = when (p) {
        Manifest.permission.READ_PHONE_STATE -> "Telefon (se når det ringer)"
        Manifest.permission.READ_CALL_LOG -> "Samtalelogg"
        Manifest.permission.READ_SMS -> "Lese SMS/MMS"
        Manifest.permission.RECEIVE_SMS -> "Motta SMS"
        Manifest.permission.RECEIVE_MMS -> "Motta MMS"
        Manifest.permission.READ_CONTACTS -> "Lese kontakter"
        Manifest.permission.WRITE_CONTACTS -> "Legge til kontakter"
        Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_EXTERNAL_STORAGE -> "Lydfiler (samtaleopptak)"
        Manifest.permission.POST_NOTIFICATIONS -> "Varsler (popup)"
        Manifest.permission.RECORD_AUDIO -> "Mikrofon (eksperimentelt opptak)"
        else -> p.substringAfterLast('.')
    }

    private fun askCommPermissions() {
        val missing = commPermissions().filter { !granted(it) }
        if (missing.isEmpty()) {
            Toast.makeText(this, "Alle tilganger er gitt", Toast.LENGTH_SHORT).show()
            commsTab.load(); refresh(); return
        }
        AlertDialog.Builder(this)
            .setTitle("Tilgang til samtaler og SMS")
            .setMessage("For å vise samtaler, SMS og MMS, slå opp ukjente nummer, spille av samtaleopptak og vise popup/lese opp " +
                "hvem som ringer, trenger LocationLogger disse tilgangene:\n\n• " + missing.joinToString("\n• ") { permLabel(it) } +
                "\n\nAlt blir på telefonen. Bare ukjente nummer sendes til 1881.no for oppslag (kan slås av).\n\n" +
                "Trykk «Tillat» på hvert spørsmål. Får du «Begrenset innstilling»: åpne appinfo › ⋮ › «Tillat begrensede innstillinger», og prøv igjen.")
            .setPositiveButton("Fortsett") { _, _ -> requestPermissions(missing.toTypedArray(), REQ_COMMS) }
            .setNeutralButton("Appinfo") { _, _ -> openAppSettings() }
            .setNegativeButton("Ikke nå", null)
            .show()
    }

    private fun onCommPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        val denied = permissions.filterIndexed { i, _ -> results.getOrNull(i) != PackageManager.PERMISSION_GRANTED }
        EventLog.add(this, EventLog.SERVICE, "Tilganger (samtaler/SMS): ${permissions.size - denied.size} av ${permissions.size} gitt" +
            if (denied.isNotEmpty()) " – mangler ${denied.joinToString { it.substringAfterLast('.') }}" else "")
        Phone.clearCache()
        try { MmsWatch.register(this) } catch (_: Exception) {}
        if (requestCode == REQ_REC_AUDIO && denied.isNotEmpty()) {
            LogStore.prefs(this).edit().putBoolean(CallRecorderService.KEY_ENABLED, false).apply()
            findViewById<CheckBox>(R.id.checkRecExperimental).isChecked = false
        }
        if (denied.isNotEmpty()) {
            val restricted = denied.any { it in setOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS,
                Manifest.permission.RECEIVE_MMS, Manifest.permission.READ_CALL_LOG) }
            AlertDialog.Builder(this)
                .setTitle("Noen tilganger mangler")
                .setMessage("Ikke gitt: " + denied.joinToString(", ") { permLabel(it) } + "." +
                    (if (restricted) "\n\nSMS og samtalelogg er «begrensede innstillinger» for apper som ikke er installert fra Play-butikken. " +
                        "Åpne appinfo, trykk ⋮ øverst til høyre › «Tillat begrensede innstillinger» (bekreft med PIN/fingeravtrykk), " +
                        "og gå så til Tillatelser og slå på SMS, Samtalelogg og Telefon." else "") +
                    "\n\nDu kan også gi tilgangene i appinfo › Tillatelser.")
                .setPositiveButton("Åpne appinfo") { _, _ -> openAppSettings() }
                .setNegativeButton("Lukk", null)
                .show()
        }
        if (tab == TAB_COMMS) commsTab.load()
        refresh()
    }

    private fun setupComms() {
        val p = LogStore.prefs(this)
        fun bind(id: Int, key: String, def: Boolean, onChange: (Boolean) -> Unit = {}) {
            val c = findViewById<CheckBox>(id)
            c.isChecked = p.getBoolean(key, def)
            c.setOnCheckedChangeListener { _, on -> p.edit().putBoolean(key, on).apply(); onChange(on); refresh() }
        }
        val needCalls = { on: Boolean -> if (on && commPermissions().any { !granted(it) }) askCommPermissions() }
        bind(R.id.checkCommPopup, Announcer.KEY_POPUP, false, needCalls)
        bind(R.id.checkCommTts, Announcer.KEY_TTS, false, needCalls)
        bind(R.id.checkCommTtsText, Announcer.KEY_TTS_SMS_TEXT, false)
        bind(R.id.checkLookup, NumberLookup.KEY_ENABLED, true)
        bind(R.id.checkSrc1881, NumberLookup.KEY_SRC_1881, true)
        bind(R.id.checkSrcGsPersons, NumberLookup.KEY_SRC_GS_PERSONS, true)
        bind(R.id.checkSrcGsCompanies, NumberLookup.KEY_SRC_GS_COMPANIES, true)
        bind(R.id.checkLookupContact, NumberLookup.KEY_AUTO_CONTACT, false) { on ->
            if (on && !granted(Manifest.permission.WRITE_CONTACTS)) askCommPermissions()
        }
        bind(R.id.checkRecExperimental, CallRecorderService.KEY_ENABLED, false) { on ->
            if (on && !granted(Manifest.permission.RECORD_AUDIO)) {
                AlertDialog.Builder(this)
                    .setTitle("Eksperimentelt opptak")
                    .setMessage("Android lar ikke vanlige apper ta opp samtalelyd. Dette tar opp mikrofonen under samtalen og " +
                        "får trolig bare med din egen stemme (eller stillhet). Opptakene lagres bare i appens egen mappe på telefonen. " +
                        "Husk at du må informere den du snakker med om at samtalen tas opp.\n\nGi tilgang til mikrofonen på neste skjerm.")
                    .setPositiveButton("Fortsett") { _, _ -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_REC_AUDIO) }
                    .setNegativeButton("Avbryt") { _, _ -> findViewById<CheckBox>(R.id.checkRecExperimental).isChecked = false }
                    .show()
            }
        }
        findViewById<Button>(R.id.btnCommPermsSettings).setOnClickListener { askCommPermissions() }
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener { openAppSettings() }
        findViewById<Button>(R.id.btnTtsTest).setOnClickListener { Announcer.speak(this, "Anrop fra Ola Nordmann", "test") }
        findViewById<TextView>(R.id.recHelp).text =
            "Avspilling: appen finner opptak fra Samsungs innebygde samtaleopptak (Telefon › ⋮ › Innstillinger › Ta opp samtaler) " +
            "og fra ACR Phone, og viser ▶ Opptak på samtalen i fanen Samtaler. Opptak i appen selv er av som standard."
    }

    private fun commStatusText(): String {
        val missing = commPermissions().filter { !granted(it) }
        return (if (missing.isEmpty()) "Tilganger: alle gitt" else "Tilganger mangler: " + missing.joinToString(", ") { permLabel(it) }) +
            (if (CallRecorderService.enabled(this) && !granted(Manifest.permission.RECORD_AUDIO)) "\nMikrofon: ikke gitt (eksperimentelt opptak virker ikke)" else "")
    }

    /** Browser / Ice Box line under Street View in Settings (1.30). */
    private fun showBrowserStatus() {
        findViewById<TextView>(R.id.browserStatus).text = BrowserOpener.statusLine(this)
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
        manualLine(p)?.let { status.text = "${status.text}\n$it" }
        settingsStatus.text = (if (running) "● Running – checking every $interval min" else "○ Stopped – interval $interval min") +
            "\n" + gps + "\n" + perm +
            (if (isIgnoringBattery()) "\nBattery optimization: off" else "\nBattery optimization: on (may stop logging)") +
            "\nGPS used today: ${LocationService.gpsUsesToday(this)}× (location: cached fix → Wi-Fi/cell → GPS only if needed)" +
            (if (CheckAlarm.canExact(this)) "\nWake-up alarms: exact" else "\nWake-up alarms: inexact (exact alarms not allowed; checks may be late with the screen off)")
        findViewById<TextView>(R.id.keepAdbWifiStatus).text =
            (if (GpsToggle.hasPermission(this)) "Permission WRITE_SECURE_SETTINGS: granted"
             else "Permission WRITE_SECURE_SETTINGS: not granted (grant via adb) – cannot turn it on") +
            (p.getString(AdbReporter.KEY_KEEP_STATUS, null)?.let { "\nLast: $it" } ?: "")
        findViewById<TextView>(R.id.dashboardStatus).text =
            (if (AdbReporter.url(this).isEmpty()) "Off" else (AdbReporter.status(this) ?: "Not reported yet")) +
                "\n" + ShizukuWatch.statusLine(this) + "\n" + TailscaleWatch.statusLine(this)
        findViewById<TextView>(R.id.commPermStatus).text = commStatusText()
        findViewById<Button>(R.id.btnCommPermsSettings).isEnabled = commPermissions().any { !granted(it) }
        findViewById<TextView>(R.id.lookupStatus).text = "Oppslag lagret: ${NumberLookup.cacheSize(this)}" +
            (NumberLookup.status(this)?.let { " · $it" } ?: "")
        lastCheck.text = "Last check: " + (p.getString(LogStore.KEY_LAST_CHECK, null) ?: "–")
        btnBackground.isEnabled = !hasBackgroundLocation()
        btnBattery.isEnabled = !isIgnoringBattery()
        val nPlaces = Places.count(Places.text(this))
        findViewById<TextView>(R.id.placesTitle).text =
            if (nPlaces == 0) "Places (merge addresses)" else "Places (merge addresses): ${placesCountText(nPlaces)}"
        val lastRaw = p.getString(LogStore.KEY_LAST_RAW_ADDRESS, null)
        findViewById<TextView>(R.id.placesLastRaw).text =
            "Last address from the phone:\n" + (lastRaw ?: "– (none yet)")
        btnInsertLast.isEnabled = lastRaw != null
        Thread {
            LogStore.deleteOldTextLogsOnce(this)
            val all = VisitStore.all(this)
            val cand = VisitStore.candidate(this)
            val trip = VisitStore.tripSoFar(this)
            runOnUiThread { showVisits(all, cand, running, trip) }
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

    /**
     * "🚗 Driving · 25 min · 18.3 km" between two stops: [durationMs] = next arrival − previous
     * departure, [distanceM] = travelled distance. Unknown values show "–"; null if both are unknown.
     */
    private fun drivingRow(durationMs: Long?, distanceM: Double?, ongoing: Boolean): android.view.View? {
        val dur = durationMs?.takeIf { it >= 0 }
        if (dur == null && distanceM == null) return null
        val durText = dur?.let { formatDuration(it) + if (ongoing) " so far" else "" } ?: "–"
        val distText = distanceM?.let { formatDistance(it.toFloat()) } ?: "–"
        return TextView(this).apply {
            text = (if (ongoing) "🚗 Driving now · " else "🚗 Driving · ") + "$durText · $distText"
            textSize = 13f
            setTextColor(if (ongoing) 0xFF1565C0.toInt() else 0xFF757575.toInt())
            setTypeface(typeface, android.graphics.Typeface.ITALIC)
            setPadding(dp(28), dp(2), 0, dp(2))
        }
    }

    private fun showVisits(all: List<Visit>, cand: Pair<String, Long>?, running: Boolean, trip: Double?) {
        visits.removeAllViews()
        val now = System.currentTimeMillis()
        val placeMap = Places.parse(Places.text(this))
        // Ongoing trip: the current stop has been left (a new place was seen once) but not confirmed yet
        val cur = all.lastOrNull()
        if (cand != null && running && cur != null) {
            drivingRow(cur.departure?.let { now - it }, trip, ongoing = true)?.let { visits.addView(it) }
        }
        if (cand != null && running) {
            visits.addView(TextView(this).apply {
                text = "Seen once: ${Places.label(placeMap, cand.first)} at ${timeFmt.format(Date(cand.second))} – becomes a visit if confirmed at the next check"
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
            // Re-labelled with the current place list (stops logged before a Place was added show its name)
            val label = Places.label(placeMap, v.name)

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

            val arrived = timeFmt.format(Date(v.arrival))
            val dep = v.departure
            fun at(t: Long) = if (sameDay(v.arrival, t)) timeFmt.format(Date(t)) else
                SimpleDateFormat("EEE d MMM HH:mm", Locale.ENGLISH).format(Date(t))
            val (times, duration) = when {
                isCurrent && running -> "$arrived – now" to "${formatDuration(now - v.arrival)} so far"
                dep != null && isCurrent -> "$arrived – last seen ${at(dep)} (logging stopped)" to formatDuration(dep - v.arrival)
                dep != null -> "$arrived – ${at(dep)}" to formatDuration(dep - v.arrival)
                next != null -> "$arrived – ?" to "up to ${formatDuration(next.arrival - v.arrival)}"
                else -> "$arrived – ?" to null
            }
            val details = duration ?: ""

            visits.addView(android.widget.LinearLayout(this).apply {
                // Text on the left (full width when there is no image), square image on the right
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.TOP
                setPadding(dp(8), dp(6), 0, dp(6))
                val row = this
                val textCol = android.widget.LinearLayout(this@MainActivity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                addView(textCol)
                textCol.addView(TextView(this@MainActivity).apply {
                    text = (if (isCurrent && running) "● " else "") + label
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextIsSelectable(true)
                })
                textCol.addView(TextView(this@MainActivity).apply { text = times; textSize = 14f })
                if (details.isNotEmpty()) textCol.addView(TextView(this@MainActivity).apply {
                    text = details
                    textSize = 13f
                    setTextColor(0xFF616161.toInt())
                })
                val lat = v.lat
                val lon = v.lon
                if (lat != null && lon != null) {
                    val slot = slotFor(label, lat, lon, placeMap)
                    streetViewImage(slot, lat, lon)?.let { row.addView(it) }
                    row.setOnLongClickListener { imageMenu(slot, lat, lon); true }
                }
            })
            // Driving from the previous stop to this one (shown under the arriving stop, in its day)
            if (prev != null) {
                drivingRow(prev.departure?.let { v.arrival - it }, v.travelledM, ongoing = false)
                    ?.let { visits.addView(it) }
            }
        }
        if (first > 0) visits.addView(TextView(this).apply {
            text = "($first older visits not shown)"
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        })
    }
}
