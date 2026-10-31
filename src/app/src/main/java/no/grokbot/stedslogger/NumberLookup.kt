package no.grokbot.stedslogger

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Name lookup for unknown Norwegian numbers in a public directory (1.32).
 *
 * Source 1: 1881.no search page (`https://www.1881.no/?query=<8 digits>`): every result box
 * (`class="box listing …"`) has its name in `listing-name` and its numbers as `tel:0047…` links; a
 * box is a hit only if one of its tel links is the number searched for (the page also lists
 * related businesses). Source 2 (fallback): gulesider.no, best effort (often blocked by Cloudflare).
 *
 * Only the number is sent, nothing else. Results are cached in files/lookup_cache.json (found: 90 days,
 * not found: 14 days). Rate limits: at least [MIN_GAP_MS] between requests and at most [MAX_PER_HOUR]
 * per hour; a block (HTTP 403/429 or a challenge page) pauses that source for [BLOCK_PAUSE_MS].
 */
object NumberLookup {
    data class Result(val name: String?, val source: String, val at: Long)

    private const val FILE = "lookup_cache.json"
    private const val FOUND_TTL = 90L * 86_400_000L
    private const val NOT_FOUND_TTL = 14L * 86_400_000L
    private const val MIN_GAP_MS = 3_000L
    private const val MAX_PER_HOUR = 40
    private const val BLOCK_PAUSE_MS = 6L * 3_600_000L
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    const val KEY_ENABLED = "lookup_enabled"
    const val KEY_AUTO_CONTACT = "lookup_auto_contact"
    private const val KEY_STATUS = "lookup_status"

    private val io = Executors.newSingleThreadExecutor()
    private var cache: MutableMap<String, Result>? = null
    private val recent = ArrayDeque<Long>()
    private var lastRequest = 0L
    private val blockedUntil = HashMap<String, Long>()

