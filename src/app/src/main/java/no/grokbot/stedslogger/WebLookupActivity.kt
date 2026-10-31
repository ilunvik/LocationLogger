package no.grokbot.stedslogger

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
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
 * Visible lookup page (1.34): 1881.no or gulesider.no (personer / bedrifter) for one number in an in-app
 * WebView, so the user can see the result and complete a Cloudflare check by hand. Cookies are shared with
 * the background lookup. "Bruk dette navnet" reads the matching entry (or lets the user type a name) and
 * saves it as the lookup result; nothing else leaves the phone.
 */
class WebLookupActivity : Activity() {
    companion object {
        const val EXTRA_NUMBER = "number"
        const val EXTRA_SITE = "site"
        const val SITE_1881 = 0
        const val SITE_GS_PERSONS = 1
        const val SITE_GS_COMPANIES = 2

        fun open(ctx: Context, number: String?, site: Int = SITE_1881) {
            val k = Phone.key(number)
            if (!Phone.isNorwegian(k)) { Toast.makeText(ctx, "Bare norske 8-sifrede nummer kan slås opp", Toast.LENGTH_SHORT).show(); return }
            ctx.startActivity(Intent(ctx, WebLookupActivity::class.java).putExtra(EXTRA_NUMBER, k).putExtra(EXTRA_SITE, site))
        }
    }

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var k: String
    private var site = SITE_1881
    private var last: WebLookup.Page? = null
    private val handler = Handler(Looper.getMainLooper())
    private val siteButtons = ArrayList<Button>()
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()

    private val poll = object : Runnable {
        override fun run() {
            if (isFinishing) return
            web.evaluateJavascript(WebLookup.script(this@WebLookupActivity, k)) { raw ->
                val p = WebLookup.parse(raw) ?: return@evaluateJavascript
                last = p
                status.text = when {
                    p.state == "challenge" -> "Sikkerhetssjekk – fullfør den på siden (kryss av «Jeg er et menneske» om den spør)"
                    p.name != null -> "Funnet: ${p.name}  – trykk «Bruk dette navnet»"
                    p.state == "done" && p.none -> "Ingen treff på ${siteName(site)}"
                    p.state == "done" -> "Ingen treff funnet automatisk – du kan skrive navnet selv"
                    else -> "Laster …"
                }
                if (p.state == "done") NumberLookup.unblock(this@WebLookupActivity, if (site == SITE_1881) NumberLookup.SRC_1881 else NumberLookup.SRC_GS)
            }
            handler.postDelayed(this, 1_500L)
        }
    }

    private fun siteName(s: Int) = when (s) { SITE_GS_PERSONS -> "gulesider.no (personer)"; SITE_GS_COMPANIES -> "gulesider.no (bedrifter)"; else -> "1881.no" }
    private fun url(s: Int) = when (s) { SITE_GS_PERSONS -> NumberLookup.urlGsPersons(k); SITE_GS_COMPANIES -> NumberLookup.urlGsCompanies(k); else -> NumberLookup.url1881(k) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        k = intent.getStringExtra(EXTRA_NUMBER)?.let { Phone.key(it) }?.takeIf { Phone.isNorwegian(it) } ?: run { finish(); return }
        site = savedInstanceState?.getInt(EXTRA_SITE) ?: intent.getIntExtra(EXTRA_SITE, SITE_1881)
        title = "Oppslag ${Phone.pretty(k)}"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sites = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(4), dp(4), 0) }
        listOf("1881", "Gulesider personer", "Gulesider bedrifter").forEachIndexed { i, label ->
            val b = Button(this, null, android.R.attr.buttonStyleSmall).apply {
                text = label; isAllCaps = false; textSize = 13f
                setOnClickListener { load(i) }
            }
            siteButtons += b; sites.addView(b)
        }
        root.addView(HorizontalScrollView(this).apply { addView(sites) })
        status = TextView(this).apply { textSize = 13f; setPadding(dp(10), dp(2), dp(10), dp(4)); setTypeface(typeface, Typeface.BOLD) }
        root.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
        web = WebView(this)
        WebLookup.configure(web, headless = false)
        web.webViewClient = object : WebViewClient() {
            // Stay inside the two directory sites; anything else (ads, maps, apps) opens outside or not at all
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return true
                val ok = host.endsWith("1881.no") || host.endsWith("gulesider.no") || host.endsWith("cloudflare.com")
                return !ok && request.isForMainFrame
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) android.view.View.INVISIBLE else android.view.View.VISIBLE
            }
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), 0, dp(4), dp(4)) }
        bottom.addView(Button(this).apply { text = "Bruk dette navnet"; isAllCaps = false; setOnClickListener { useName() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(Button(this).apply { text = "Lukk"; isAllCaps = false; setOnClickListener { finish() } })
        root.addView(bottom)
        setContentView(root)
        EventLog.add(this, EventLog.LOOKUP, "Nettvisning åpnet for ${Phone.masked(k)}")
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else load(site)
        handler.postDelayed(poll, 1_500L)
    }

    private fun load(s: Int) {
        site = s
        last = null
        siteButtons.forEachIndexed { i, b -> b.setTypeface(null, if (i == s) Typeface.BOLD else Typeface.NORMAL); b.alpha = if (i == s) 1f else 0.7f }
        status.text = "Laster ${siteName(s)} …"
        web.loadUrl(url(s), mapOf("Accept-Language" to "nb-NO,nb;q=0.9,no;q=0.8,en;q=0.5"))
    }

    private fun useName() {
        web.evaluateJavascript(WebLookup.script(this, k)) { raw ->
            val p = WebLookup.parse(raw) ?: last
            val edit = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                setText(p?.name ?: "")
                hint = "Navn"
                setSelection(text.length)
            }
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(edit) }
            val source = if (site == SITE_1881) NumberLookup.SRC_1881 else NumberLookup.SRC_GS
            AlertDialog.Builder(this)
                .setTitle(if (p?.name != null) "Navn funnet på siden" else "Fant ikke navnet automatisk")
                .setMessage("Lagres som oppslag for ${Phone.pretty(k)} (kilde: $source). Du kan rette navnet.")
                .setView(box)
                .setPositiveButton("Lagre") { _, _ -> save(edit.text.toString(), source, addContact = false) }
                .setNeutralButton("Lagre + kontakter") { _, _ -> save(edit.text.toString(), source, addContact = true) }
                .setNegativeButton("Avbryt", null)
                .show()
        }
    }

    private fun save(name: String, source: String, addContact: Boolean) {
        val n = name.trim()
        if (n.length < 2) { Toast.makeText(this, "Skriv et navn", Toast.LENGTH_SHORT).show(); return }
        val r = NumberLookup.store(this, k, n, source) ?: return
        Phone.clearCache()
        val msg = if (addContact) Contacts.add(this, k, n, source) ?: "Lagret og lagt til i kontakter"
                  else { Contacts.maybeAutoAdd(this, k, r); "Lagret: $n" }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        setResult(RESULT_OK)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_SITE, site)
        web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        try { CookieManager.getInstance().flush() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) try { web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
