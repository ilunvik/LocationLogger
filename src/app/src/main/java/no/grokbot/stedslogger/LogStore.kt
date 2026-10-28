package no.grokbot.stedslogger

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/** Handles location.txt (Documents/location.txt via MediaStore, or app files dir as fallback) and saved state. */
object LogStore {
    private const val TAG = "LocationLogger"
    const val FILE_NAME = "location.txt"
    const val PREFS = "stedslogger"
    const val KEY_RUNNING = "running"
    const val KEY_LAST_ADDRESS = "last_address"          // place of the latest visit (after the place list)
    const val KEY_LAST_RAW_ADDRESS = "last_raw_address"  // last address exactly as the geocoder gave it
    const val KEY_LAST_CHECK = "last_check"
    const val KEY_FILE_URI = "file_uri"
    const val KEY_LOG_VERSION = "log_version"
    const val KEY_GPS_OFF_BETWEEN = "gps_off_between"
    const val KEY_INTERVAL_MIN = "interval_min"
    const val DEFAULT_INTERVAL_MIN = 5
    const val MIN_INTERVAL_MIN = 1
    const val MAX_INTERVAL_MIN = 1440

    fun gpsOffBetween(ctx: Context) = prefs(ctx).getBoolean(KEY_GPS_OFF_BETWEEN, true)
    fun intervalMin(ctx: Context) =
        prefs(ctx).getInt(KEY_INTERVAL_MIN, DEFAULT_INTERVAL_MIN).coerceIn(MIN_INTERVAL_MIN, MAX_INTERVAL_MIN)

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun fallbackFile(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, FILE_NAME)

    /** Human readable location of the log file. */
    fun describeLocation(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && prefs(ctx).getString(KEY_FILE_URI, null) != null)
            "Internal storage › Documents › $FILE_NAME"
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            "Internal storage › Documents › $FILE_NAME (created on first entry)"
        else fallbackFile(ctx).absolutePath

    private fun findOrCreateMediaUri(ctx: Context, create: Boolean): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val p = prefs(ctx)
        val cr = ctx.contentResolver
        p.getString(KEY_FILE_URI, null)?.let { s ->
            val uri = Uri.parse(s)
            val exists = try {
                cr.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
            } catch (e: Exception) { false }
            if (exists) return uri
            p.edit().remove(KEY_FILE_URI).apply()
        }
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relPath = Environment.DIRECTORY_DOCUMENTS + "/"
        try {
            cr.query(
                collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(relPath, FILE_NAME), null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val uri = Uri.withAppendedPath(collection, c.getLong(0).toString())
                    p.edit().putString(KEY_FILE_URI, uri.toString()).apply()
                    return uri
                }
            }
        } catch (e: Exception) { Log.w(TAG, "query failed", e) }
        if (!create) return null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            }
            cr.insert(collection, values)?.also {
                p.edit().putString(KEY_FILE_URI, it.toString()).apply()
            }
        } catch (e: Exception) { Log.w(TAG, "insert failed", e); null }
    }

    /** Appends one line. Returns true on success. */
    @Synchronized
    fun append(ctx: Context, line: String): Boolean {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val uri = findOrCreateMediaUri(ctx, create = true)
                if (uri != null) {
                    ctx.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                        FileOutputStream(pfd.fileDescriptor).use { out ->
                            val ch = out.channel
                            ch.position(ch.size())
                            out.write(bytes)
                            out.flush()
                        }
                    }
                    bump(ctx)
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore write failed, using fallback", e)
                prefs(ctx).edit().remove(KEY_FILE_URI).apply()
            }
        }
        return try {
            fallbackFile(ctx).appendBytes(bytes)
            bump(ctx)
            true
        } catch (e: Exception) { Log.e(TAG, "fallback write failed", e); false }
    }

    fun bump(ctx: Context) {
        val p = prefs(ctx)
        p.edit().putLong(KEY_LOG_VERSION, p.getLong(KEY_LOG_VERSION, 0) + 1).apply()
    }

    fun readAll(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                findOrCreateMediaUri(ctx, create = false)?.let { uri ->
                    ctx.contentResolver.openInputStream(uri)?.use {
                        return it.readBytes().toString(Charsets.UTF_8)
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "read failed", e) }
        }
        val f = fallbackFile(ctx)
        return if (f.exists()) try { f.readText() } catch (e: Exception) { "" } else ""
    }
}
