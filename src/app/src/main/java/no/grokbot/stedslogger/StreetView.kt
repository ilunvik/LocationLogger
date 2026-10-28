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
 * Each stop's banner belongs to a [Slot]: a named Place (files/placeimages/p_<hash>.jpg, shared by
 * all its stops) or, for an unnamed stop, its rounded coordinates (files/placeimages/c_<lat>_<lon>.jpg
 * once changed by the user). A `.hidden` marker means "removed, do not refetch"; `.heading` keeps the
 * Street View direction chosen with "Other Street View angle".
 *
 * Street View downloads are cached in files/streetview/ by coordinates rounded to 5 decimals:
 * `<lat>_<lon>.jpg` for an image, `<lat>_<lon>.none` when Google has no outdoor panorama
 * there (metadata ZERO_RESULTS / NOT_FOUND), so each spot is fetched only once.
 * Other failures (bad key, quota, network) are not cached on disk, only remembered until the
 * app restarts or the key is changed, so they do not hammer the API.
 */
object StreetView {
    private const val TAG = "LocationLogger"
    private const val KEY_API_KEY = "streetview_api_key"
    private const val DIR = "streetview"
    private const val IMG_DIR = "placeimages"
    private const val PHOTO_WIDTH = 640
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

    // ---------- images per place / per spot ----------

    /**
     * Where a stop's banner image belongs: a named Place (all its stops share one image), or for
     * an unnamed stop its coordinates rounded to 5 decimals.
     */
    class Slot(val id: String, val label: String)

    fun placeSlot(name: String): Slot {
        val h = MessageDigest.getInstance("SHA-1").digest(Places.imageKey(name).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(20)
        return Slot("p_$h", name)
    }

    fun spotSlot(lat: Double, lon: Double) = Slot("c_" + cacheKey(lat, lon), cacheKey(lat, lon))

    private fun imgDir(ctx: Context) = File(ctx.filesDir, IMG_DIR).apply { mkdirs() }
    private fun slotJpg(ctx: Context, s: Slot) = File(imgDir(ctx), "${s.id}.jpg")
    private fun slotHidden(ctx: Context, s: Slot) = File(imgDir(ctx), "${s.id}.hidden")
    private fun slotHeading(ctx: Context, s: Slot) = File(imgDir(ctx), "${s.id}.heading")

    /** True if this slot has its own stored image (chosen photo or saved Street View). */
    fun hasStored(ctx: Context, s: Slot) = slotJpg(ctx, s).exists()
    fun isHidden(ctx: Context, s: Slot) = slotHidden(ctx, s).exists()

    /** Small thumbnail of a stored slot image (for the Settings list); null if none. */
    fun thumbnail(ctx: Context, s: Slot): Bitmap? {
        val f = slotJpg(ctx, s)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = 4; inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    /** Image already in memory, if any (so rebuilt rows show it at once). */
    @Synchronized
    fun cached(slot: Slot): Bitmap? = memory.get(slot.id)

    @Synchronized
    fun knownNone(slot: Slot) = slot.id in none

    @Synchronized
    private fun forget(slot: Slot) { memory.remove(slot.id); none.remove(slot.id) }

    /**
     * Loads the image for [slot] off the main thread; [cb] runs on the main thread with the bitmap,
     * or null if there is none. Order: hidden → none; stored slot image; otherwise (only with an API
     * key) Street View from [lat],[lon], which for a Place is then saved as the Place's image.
     */
    fun load(ctx: Context, slot: Slot, lat: Double, lon: Double, cb: (Bitmap?) -> Unit) {
        val app = ctx.applicationContext
        val key = slot.id
        synchronized(this) {
            memory.get(key)?.let { cb(it); return }
            if (key in none) { cb(null); return }
            pending[key]?.let { it += cb; return }
            pending[key] = mutableListOf(cb)
        }
        executor.execute {
            val bmp = try { loadSlot(app, slot, lat, lon) } catch (e: Exception) {
                Log.w(TAG, "Street View failed: ${e.javaClass.simpleName}")  // never log URLs (contain the key)
                null
            }
            val cbs = synchronized(this) {
                if (bmp != null) memory.put(key, bmp) else none += key
                pending.remove(key) ?: mutableListOf()
            }
            main.post { cbs.forEach { it(bmp) } }
        }
    }

    private fun loadSlot(ctx: Context, slot: Slot, lat: Double, lon: Double): Bitmap? {
        if (isHidden(ctx, slot)) return null
        val f = slotJpg(ctx, slot)
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath, decodeOpts)?.let { return it }
        val apiKey = apiKey(ctx) ?: return null
        val bytes = spotImage(ctx, apiKey, lat, lon) ?: return null
        if (slot.id.startsWith("p_")) writeAtomic(f, bytes)   // the Place keeps this image from now on
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
    }

    /** Street View for a spot (default direction), via the coordinate cache in files/streetview/. */
    private fun spotImage(ctx: Context, apiKey: String, lat: Double, lon: Double): ByteArray? {
        val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
        val ck = cacheKey(lat, lon)
        val img = File(dir, "$ck.jpg")
        val noneFile = File(dir, "$ck.none")
        if (noneFile.exists()) return null
        if (img.exists()) return img.readBytes()
        val meta = metadata(ctx, apiKey, lat, lon)
        when (val status = meta.optString("status")) {
            "OK" -> {}
            "ZERO_RESULTS", "NOT_FOUND" -> { noneFile.writeText(status); return null }
            else -> { Log.w(TAG, "Street View metadata: $status"); return null }
        }
        val bytes = get(ctx, imageUrl(apiKey, lat, lon, null))
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        if (b.outWidth <= 0) return null
        writeAtomic(img, bytes)
        return bytes
    }

    private fun common(apiKey: String, lat: Double, lon: Double): String {
        val loc = String.format(Locale.US, "%.6f,%.6f", lat, lon)
        val k = java.net.URLEncoder.encode(apiKey, "UTF-8")
        return "location=$loc&radius=50&source=outdoor&key=$k"
    }

    private fun metadata(ctx: Context, apiKey: String, lat: Double, lon: Double) = JSONObject(String(
        get(ctx, "https://maps.googleapis.com/maps/api/streetview/metadata?" + common(apiKey, lat, lon)), Charsets.UTF_8))

    private fun imageUrl(apiKey: String, lat: Double, lon: Double, heading: Int?) =
        "https://maps.googleapis.com/maps/api/streetview?size=640x640&" + common(apiKey, lat, lon) +
            (heading?.let { "&heading=$it" } ?: "")

    private fun writeAtomic(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.writeBytes(bytes); tmp.delete() }
    }

