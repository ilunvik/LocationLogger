package no.grokbot.stedslogger

import android.content.Context

/**
 * Personal comments on calls, SMS and MMS (1.44). Stored only on the phone in the app-private
 * SharedPreferences file "comments" (allowBackup is off, so it is not copied anywhere); never sent,
 * and the Log only records that a comment was saved or deleted, never its text or the number.
 *
 * Keys are stable per provider row:
 *  - call: "C:<CallLog _ID>:<date ms>:<hash of the normalised number>"
 *  - SMS:  "S:<sms _id>:<type>:<date ms>"
 *  - MMS:  "M:<mms _id>:<type>:<date ms>"
 */
object Comments {
    private const val PREFS = "comments"

    fun key(it: CommItem): String = when (it.kind) {
        CommItem.Kind.CALL -> "C:${it.rowId}:${it.at}:${Integer.toHexString(Phone.key(it.number).hashCode())}"
        CommItem.Kind.SMS -> "S:${it.rowId}:${it.dir}:${it.at}"
        CommItem.Kind.MMS -> "M:${it.mmsId}:${it.dir}:${it.at}"
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context, it: CommItem): String? = prefs(ctx).getString(key(it), null)?.takeIf { s -> s.isNotBlank() }

    /** Saves [text] (trimmed); blank deletes. Returns true if something changed. */
    fun set(ctx: Context, it: CommItem, text: String?): Boolean {
        val k = key(it)
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
