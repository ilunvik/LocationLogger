package no.grokbot.stedslogger

import android.content.Context

/**
 * Personal comments on calls, SMS and MMS (1.44) and on stops (1.46). Stored only on the phone in the app-private
 * SharedPreferences file "comments" (allowBackup is off, so it is not copied anywhere); never sent,
 * and the Log only records that a comment was saved or deleted, never its text or the number.
 *
 * Keys are stable per provider row:
 *  - call: "C:<CallLog _ID>:<date ms>:<hash of the normalised number>"
 *  - SMS:  "S:<sms _id>:<type>:<date ms>"
 *  - MMS:  "M:<mms _id>:<type>:<date ms>"
 *  - stop (1.46): "V:<arrival ms>:<hash of the coordinates rounded to ~100 m>" – the arrival time never
 *    changes once a stop is confirmed; the hash (not the coordinates) tells apart stops at the same time
 */
object Comments {
    private const val PREFS = "comments"

    fun key(it: CommItem): String = when (it.kind) {
        CommItem.Kind.CALL -> "C:${it.rowId}:${it.at}:${Integer.toHexString(Phone.key(it.number).hashCode())}"
        CommItem.Kind.SMS -> "S:${it.rowId}:${it.dir}:${it.at}"
        CommItem.Kind.MMS -> "M:${it.mmsId}:${it.dir}:${it.at}"
    }

    fun key(v: Visit): String {
        val loc = if (v.lat != null && v.lon != null)
            Integer.toHexString(String.format(java.util.Locale.ROOT, "%.3f,%.3f", v.lat, v.lon).hashCode()) else "-"
        return "V:${v.arrival}:$loc"
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context, it: CommItem): String? = get(ctx, key(it))
    fun get(ctx: Context, v: Visit): String? = get(ctx, key(v))
    private fun get(ctx: Context, k: String): String? = prefs(ctx).getString(k, null)?.takeIf { s -> s.isNotBlank() }

    fun set(ctx: Context, it: CommItem, text: String?): Boolean = set(ctx, key(it), text)
    fun set(ctx: Context, v: Visit, text: String?): Boolean = set(ctx, key(v), text)

    /** Row excerpt of a comment: first line, at most ~60 chars cut at a word boundary, "…" when shortened. */
    fun excerpt(c: String, max: Int = 60): String {
        val first = c.trim().lineSequence().first().trim()
        val more = first.length < c.trim().length
        if (first.length <= max) return if (more) "$first …" else first
        val cut = first.lastIndexOf(' ', max).takeIf { i -> i >= max / 2 } ?: max
        return first.substring(0, cut).trimEnd() + "…"
    }

    /** Saves [text] (trimmed); blank deletes. Returns true if something changed. */
    private fun set(ctx: Context, k: String, text: String?): Boolean {
        val t = text?.trim().orEmpty()
        val old = prefs(ctx).getString(k, null)
        if (t.isEmpty()) {
            if (old == null) return false
            prefs(ctx).edit().remove(k).apply()
        } else {
            if (old == t) return false
            prefs(ctx).edit().putString(k, t).apply()
        }
        return true
    }
}