    // ---------- changing a slot's image (long-press menu) ----------

    /** Runs [work] off the main thread, then [done] on the main thread with an error text or null. */
    private fun change(ctx: Context, slot: Slot, done: (String?) -> Unit, work: (Context) -> String?) {
        val app = ctx.applicationContext
        executor.execute {
            val err = try { work(app) } catch (e: Exception) {
                Log.w(TAG, "image change failed: ${e.javaClass.simpleName}")
                "Failed: ${e.javaClass.simpleName}"
            }
            forget(slot)
            main.post { done(err) }
        }
    }

    private fun saveSlot(ctx: Context, slot: Slot, bytes: ByteArray) {
        writeAtomic(slotJpg(ctx, slot), bytes)
        slotHidden(ctx, slot).delete()
    }

    /** Fetches Street View for [slot] from [lat],[lon] with [heading] (null = default view) and stores it. */
    private fun fetchInto(ctx: Context, slot: Slot, lat: Double, lon: Double, heading: Int?): String? {
        val apiKey = apiKey(ctx) ?: return "No API key (see Settings)"
        val meta = metadata(ctx, apiKey, lat, lon)
        val status = meta.optString("status")
        if (status != "OK") return if (status == "ZERO_RESULTS" || status == "NOT_FOUND")
            "No Street View near this stop" else "Street View: $status"
        val bytes = get(ctx, imageUrl(apiKey, lat, lon, heading))
        saveSlot(ctx, slot, bytes)
        if (heading == null) slotHeading(ctx, slot).delete() else slotHeading(ctx, slot).writeText(heading.toString())
        return null
    }

    /** "Other Street View angle": 90° further than the stored heading (first time: from the default view). */
    fun nextAngle(ctx: Context, slot: Slot, lat: Double, lon: Double, done: (String?) -> Unit) = change(ctx, slot, done) { c ->
        val stored = slotHeading(c, slot).takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
        val base = stored ?: apiKey(c)?.let { defaultHeading(c, it, lat, lon) } ?: 0
        fetchInto(c, slot, lat, lon, (base + 90) % 360)
    }

    /** Heading of the default view: from the panorama towards the stop. */
    private fun defaultHeading(ctx: Context, apiKey: String, lat: Double, lon: Double): Int {
        val loc = metadata(ctx, apiKey, lat, lon).optJSONObject("location") ?: return 0
        val r = FloatArray(2)
        android.location.Location.distanceBetween(loc.optDouble("lat"), loc.optDouble("lng"), lat, lon, r)
        return ((r[1].toInt() % 360) + 360) % 360
    }

    /** "Use Street View from this stop": default view from [lat],[lon]. */
    fun fromStop(ctx: Context, slot: Slot, lat: Double, lon: Double, done: (String?) -> Unit) =
        change(ctx, slot, done) { c -> fetchInto(c, slot, lat, lon, null) }

    /** "Remove image": delete it and keep it hidden (no automatic refetch). */
    fun remove(ctx: Context, slot: Slot, done: (String?) -> Unit) = change(ctx, slot, done) { c ->
        slotJpg(c, slot).delete()
        slotHeading(c, slot).delete()
        slotHidden(c, slot).writeText("hidden")
        null
    }

    /** "Choose photo…": decode [uri], fix rotation, center-crop to a square, scale to at most 640×640 and store it. */
    fun fromPhoto(ctx: Context, slot: Slot, uri: android.net.Uri, done: (String?) -> Unit) = change(ctx, slot, done) { c ->
        val cr = c.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return@change "Could not read the image"
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_WIDTH) sample *= 2
        var bmp = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@change "Could not read the image"
        val rotation = try {
            cr.openInputStream(uri)?.use {
                when (android.media.ExifInterface(it).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) { 0f }
        if (rotation != 0f) {
            val m = android.graphics.Matrix().apply { postRotate(rotation) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        // Center-crop to a square, then scale to at most PHOTO_WIDTH × PHOTO_WIDTH
        val side = minOf(bmp.width, bmp.height)
        if (bmp.width != bmp.height) {
            bmp = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        }
        if (side > PHOTO_WIDTH) bmp = Bitmap.createScaledBitmap(bmp, PHOTO_WIDTH, PHOTO_WIDTH, true)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        saveSlot(c, slot, out.toByteArray())
        slotHeading(c, slot).delete()
        null
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
