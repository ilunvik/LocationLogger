package no.grokbot.stedslogger

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * "Oppslag" tab (1.36): a permanent in-app browser for 1881.no and gulesider.no. Type or paste a number (or a
 * name), pick a site, browse; "Bruk dette navnet" / "Lagre + kontakter" save the name for the number on the
 * phone. The WebView is created the first time the tab is shown and then kept for the life of the activity
 * (MainActivity handles fold/rotation itself), so switching tabs never reloads it. Cookies are shared with
 * the background lookup, so a security check passed here also helps later automatic lookups.
 */
class LookupTab(private val act: Activity) {
    companion object {
        const val SITE_1881 = 0
        const val SITE_GS_PERSONS = 1
        const val SITE_GS_COMPANIES = 2
        const val EXTRA_NUMBER = "lookup_number"
        const val EXTRA_SITE = "lookup_site"
        private const val STATE_K = "lookup_k"
        private const val STATE_SITE = "lookup_site_state"
        private const val STATE_INPUT = "lookup_input"
        private const val STATE_WEB = "lookup_web"
        private val ALLOWED = listOf("1881.no", "gulesider.no", "cloudflare.com")

        /** Switch to the Oppslag tab with [number] filled in and its page loaded. */
        fun open(ctx: Context, number: String?, site: Int = SITE_1881) {
            val k = Phone.key(number)
            if (!Phone.isNorwegian(k)) { Toast.makeText(ctx, "Bare norske 8-sifrede nummer kan slås opp", Toast.LENGTH_SHORT).show(); return }
            ctx.startActivity(Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_WEB)
                .putExtra(EXTRA_NUMBER, k).putExtra(EXTRA_SITE, site))
        }

        fun allowed(host: String?) = host != null && ALLOWED.any { host == it || host.endsWith(".$it") }

