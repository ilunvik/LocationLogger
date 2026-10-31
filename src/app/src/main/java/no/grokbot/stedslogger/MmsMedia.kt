package no.grokbot.stedslogger

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/**
 * MMS attachments (1.33): thumbnails, a fullscreen image viewer and "open with" for video / audio / vCard /
 * other parts. Everything is read from Android's MMS store on the phone; a part is only copied (to the app's
 * own cache, deleted after a day) when the viewer app cannot be given access to the MMS store directly.
 */
object MmsMedia {
    private val cache = object : LruCache<Long, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val failed = HashSet<Long>()
    private val loading = HashSet<Long>()
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun cached(part: MmsPart): Bitmap? = cache.get(part.id)

    /** Loads a thumbnail (max [px] on the long side) in the background; [done] runs on the main thread. */
    fun thumb(ctx: Context, part: MmsPart, px: Int, done: (Bitmap?) -> Unit) {
        cache.get(part.id)?.let { done(it); return }
        synchronized(this) { if (part.id in failed || !loading.add(part.id)) return }
        val app = ctx.applicationContext
        pool.execute {
            val b = decode(app, part.uri, px)
            synchronized(this) { loading.remove(part.id); if (b == null) failed += part.id }
            if (b != null) cache.put(part.id, b)
            main.post { done(b) }
        }
    }

    fun decode(ctx: Context, uri: Uri, maxPx: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxPx || o.outHeight / (sample * 2) >= maxPx) sample *= 2
        ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (_: Throwable) { null }

    /** Fullscreen image; tap to close. "Åpne i …" hands it to the gallery / another viewer. */
    fun showImage(act: Activity, part: MmsPart, title: String) {
        val dlg = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(act).apply { setBackgroundColor(Color.BLACK) }
        val img = ImageView(act).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            cached(part)?.let { setImageBitmap(it) }
        }
        root.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val d = act.resources.displayMetrics.density
        root.addView(TextView(act).apply {
            text = "$title   ·   Trykk for å lukke   ·   Åpne i …"
            setTextColor(Color.WHITE); setBackgroundColor(0x88000000.toInt()); textSize = 13f
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            setOnClickListener { dlg.dismiss(); open(act, part) }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        img.setOnClickListener { dlg.dismiss() }
        dlg.setContentView(root)
        dlg.show()
        val px = maxOf(act.resources.displayMetrics.widthPixels, act.resources.displayMetrics.heightPixels)
        Thread {
            val big = decode(act, part.uri, px)
            act.runOnUiThread {
                if (big != null) img.setImageBitmap(big)
                else if (cached(part) == null) { Toast.makeText(act, "Kunne ikke vise bildet", Toast.LENGTH_SHORT).show(); dlg.dismiss() }
            }
        }.start()
    }

    /** True when Android's MMS store lets this app pass a read grant on to another app. */
    private fun mmsGrantable(ctx: Context): Boolean = try {
        ctx.packageManager.resolveContentProvider("mms", 0)?.grantUriPermissions == true
    } catch (_: Exception) { false }

    /** Opens a part (video, audio, vCard, image …) with ACTION_VIEW in an app of the user's choice. */
    fun open(act: Activity, part: MmsPart) {
        val type = part.ct.let { if (it.contains("vcard", true)) "text/x-vcard" else it }
        if (mmsGrantable(act) && tryView(act, part.uri, type, quiet = true)) {
            EventLog.add(act, EventLog.SMS, "MMS-vedlegg åpnet (${type})")
            return
        }
        Thread {
            val uri = try { PartProvider.copy(act, part) } catch (e: Exception) { null }
            act.runOnUiThread {
                if (uri == null) Toast.makeText(act, "Kunne ikke lese vedlegget", Toast.LENGTH_LONG).show()
                else if (tryView(act, uri, type, quiet = false)) EventLog.add(act, EventLog.SMS, "MMS-vedlegg åpnet via kopi (${type})")
            }
        }.start()
    }

    private fun tryView(act: Activity, uri: Uri, type: String, quiet: Boolean): Boolean {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            act.startActivity(Intent.createChooser(view, "Åpne vedlegg med").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            true
        } catch (e: ActivityNotFoundException) {
            if (!quiet) Toast.makeText(act, "Ingen app kan åpne $type", Toast.LENGTH_LONG).show(); false
        } catch (e: SecurityException) {
            if (!quiet) Toast.makeText(act, "Ingen tilgang til vedlegget", Toast.LENGTH_LONG).show(); false
        } catch (e: Exception) { false }
    }
}

/**
 * Minimal read-only file provider (no AndroidX): serves copies of MMS parts from cache/mms to the viewer
 * app the user picks, through a temporary read grant. Not exported; files older than a day are deleted.
 */
class PartProvider : ContentProvider() {
    companion object {
        private const val DIR = "mms"
        fun authority(ctx: Context) = ctx.packageName + ".parts"

        fun copy(ctx: Context, part: MmsPart): Uri {
            val dir = File(ctx.cacheDir, DIR).apply { mkdirs() }
            val cutoff = System.currentTimeMillis() - 24 * 3600_000L
            dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
            val f = File(dir, "vedlegg-${part.id}.${ext(part)}")
            ctx.contentResolver.openInputStream(part.uri)?.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                ?: throw IllegalStateException("no stream")
            return Uri.parse("content://${authority(ctx)}/${f.name}")
        }

        private fun ext(p: MmsPart): String {
            p.name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..5 && it.all { c -> c.isLetterOrDigit() } }?.let { return it }
            return when {
                p.ct.contains("vcard", true) -> "vcf"
                p.ct == "image/jpeg" -> "jpg"
                p.ct == "video/3gpp" -> "3gp"
                p.ct == "audio/amr" -> "amr"
                p.ct == "audio/mpeg" -> "mp3"
                p.ct.contains("calendar") -> "ics"
                else -> p.ct.substringAfter('/', "bin").substringBefore(';').filter { it.isLetterOrDigit() }.take(5).ifEmpty { "bin" }
            }
        }
    }

    private fun file(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.startsWith(".")) return null
        val f = File(File(context!!.cacheDir, DIR), name)
        return f.takeIf { it.isFile }
    }

    override fun onCreate() = true

    override fun getType(uri: Uri): String? {
        val n = uri.lastPathSegment ?: return null
        return Comms.guessType(n) ?: "application/octet-stream"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read only")
        val f = file(uri) ?: throw java.io.FileNotFoundException()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = file(uri) ?: return null
        val cols = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply { addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else f.name }.toTypedArray()) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
