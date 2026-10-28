package no.grokbot.stedslogger

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/** Saved state (preferences), and the one-time removal of the old text log. */
object LogStore {
    private const val TAG = "LocationLogger"
    /** Old text log written by versions <= 1.6; only used to delete it. */
    private const val FILE_NAME = "location.txt"
    const val PREFS = "stedslogger"
    const val KEY_RUNNING = "running"
    const val KEY_LAST_ADDRESS = "last_address"          // place of the latest visit (after the place list)
    const val KEY_LAST_RAW_ADDRESS = "last_raw_address"  // last address exactly as the geocoder gave it
    const val KEY_LAST_CHECK = "last_check"
    const val KEY_LAST_CHECK_AT = "last_check_at"    // when KEY_LAST_CHECK was last written (ms)
    private const val KEY_FILE_URI = "file_uri"
    private const val KEY_IMPORTED_FROM_TEXT = "visits_imported_from_text"
    private const val KEY_TEXT_LOG_DELETED = "text_log_deleted"
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

    /**
     * One-time cleanup (1.9): deletes the old text log location.txt that versions <= 1.6 wrote,
     * from every place the app ever wrote it:
     *  - Documents/location.txt (Android 10+, created by this app via MediaStore; only entries
     *    owned by this app are matched, so files from other apps are never touched),
     *  - <app-specific external dir>/files/location.txt (Android/data/no.grokbot.stedslogger/files/),
     *  - <app-private files dir>/location.txt (fallback when no external dir was available).
     * Visits (visits.json) are not touched. Retried on the next start if something failed.
     */
    @Synchronized
    fun deleteOldTextLogsOnce(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean(KEY_TEXT_LOG_DELETED, false)) return
        var ok = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = ctx.contentResolver
            try {
                val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val sel = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
                    "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND " +
                    "(${MediaStore.MediaColumns.DISPLAY_NAME}=? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?)"
                val args = arrayOf(Environment.DIRECTORY_DOCUMENTS + "/", ctx.packageName, FILE_NAME, "location (%).txt")
                val ids = mutableListOf<Long>()
                cr.query(collection, arrayOf(MediaStore.MediaColumns._ID), sel, args, null)?.use { c ->
                    while (c.moveToNext()) ids += c.getLong(0)
                }
                for (id in ids) {
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    try {
                        cr.delete(uri, null, null)
                        Log.i(TAG, "deleted old text log $uri")
                    } catch (e: Exception) { ok = false; Log.w(TAG, "delete $uri failed", e) }
                }
            } catch (e: Exception) { ok = false; Log.w(TAG, "text log query failed", e) }
        }
        val files = listOfNotNull(ctx.getExternalFilesDir(null), ctx.filesDir).map { File(it, FILE_NAME) }
        for (f in files) {
            if (f.exists() && !f.delete()) { ok = false; Log.w(TAG, "could not delete ${f.absolutePath}") }
        }
        if (ok) p.edit().putBoolean(KEY_TEXT_LOG_DELETED, true)
            .remove(KEY_FILE_URI).remove(KEY_IMPORTED_FROM_TEXT).apply()
    }

    fun bump(ctx: Context) {
        val p = prefs(ctx)
        p.edit().putLong(KEY_LOG_VERSION, p.getLong(KEY_LOG_VERSION, 0) + 1).apply()
    }
}
