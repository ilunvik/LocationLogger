package no.grokbot.stedslogger

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Call recordings made by other recorders (1.32), matched to call log entries. Ordinary apps cannot record
 * the other party on modern Android, so the app plays what these have saved:
 *  - Samsung's built-in call recording (Phone app): Recordings/Call/ (One UI 4+) or Call/ (older One UI),
 *    files like "Call recording <name or number>_YYMMDD_HHMMSS.m4a" or "<name>_YYMMDD_HHMMSS.m4a".
 *  - ACR Phone (com.nll.cb): Recordings/ACRPhone/YYYY/MM/DD/<number>/<number>-<dir>-<epoch ms>.m4a.
 *  - This app's experimental recorder (own voice only, [CallRecorderService]): app-specific folder, same name
 *    pattern as ACR Phone.
 * Found through MediaStore (READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE); files are only read, never moved or copied.
 */
object Recordings {
    data class Rec(val uri: Uri, val name: String, val startMs: Long, val durationMs: Long, val numberKey: String?, val source: String)

    private val ACR = Regex("^(\\+?\\d{3,20})-(\\d)-(\\d{13})")
    private val SAMSUNG = Regex("(\\d{6})_(\\d{6})")
    private val EPOCH = Regex("(1\\d{12})")

    fun permission(): String = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE

    fun canRead(ctx: Context) = ctx.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED

    fun ownDir(ctx: Context): File? = ctx.getExternalFilesDir("Samtaleopptak")

    /** All call recordings found, newest first. Do not call on the main thread. */
    fun all(ctx: Context): List<Rec> {
        val out = ArrayList<Rec>()
        if (canRead(ctx)) try {
            val col = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            @Suppress("DEPRECATION") val dataCol = MediaStore.Audio.Media.DATA
            val sel = "($dataCol LIKE ? OR $dataCol LIKE ? OR $dataCol LIKE ? OR $dataCol LIKE ?)"
            val args = arrayOf("%/Recordings/Call/%", "%/Call/%", "%/ACRPhone/%", "%/Recordings/Call recordings/%")
            ctx.contentResolver.query(col, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
                    dataCol, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DURATION),
                sel, args, "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(col, c.getLong(0))
                    val name = c.getString(1) ?: continue
                    val path = c.getString(2) ?: ""
                    val dur = c.getLong(4)
                    val added = c.getLong(3) * 1000L
                    val src = if (path.contains("/ACRPhone/")) "ACR Phone" else "Samsung"
                    parse(uri, name, dur, added, src)?.let { out += it }
                }
            }
        } catch (e: Exception) { EventLog.error(ctx, "Kunne ikke lese samtaleopptak", e) }
        ownDir(ctx)?.listFiles()?.forEach { f ->
            if (f.isFile && f.length() > 0) parse(Uri.fromFile(f), f.name, 0L, f.lastModified(), "LocationLogger (eksperimentell)")?.let { out += it }
        }
        return out.sortedByDescending { it.startMs }
    }

    private fun parse(uri: Uri, name: String, durMs: Long, added: Long, source: String): Rec? {
        ACR.find(name)?.let { m ->
            return Rec(uri, name, m.groupValues[3].toLong(), durMs, Phone.key(m.groupValues[1]).ifEmpty { null }, source)
        }
        SAMSUNG.find(name)?.let { m ->
            val t = try { SimpleDateFormat("yyMMddHHmmss", Locale.ROOT).parse(m.groupValues[1] + m.groupValues[2])?.time } catch (_: Exception) { null }
            if (t != null) {
                val before = name.substring(0, m.range.first).removePrefix("Call recording ").removePrefix("Samtaleopptak ").trim('_', ' ')
                val k = Phone.key(before).takeIf { it.isNotEmpty() && before.count { it.isDigit() } >= before.length - 2 }
                return Rec(uri, name, t, durMs, k, source)
            }
        }
        EPOCH.find(name)?.let { m -> return Rec(uri, name, m.groupValues[1].toLong(), durMs, null, source) }
        // Fallback: the file was added when the recording ended
        return Rec(uri, name, if (durMs > 0) added - durMs else added, durMs, null, source)
    }

    /**
     * The recording(s) for a call: start within [call − 60 s, call end + 90 s]; a recording that carries a
     * number must have the same number.
     */
    fun forCall(recs: List<Rec>, call: CommItem): List<Rec> {
        if (call.kind != CommItem.Kind.CALL) return emptyList()
        val k = Phone.key(call.number)
        val from = call.at - 60_000L
        val to = call.at + call.durationS * 1000L + 90_000L
        return recs.filter { r -> r.startMs in from..to && (r.numberKey == null || k.isEmpty() || r.numberKey == k) }
    }
}
