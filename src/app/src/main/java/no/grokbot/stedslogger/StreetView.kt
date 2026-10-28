package no.grokbot.stedslogger

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Street View images for stops (Street View Static API). The API key is entered in Settings and
 * kept only in the app's preferences on this device; it is never logged.
 *
 * Results are cached in files/streetview/ by coordinates rounded to 5 decimals:
 * `<lat>_<lon>.jpg` for an image, `<lat>_<lon>.none` when Google has no outdoor panorama
 * there (metadata ZERO_RESULTS / NOT_FOUND), so each spot is fetched only once.
 * Other failures (bad key, quota, network) are not cached on disk, only remembered until the
 * app restarts or the key is changed, so they do not hammer the API.
 */
object StreetView {
    private const val TAG = "LocationLogger"
    private const val KEY_API_KEY = "streetview_api_key"
    private const val DIR = "streetview"
    private const val TIMEOUT_MS = 15_000

    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val decodeOpts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
    private val memory = object : android.util.LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val none = HashSet<String>()          // no image (cached on disk or failed this session)
    private val pending = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    fun apiKey(ctx: Context): String? =
        LogStore.prefs(ctx).getString(KEY_API_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    /** Saves (or with an empty value removes) the key. Session failures are forgotten. */
    fun saveKey(ctx: Context, key: String) {
        val k = key.trim()
        LogStore.prefs(ctx).edit().apply { if (k.isEmpty()) remove(KEY_API_KEY) else putString(KEY_API_KEY, k) }.apply()
        // Only ZERO_RESULTS/NOT_FOUND are cached on disk and they do not depend on the key
        synchronized(this) { none.clear() }
    }

    /** Cache key for coordinates rounded to 5 decimals (about 1 m). */
    fun cacheKey(lat: Double, lon: Double) = String.format(Locale.US, "%.5f_%.5f", lat, lon)

    /** Map link with a pin (opens the Google Maps app if installed, otherwise the browser). */
    fun mapsUrl(lat: Double, lon: Double) =
        String.format(Locale.US, "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f", lat, lon)

    /** SHA-1 of this app's signing certificate, uppercase hex without colons. */
    fun certSha1(ctx: Context): String? = try {
        val pm = ctx.packageManager
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            if (info == null) null
            else if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        certs?.firstOrNull()?.let { sig ->
            MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString("") { "%02X".format(it) }
        }
    } catch (e: Exception) { null }

    /** "71:FD:…" form for display. */
    fun certSha1Display(ctx: Context): String? = certSha1(ctx)?.chunked(2)?.joinToString(":")

    /** Image already in memory, if any (so rebuilt rows show it at once). */
    @Synchronized
    fun cached(key: String): Bitmap? = memory.get(key)

    @Synchronized
    fun knownNone(key: String) = key in none

    /**
     * Loads the image for [lat],[lon] off the main thread; [cb] runs on the main thread with
     * the bitmap, or null if there is none. Does nothing (cb(null)) without an API key.
     */
    fun load(ctx: Context, lat: Double, lon: Double, cb: (Bitmap?) -> Unit) {
        val app = ctx.applicationContext
        val apiKey = apiKey(app) ?: run { cb(null); return }
        val key = cacheKey(lat, lon)
        synchronized(this) {
            memory.get(key)?.let { cb(it); return }
            if (key in none) { cb(null); return }
            pending[key]?.let { it += cb; return }
            pending[key] = mutableListOf(cb)
        }
        executor.execute {
            val bmp = try { fetch(app, apiKey, key, lat, lon) } catch (e: Exception) {
                Log.w(TAG, "Street View failed: ${e.javaClass.simpleName}")  // never log URLs (contain the key)
                synchronized(this) { none += key }
                null
            }
            val cbs = synchronized(this) {
                if (bmp != null) memory.put(key, bmp)
                pending.remove(key) ?: mutableListOf()
            }
            main.post { cbs.forEach { it(bmp) } }
        }
    }

    private fun fetch(ctx: Context, apiKey: String, key: String, lat: Double, lon: Double): Bitmap? {
        val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
        val img = File(dir, "$key.jpg")
        val noneFile = File(dir, "$key.none")
        if (noneFile.exists()) { synchronized(this) { none += key }; return null }
        if (img.exists()) BitmapFactory.decodeFile(img.absolutePath, decodeOpts)?.let { return it }

        val loc = String.format(Locale.US, "%.6f,%.6f", lat, lon)
        val k = java.net.URLEncoder.encode(apiKey, "UTF-8")
        val common = "location=$loc&radius=50&source=outdoor&key=$k"
        val meta = JSONObject(String(get(ctx, "https://maps.googleapis.com/maps/api/streetview/metadata?$common"), Charsets.UTF_8))
        when (val status = meta.optString("status")) {
            "OK" -> {}
            "ZERO_RESULTS", "NOT_FOUND" -> { noneFile.writeText(status); synchronized(this) { none += key }; return null }
            else -> { Log.w(TAG, "Street View metadata: $status"); synchronized(this) { none += key }; return null }
        }
        val bytes = get(ctx, "https://maps.googleapis.com/maps/api/streetview?size=640x320&$common")
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            ?: run { synchronized(this) { none += key }; return null }
        val tmp = File(dir, "$key.jpg.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(img)
        return bmp
    }

    private fun get(ctx: Context, url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("X-Android-Package", ctx.packageName)
            certSha1(ctx)?.let { c.setRequestProperty("X-Android-Cert", it) }
            val code = c.responseCode
            if (code != 200) throw java.io.IOException("HTTP $code")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }
}
