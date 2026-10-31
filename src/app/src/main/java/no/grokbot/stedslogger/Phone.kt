package no.grokbot.stedslogger

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/** Phone number helpers and contact-name lookup (1.32). Everything stays on the phone. */
object Phone {
    /**
     * Comparable key for a number: digits only (leading + kept for non-Norwegian numbers);
     * Norwegian numbers (+47 / 0047 prefix) become their 8 national digits. Empty for hidden numbers.
     */
    fun key(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val plus = raw.trim().startsWith("+")
        var d = raw.filter { it.isDigit() }
        if (d.isEmpty()) return ""
        if (plus && d.startsWith("47") && d.length == 10) d = d.substring(2)
        else if (!plus && d.startsWith("0047") && d.length == 12) d = d.substring(4)
        else if (plus) return "+$d"
        return d
    }

    /** True for an ordinary Norwegian 8-digit number (what the directory can look up). */
    fun isNorwegian(key: String) = key.length == 8 && key[0] in '2'..'9'

    /** Alphanumeric sender ("DNB", "Posten") as is, else null. */
    fun alpha(raw: String?): String? = raw?.trim()?.takeIf { t -> t.any { it.isLetter() } }

    fun isHidden(raw: String?) = key(raw).isEmpty() || raw?.trim()?.startsWith("-") == true

    /** "912 34 567" / "22 33 44 55" style for display; others unchanged. */
    fun pretty(raw: String?): String {
        val k = key(raw)
        alpha(raw)?.let { return it }
        if (k.isEmpty()) return "Skjult nummer"
        if (isNorwegian(k)) {
            return if (k[0] == '4' || k[0] == '9') "${k.substring(0, 3)} ${k.substring(3, 5)} ${k.substring(5)}"
            else "${k.substring(0, 2)} ${k.substring(2, 4)} ${k.substring(4, 6)} ${k.substring(6)}"
        }
        return raw!!.trim()
    }

    /** Last digits only, for the Log tab (so a shared log does not carry whole numbers). */
    fun masked(raw: String?): String {
        val k = key(raw)
        alpha(raw)?.let { return it }
        if (k.isEmpty()) return "skjult nummer"
        return "…" + k.takeLast(3)
    }

    /** Digits separated by spaces so text-to-speech reads them one by one. */
    fun spoken(raw: String?): String {
        val k = key(raw)
        alpha(raw)?.let { return it }
        if (k.isEmpty()) return "skjult nummer"
        return k.removePrefix("+").toCharArray().joinToString(" ")
    }

    private val contactCache = HashMap<String, String?>()

    fun clearCache() = synchronized(contactCache) { contactCache.clear() }

    /** Display name of the contact with this number, or null (also null without READ_CONTACTS). */
    fun contactName(ctx: Context, raw: String?): String? {
        val k = key(raw)
        if (k.isEmpty()) return null
        synchronized(contactCache) { if (contactCache.containsKey(k)) return contactCache[k] }
        val name = try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(raw!!.trim()))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: SecurityException) { return null } catch (_: Exception) { null }
        synchronized(contactCache) { contactCache[k] = name }
        return name
    }
}
