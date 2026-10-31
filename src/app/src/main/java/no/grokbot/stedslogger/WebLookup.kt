package no.grokbot.stedslogger

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Number lookup in a real browser engine (1.34): an off-screen Android WebView with JavaScript, DOM storage
 * and persistent cookies loads the directory page, waits until a Cloudflare check ("Just a moment...") is
 * over, and reads the result with assets/lookup_extract.js (JSON-LD, 1881 result boxes / detail page,
 * result cards; the entry must carry exactly the number searched for). Cookies are shared with the visible
 * [LookupTab], so a check the user completes there also helps later background lookups.
 * Only the lookup URL (which contains the number) is sent.
 */
object WebLookup {
    sealed class Outcome {
        data class Found(val name: String, val how: String?) : Outcome()
        object NotFound : Outcome()
        data class Blocked(val why: String) : Outcome()
        data class Failed(val why: String) : Outcome()
    }

    /** One extractor reading of the page. */
    data class Page(val state: String, val name: String?, val none: Boolean, val title: String?, val how: String?, val url: String?)

    const val MAX_WAIT_MS = 20_000L
    private const val POLL_MS = 1_000L
    /** After the page is done without a hit, wait this long for late content before calling it "no hit". */
    private const val SETTLE_MS = 2_500L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var script: String? = null

    fun script(ctx: Context, k: String): String {
        val s = script ?: ctx.applicationContext.assets.open("lookup_extract.js").bufferedReader().use { it.readText() }.also { script = it }
        return s.replace("'__K__'", "'" + k.filter { it.isDigit() } + "'")
    }

    /** WebView's own user agent without the "; wv" / "Version/4.0" markers, i.e. the one Chrome itself sends. */
    fun browserUa(ctx: Context): String = try {
        WebSettings.getDefaultUserAgent(ctx).replace("; wv)", ")").replace(Regex("Version/\\d+(\\.\\d+)* "), "")
    } catch (_: Exception) {
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(wv: WebView, headless: Boolean) {
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        @Suppress("DEPRECATION")
        s.databaseEnabled = true
        s.userAgentString = browserUa(wv.context)
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.mediaPlaybackRequiresUserGesture = true
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        if (headless) s.blockNetworkImage = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
    }

    fun parse(raw: String?): Page? = try {
        val inner = JSONTokener(raw ?: return null).nextValue() as? String ?: return null
        val o = JSONObject(inner)
        Page(o.optString("s"), o.optString("n").takeIf { o.has("n") && !o.isNull("n") && it.isNotBlank() },
            o.optBoolean("none"), o.optString("t"), o.optString("h").takeIf { o.has("h") && !o.isNull("h") }, o.optString("u"))
    } catch (_: Exception) { null }

    /** Blocking (never call on the main thread): loads [url] off-screen and returns what the page says about [k]. */
    fun run(ctx: Context, url: String, k: String, timeoutMs: Long = MAX_WAIT_MS): Outcome {
        check(Looper.myLooper() != Looper.getMainLooper()) { "WebLookup.run on main thread" }
        val app = ctx.applicationContext
        val js = script(app, k)
        val latch = CountDownLatch(1)
        var outcome: Outcome = Outcome.Failed("tidsavbrudd")
        main.post {
            var wv: WebView? = null
            var finished = false
            var sawChallenge = false
            var doneSince = 0L
            var httpError = 0
            val start = System.currentTimeMillis()
            fun finish(o: Outcome) {
                if (finished) return
                finished = true
                outcome = o
                try { wv?.stopLoading(); wv?.destroy() } catch (_: Exception) {}
                try { CookieManager.getInstance().flush() } catch (_: Exception) {}
                latch.countDown()
            }
            try {
                val v = WebView(app)
                wv = v
                configure(v, headless = true)
                // Give it a real (phone-sized) viewport even though it is never on screen
                v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY))
                v.layout(0, 0, 1080, 2200)
                v.webViewClient = object : WebViewClient() {
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame) httpError = response.statusCode
                    }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        wv = null; finish(Outcome.Failed("nettleseren krasjet")); return true
                    }
                }
                val poll = object : Runnable {
                    override fun run() {
                        if (finished) return
                        val now = System.currentTimeMillis()
                        if (now - start > timeoutMs) {
                            finish(if (sawChallenge) Outcome.Blocked("Cloudflare-sjekk ble ikke ferdig")
                                   else if (httpError in setOf(403, 429, 503)) Outcome.Blocked("HTTP $httpError")
                                   else Outcome.Failed("tidsavbrudd"))
                            return
                        }
                        val wview = wv ?: return
                        wview.evaluateJavascript(js) { raw ->
                            if (finished) return@evaluateJavascript
                            val p = parse(raw)
                            when {
                                p == null -> {}
                                p.state == "challenge" -> { sawChallenge = true; doneSince = 0L }
                                p.name != null -> { finish(Outcome.Found(p.name, p.how)); return@evaluateJavascript }
                                p.state == "done" && p.none -> { finish(Outcome.NotFound); return@evaluateJavascript }
                                p.state == "done" -> {
                                    if (doneSince == 0L) doneSince = System.currentTimeMillis()
                                    else if (System.currentTimeMillis() - doneSince >= SETTLE_MS) {
                                        finish(if (httpError in setOf(403, 429, 503)) Outcome.Blocked("HTTP $httpError") else Outcome.NotFound)
                                        return@evaluateJavascript
                                    }
                                }
                            }
                            main.postDelayed(this, POLL_MS)
                        }
                    }
                }
                v.loadUrl(url, mapOf("Accept-Language" to "nb-NO,nb;q=0.9,no;q=0.8,en;q=0.5"))
                main.postDelayed(poll, POLL_MS)
                main.postDelayed({ if (!finished) finish(if (sawChallenge) Outcome.Blocked("Cloudflare-sjekk ble ikke ferdig") else Outcome.Failed("tidsavbrudd")) }, timeoutMs + 3_000L)
            } catch (e: Throwable) {
                finish(Outcome.Failed("WebView: ${e.javaClass.simpleName}"))
            }
        }
        if (!latch.await(timeoutMs + 8_000L, TimeUnit.MILLISECONDS)) return Outcome.Failed("tidsavbrudd")
        return outcome
    }
}
