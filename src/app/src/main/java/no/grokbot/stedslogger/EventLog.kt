package no.grokbot.stedslogger

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/** One line in the debug log. */
data class LogEntry(val at: Long, val type: String, val msg: String)

/**
 * Lightweight on-device debug log (since 1.25), shown in the Log tab. Stored only in the app-private
 * files dir (events.log), one tab-separated line per entry: `epochMs \t TYPE \t message`.
 * Capped at [MAX] lines (trimmed to [MAX] when it grows past [MAX] + [SLACK]). Writes run on a
 * single background thread, so callers on the main thread are never blocked. Nothing leaves the phone.
 */
object EventLog {
    private const val TAG = "LocationLogger"
    private const val FILE = "events.log"
    const val MAX = 4000
    private const val SLACK = 500

    // Types (shown in the Log tab, also used by the filter)
    const val CHECK = "SJEKK"      // periodic / manual check started and its result
    const val FIX = "POSISJON"     // every raw location fix (provider, accuracy, age, coordinates)
    const val VISIT = "BESØK"      // visit outcome (same place / candidate / new visit)
    const val ADB = "ADB"          // dashboard reports and wireless-debugging changes
    const val SHIZUKU = "SHIZUKU"  // Shizuku running/stopped (since 1.27)
    const val NET = "NETT"         // network changes
    const val SERVICE = "TJENESTE" // service start/stop, boot, alarm restarts
    const val ERROR = "FEIL"       // errors and exceptions

    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var lines = -1

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    fun add(ctx: Context, type: String, msg: String, e: Throwable? = null) {
        val at = System.currentTimeMillis()
        val text = if (e != null) "$msg: ${e.javaClass.simpleName}${e.message?.let { " – $it" } ?: ""}" else msg
        val app = ctx.applicationContext
        try { io.execute { write(app, at, type, text) } } catch (_: Exception) {}
    }

    fun error(ctx: Context, msg: String, e: Throwable? = null) {
        if (e != null) Log.w(TAG, msg, e) else Log.w(TAG, msg)
        add(ctx, ERROR, msg, e)
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    @Synchronized
    private fun write(ctx: Context, at: Long, type: String, msg: String) {
        try {
            val f = file(ctx)
            if (lines < 0) lines = if (f.exists()) f.bufferedReader().use { r -> r.lineSequence().count() } else 0
            f.appendText("$at\t${clean(type)}\t${clean(msg)}\n")
            lines++
            if (lines > MAX + SLACK) {
                val keep = f.readLines().takeLast(MAX)
                val tmp = File(f.parentFile, "$FILE.tmp")
                tmp.writeText(keep.joinToString("\n", postfix = "\n"))
                if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
                lines = keep.size
            }
        } catch (e: Exception) { Log.w(TAG, "event log write failed", e) }
    }

    /** All entries, oldest first. Do not call on the main thread. */
    @Synchronized
    fun all(ctx: Context): List<LogEntry> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().mapNotNull { l ->
                val p = l.split('\t', limit = 3)
                val t = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                LogEntry(t, p.getOrNull(1) ?: "?", p.getOrNull(2) ?: "")
            }
        } catch (e: Exception) { Log.w(TAG, "event log read failed", e); emptyList() }
    }

    /** Deletes all entries. Do not call on the main thread. */
    @Synchronized
    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (_: Exception) {}
        lines = 0
    }

    /** Runs [block] on the log's writer thread (after pending writes), e.g. to read a consistent view. */
    fun afterPending(block: () -> Unit) { io.execute(block) }
}