        /** Search URL for a name or free text (1881 searches everything; gulesider per category). */
        fun textUrl(site: Int, q: String) = when (site) {
            SITE_GS_PERSONS -> "https://www.gulesider.no/${Uri.encode(q.trim())}/personer"
            SITE_GS_COMPANIES -> "https://www.gulesider.no/${Uri.encode(q.trim())}/bedrifter"
            else -> "https://www.1881.no/?query=${Uri.encode(q.trim())}"
        }
    }

    private val page: LinearLayout = act.findViewById(R.id.pageWeb)
    private val handler = Handler(Looper.getMainLooper())
    private val d = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private var web: WebView? = null
    private lateinit var input: EditText
    private lateinit var urlBar: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnBack: Button
    private lateinit var btnFwd: Button
    private lateinit var btnUse: Button
    private lateinit var btnSaveContact: Button
    private val siteButtons = ArrayList<Button>()

    /** Number the page is about (null for a name search). */
    private var k: String? = null
    private var site = SITE_1881
    private var last: WebLookup.Page? = null
    private var visible = false
    private var pending: Bundle? = null

    private val poll = object : Runnable {
        override fun run() {
            val w = web ?: return
            if (!visible) return
            val kk = k
            if (kk != null) w.evaluateJavascript(WebLookup.script(act, kk)) { raw ->
                val p = WebLookup.parse(raw) ?: return@evaluateJavascript
                last = p
                status.text = when {
                    p.state == "challenge" -> "Sikkerhetssjekk – fullfør den på siden (kryss av «Jeg er et menneske» om den spør)"
                    p.name != null -> "Funnet: ${p.name}  – trykk «Bruk dette navnet»"
                    p.state == "done" && p.none -> "Ingen treff for ${Phone.pretty(kk)} på ${siteName(site)}"
                    p.state == "done" -> "Ingen treff funnet automatisk – du kan skrive navnet selv"
                    else -> "Laster …"
                }
                if (p.state == "done") NumberLookup.unblock(act, if (site == SITE_1881) NumberLookup.SRC_1881 else NumberLookup.SRC_GS)
            }
            handler.postDelayed(this, 1_500L)
        }
    }

    private fun siteName(s: Int) = when (s) { SITE_GS_PERSONS -> "gulesider.no (personer)"; SITE_GS_COMPANIES -> "gulesider.no (bedrifter)"; else -> "1881.no" }
    private fun numberUrl(s: Int, k: String) = when (s) { SITE_GS_PERSONS -> NumberLookup.urlGsPersons(k); SITE_GS_COMPANIES -> NumberLookup.urlGsCompanies(k); else -> NumberLookup.url1881(k) }

    private fun small(text: String, onClick: () -> Unit) = Button(act, null, android.R.attr.buttonStyleSmall).apply {
        this.text = text; isAllCaps = false; textSize = 13f; minWidth = 0; minimumWidth = 0
        setPadding(dp(10), 0, dp(10), 0); setOnClickListener { onClick() }
    }

    private fun build() {
        if (web != null) return
        page.removeAllViews()
        // Number / name field
        val row1 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), 0) }
        input = EditText(act).apply {
            hint = "Nummer (eller navn)"
            inputType = InputType.TYPE_CLASS_PHONE
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            isSingleLine = true
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE) { search(site); true } else false }
        }
        row1.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(small("abc") {}.also { b ->
            b.setOnClickListener {
                val text = input.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT
                input.inputType = if (text) InputType.TYPE_CLASS_PHONE else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                b.text = if (text) "abc" else "123"
                input.setSelection(input.text.length)
                input.requestFocus()
                (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(input)
            }
        })
        row1.addView(small("✕") { input.setText(""); input.requestFocus() })
        page.addView(row1)
        // Sites
        val sites = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), 0, dp(4), 0) }
        listOf("1881", "Gulesider personer", "Gulesider bedrifter").forEachIndexed { i, label ->
            val b = small(label) { search(i) }
            siteButtons += b; sites.addView(b)
        }
        page.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(sites) })
        // Navigation bar
        val nav = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
        btnBack = small("◀") { web?.let { if (it.canGoBack()) it.goBack() } }
        btnFwd = small("▶") { web?.let { if (it.canGoForward()) it.goForward() } }
        nav.addView(btnBack); nav.addView(btnFwd)
        nav.addView(small("⟳") { web?.reload() })
        urlBar = EditText(act).apply {
            textSize = 12f; isSingleLine = true
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_GO; hint = "Adresse"
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_DONE) { go(text.toString()); true } else false }
        }
        nav.addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        page.addView(nav)
        status = TextView(act).apply { textSize = 13f; setPadding(dp(10), 0, dp(10), dp(2)); setTypeface(typeface, Typeface.BOLD)
            text = "Skriv eller lim inn et nummer og velg 1881 eller Gulesider" }
        page.addView(status)
        progress = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.INVISIBLE }
        page.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
        // Web view
        val w = WebView(act)
        WebLookup.configure(w, headless = false)
        w.webViewClient = object : WebViewClient() {
            // Stay inside the two directory sites; other main-frame links (ads, maps, apps) are not followed
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val ok = allowed(request.url.host)
                if (!ok && request.isForMainFrame) Toast.makeText(act, "Nettvisningen viser bare 1881.no og gulesider.no", Toast.LENGTH_SHORT).show()
                return !ok && request.isForMainFrame
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { showUrl(url) }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { showUrl(url) }
            override fun onPageFinished(view: WebView, url: String?) { showUrl(url) }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.INVISIBLE else View.VISIBLE
            }
        }
        page.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        web = w
        // Save actions
        val bottom = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), 0, dp(4), dp(2)) }
        btnUse = Button(act).apply { text = "Bruk dette navnet"; isAllCaps = false; setOnClickListener { useName(addContact = false) } }
        btnSaveContact = Button(act).apply { text = "Lagre + kontakter"; isAllCaps = false; setOnClickListener { useName(addContact = true) } }
        bottom.addView(btnUse, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(btnSaveContact, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        page.addView(bottom)
        pending?.let { b ->
            pending = null
            k = b.getString(STATE_K); site = b.getInt(STATE_SITE, SITE_1881)
            input.setText(b.getString(STATE_INPUT) ?: "")
            b.getBundle(STATE_WEB)?.let { w.restoreState(it) }
        }
        markSite(); updateButtons()
    }

    private fun showUrl(url: String?) {
        if (!urlBar.hasFocus()) urlBar.setText(url ?: "")
        updateButtons()
    }

    private fun updateButtons() {
        val w = web ?: return
        btnBack.isEnabled = w.canGoBack(); btnFwd.isEnabled = w.canGoForward()
        val has = k != null
        btnUse.isEnabled = has; btnSaveContact.isEnabled = has
    }

    private fun markSite() = siteButtons.forEachIndexed { i, b ->
        b.setTypeface(null, if (i == site) Typeface.BOLD else Typeface.NORMAL); b.alpha = if (i == site) 1f else 0.7f
    }

    private fun hideKeyboard() {
        (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(page.windowToken, 0)
        input.clearFocus(); urlBar.clearFocus()
    }

    /** Search the field's content on [s]: a Norwegian number looks up that number, anything else is a text search. */
    private fun search(s: Int) {
        val text = input.text.toString().trim()
        val key = Phone.key(text)
        site = s; last = null; markSite()
        when {
            Phone.isNorwegian(key) -> {
                if (key != k) EventLog.add(act, EventLog.LOOKUP, "Nettvisning: oppslag på ${Phone.masked(key)}")
                k = key; load(numberUrl(s, key)); status.text = "Laster ${siteName(s)} for ${Phone.pretty(key)} …"
            }
            text.count { it.isLetter() } >= 2 -> { k = null; load(textUrl(s, text)); status.text = "Søker etter «$text» på ${siteName(s)} …" }
            text.isEmpty() -> { Toast.makeText(act, "Skriv eller lim inn et nummer", Toast.LENGTH_SHORT).show(); input.requestFocus(); return }
            else -> { Toast.makeText(act, "Skriv et norsk 8-sifret nummer (eller et navn med «abc»)", Toast.LENGTH_SHORT).show(); return }
        }
        hideKeyboard(); updateButtons()
    }

    private fun load(url: String) {
        web?.loadUrl(url, mapOf("Accept-Language" to "nb-NO,nb;q=0.9,no;q=0.8,en;q=0.5"))
        handler.removeCallbacks(poll); handler.postDelayed(poll, 1_500L)
    }

    private fun go(raw: String) {
        val t = raw.trim()
        val url = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
        if (!allowed(Uri.parse(url).host)) { Toast.makeText(act, "Nettvisningen viser bare 1881.no og gulesider.no", Toast.LENGTH_SHORT).show(); return }
        hideKeyboard(); load(url)
    }

    /** From a call row or dialog: fill in [number] and load it on [s]. */
    fun openNumber(number: String, s: Int) {
        build()
        input.inputType = InputType.TYPE_CLASS_PHONE
        input.setText(Phone.pretty(number))
        search(s)
    }

    fun show() {
        build()
        visible = true
        web?.onResume()
        handler.removeCallbacks(poll); handler.postDelayed(poll, 500L)
    }

    fun hide() {
        visible = false
        handler.removeCallbacks(poll)
        web?.onPause()
        try { CookieManager.getInstance().flush() } catch (_: Exception) {}
    }

    /** Android back while the tab is active: web history first. */
    fun back(): Boolean {
        val w = web ?: return false
        if (w.canGoBack()) { w.goBack(); return true }
        return false
    }

    fun save(out: Bundle) {
        val w = web
        if (w == null) { pending?.let { out.putAll(it) }; return }
        val b = Bundle()
        b.putString(STATE_K, k); b.putInt(STATE_SITE, site); b.putString(STATE_INPUT, input.text.toString())
        val ws = Bundle(); w.saveState(ws); b.putBundle(STATE_WEB, ws)
        out.putBundle("lookup_tab", b)
    }

    fun restore(saved: Bundle?) { pending = saved?.getBundle("lookup_tab") }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        web?.let { try { (it.parent as? ViewGroup)?.removeView(it); it.destroy() } catch (_: Exception) {} }
        web = null
    }

    private fun useName(addContact: Boolean) {
        val kk = k ?: run { Toast.makeText(act, "Slå opp et nummer først", Toast.LENGTH_SHORT).show(); return }
        val w = web ?: return
        w.evaluateJavascript(WebLookup.script(act, kk)) { raw ->
            val p = WebLookup.parse(raw) ?: last
            val edit = EditText(act).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                setText(p?.name ?: ""); hint = "Navn"; setSelection(text.length)
            }
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(edit) }
            val source = if (site == SITE_1881) NumberLookup.SRC_1881 else NumberLookup.SRC_GS
            val b = AlertDialog.Builder(act)
                .setTitle(if (p?.name != null) "Navn funnet på siden" else "Fant ikke navnet automatisk")
                .setMessage("Lagres som oppslag for ${Phone.pretty(kk)} (kilde: $source). Du kan rette navnet." +
                    if (addContact) "\nKontakten lagres i: ${Contacts.current(act).label}" else "")
                .setView(box)
                .setNegativeButton("Avbryt", null)
            if (addContact) b.setPositiveButton("Lagre + kontakter") { _, _ -> save(kk, edit.text.toString(), source, true) }
            else b.setPositiveButton("Lagre") { _, _ -> save(kk, edit.text.toString(), source, false) }
                .setNeutralButton("Lagre + kontakter") { _, _ -> save(kk, edit.text.toString(), source, true) }
            b.show()
        }
    }

    private fun save(kk: String, name: String, source: String, addContact: Boolean) {
        val n = name.trim()
        if (n.length < 2) { Toast.makeText(act, "Skriv et navn", Toast.LENGTH_SHORT).show(); return }
        val r = NumberLookup.store(act, kk, n, source) ?: return
        Phone.clearCache()
        val msg = if (addContact) Contacts.add(act, kk, n, source) ?: "Lagret og lagt til i kontakter"
                  else { Contacts.maybeAutoAdd(act, kk, r); "Lagret: $n" }
        Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
        status.text = msg
    }
}