    fun enabled(ctx: Context) = LogStore.prefs(ctx).getBoolean(KEY_ENABLED, true)
    fun autoContact(ctx: Context) = LogStore.prefs(ctx).getBoolean(KEY_AUTO_CONTACT, false)
    fun status(ctx: Context): String? = LogStore.prefs(ctx).getString(KEY_STATUS, null)

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    @Synchronized
    private fun load(ctx: Context): MutableMap<String, Result> {
        cache?.let { return it }
        val m = HashMap<String, Result>()
        try {
            val f = file(ctx)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                for (k in o.keys()) {
                    val e = o.getJSONObject(k)
                    m[k] = Result(if (e.isNull("n")) null else e.getString("n"), e.optString("s", "?"), e.optLong("t"))
                }
            }
        } catch (_: Exception) {}
        cache = m
        return m
    }

    @Synchronized
    private fun save(ctx: Context) {
        val m = cache ?: return
        try {
            val o = JSONObject()
            for ((k, r) in m) o.put(k, JSONObject().put("n", r.name ?: JSONObject.NULL).put("s", r.source).put("t", r.at))
            val f = file(ctx)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Exception) {}
    }

    /** Cached result (also expired-but-found names, which are still shown), or null. */
    @Synchronized
    fun cached(ctx: Context, raw: String?): Result? {
        val k = Phone.key(raw)
        if (!Phone.isNorwegian(k)) return null
        return load(ctx)[k]
    }

    @Synchronized
    private fun fresh(ctx: Context, k: String): Result? {
        val r = load(ctx)[k] ?: return null
        val ttl = if (r.name != null) FOUND_TTL else NOT_FOUND_TTL
        return if (System.currentTimeMillis() - r.at < ttl) r else null
    }

    @Synchronized
    fun clearCache(ctx: Context) { cache = HashMap(); save(ctx) }

    fun cacheSize(ctx: Context): Int = synchronized(this) { load(ctx).size }

    /** Should this number be looked up (Norwegian, not a contact, no fresh cache entry)? */
    fun needsLookup(ctx: Context, raw: String?): Boolean {
        val k = Phone.key(raw)
        if (!Phone.isNorwegian(k)) return false
        if (Phone.contactName(ctx, raw) != null) return false
        return fresh(ctx, k) == null
    }

    /** Looks the number up in the background; [done] runs on the lookup thread. */
    fun lookupAsync(ctx: Context, raw: String?, manual: Boolean = false, done: (Result?) -> Unit) {
        val app = ctx.applicationContext
        io.execute { done(try { lookup(app, raw, manual) } catch (e: Exception) { EventLog.error(app, "Oppslag feilet", e); null }) }
    }

    /**
     * Blocking lookup (do not call on the main thread). Returns the cached or new result, or null when the
     * number cannot be looked up or every source is paused / rate limited ([manual] skips the hourly cap).
     */
    fun lookup(ctx: Context, raw: String?, manual: Boolean = false): Result? {
        val k = Phone.key(raw)
        if (!Phone.isNorwegian(k)) return null
        fresh(ctx, k)?.let { return it }
        if (!manual && !enabled(ctx)) return null
        val now = System.currentTimeMillis()
        synchronized(recent) {
            while (recent.isNotEmpty() && now - recent.first() > 3_600_000L) recent.removeFirst()
            if (!manual && recent.size >= MAX_PER_HOUR) {
                setStatus(ctx, "Pause: maks $MAX_PER_HOUR oppslag i timen")
                return null
            }
            val wait = lastRequest + MIN_GAP_MS - now
            if (wait > 0) Thread.sleep(wait)
            lastRequest = System.currentTimeMillis()
            recent.addLast(lastRequest)
        }
        var anyAnswer = false
        for ((source, fn) in listOf<Pair<String, (String) -> String?>>("1881.no" to ::from1881, "gulesider.no" to ::fromGulesider)) {
            if ((blockedUntil[source] ?: 0L) > System.currentTimeMillis()) continue
            try {
                val name = fn(k)
                anyAnswer = true
                if (name != null) {
                    val r = Result(name, source, System.currentTimeMillis())
                    synchronized(this) { load(ctx)[k] = r; save(ctx) }
                    EventLog.add(ctx, EventLog.LOOKUP, "Funnet via $source for ${Phone.masked(k)}: $name")
                    setStatus(ctx, "OK – siste treff via $source")
                    return r
                }
            } catch (b: Blocked) {
                blockedUntil[source] = System.currentTimeMillis() + BLOCK_PAUSE_MS
                EventLog.add(ctx, EventLog.LOOKUP, "$source blokkerte oppslaget (${b.message}) – pause ${BLOCK_PAUSE_MS / 3_600_000} t")
                setStatus(ctx, "$source blokkerer (${b.message}); pause ${BLOCK_PAUSE_MS / 3_600_000} t")
            } catch (e: Exception) {
                EventLog.add(ctx, EventLog.LOOKUP, "$source: feil for ${Phone.masked(k)}: ${e.javaClass.simpleName} ${e.message ?: ""}")
            }
        }
        if (!anyAnswer) return null
        val r = Result(null, "1881.no", System.currentTimeMillis())
        synchronized(this) { load(ctx)[k] = r; save(ctx) }
        EventLog.add(ctx, EventLog.LOOKUP, "Ingen treff for ${Phone.masked(k)}")
        return r
    }

    private fun setStatus(ctx: Context, s: String) = LogStore.prefs(ctx).edit().putString(KEY_STATUS, s).apply()

    private class Blocked(msg: String) : Exception(msg)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        c.setRequestProperty("Accept-Language", "nb-NO,nb;q=0.9,no;q=0.8")
        try {
            val code = c.responseCode
            if (code == 403 || code == 429 || code == 503) throw Blocked("HTTP $code")
            if (code != 200) throw java.io.IOException("HTTP $code")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            if (body.contains("Just a moment...") || body.contains("cf-challenge") || body.contains("captcha", ignoreCase = true) && body.length < 20_000)
                throw Blocked("utfordringsside")
            return body
        } finally { c.disconnect() }
    }

    /** 1881.no: name of the result box whose tel: link is exactly this number. */
    internal fun parse1881(html: String, k: String): String? {
        val boxes = html.split("class=\"box listing").drop(1)
        if (boxes.isEmpty()) return null
        val nameRe = Regex("class=\"listing-name\">\\s*<a[^>]*>([^<]+)</a>")
        val tel = "tel:0047$k"
        for (b in boxes) {
            val n = nameRe.find(b)?.groupValues?.get(1) ?: continue
            if (b.contains(tel) || b.contains("tel:$k") || b.contains("tel:+47$k")) return unescape(n.trim())
        }
        // A person may be listed without a tel: link; accept a single result box only
        if (boxes.size == 1) nameRe.find(boxes[0])?.groupValues?.get(1)?.let { return unescape(it.trim()) }
        return null
    }

    private fun from1881(k: String): String? = parse1881(get("https://www.1881.no/?query=$k"), k)

    /** gulesider.no (best effort): the JSON-LD object whose "telephone" is this number gives the name. */
    internal fun parseGulesider(html: String, k: String): String? {
        val telRe = Regex("\"telephone\"\\s*:\\s*\"([^\"]+)\"")
        val nameRe = Regex("\"name\"\\s*:\\s*\"([^\"]{2,80})\"")
        for (chunk in html.split("\"@type\"").drop(1)) {
            val tel = telRe.find(chunk)?.groupValues?.get(1) ?: continue
            if (Phone.key(tel) != k) continue
            val n = nameRe.find(chunk)?.groupValues?.get(1) ?: continue
            if (!n.contains("gulesider", true)) return unescape(n)
        }
        return null
    }

    private fun fromGulesider(k: String): String? = parseGulesider(get("https://www.gulesider.no/$k/hva"), k)

    private fun unescape(s: String): String {
        var t = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        t = Regex("&#(\\d+);").replace(t) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value }
        t = Regex("&#x([0-9a-fA-F]+);").replace(t) { m -> m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value }
        t = Regex("\\\\u([0-9a-fA-F]{4})").replace(t) { m -> m.groupValues[1].toInt(16).toChar().toString() }
        return t.trim()
    }
}
