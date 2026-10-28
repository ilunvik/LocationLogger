package no.grokbot.stedslogger

import android.content.Context
import java.util.Locale

/**
 * User-editable list that merges several geocoder addresses into one place name.
 *
 * One place per line: `Name;address 1;address 2;...` (e.g. `Home;Example Street 1, 0000 Town`).
 * When a reverse-geocoded address matches any listed address (or the name itself), the name is
 * logged instead. Matching ignores case, punctuation and extra/no-break spaces; a listed address also
 * matches the same street when the postcode/town is missing on one side or the postcodes agree.
 * Stored visits are re-labelled with the current list when shown ([resolve] at display time).
 * Empty lines and lines starting with `#` are ignored.
 */
object Places {
    const val KEY_PLACES = "places"

    fun text(ctx: Context): String = LogStore.prefs(ctx).getString(KEY_PLACES, "") ?: ""

    fun save(ctx: Context, text: String) {
        LogStore.prefs(ctx).edit().putString(KEY_PLACES, text.trimEnd()).apply()
    }

    /**
     * Normal form for matching: lower case, any kind of space (incl. no-break spaces) collapsed to
     * one, punctuation dropped except the commas that separate street and postcode/town, and
     * spaces around commas evened out. Letters such as æ, ø, å are kept.
     */
    fun norm(s: String): String = clean(s)
        .replace(Regex("[\\s\\p{Z}]+"), " ")
        .replace(Regex("[^\\p{L}\\p{N} ,]"), "")
        .replace(Regex(" *, *"), ", ")
        .replace(Regex("(, )+"), ", ")
        .trim(' ', ',')
        .replace(Regex(" +"), " ")
        .lowercase(Locale.ROOT)

    /**
     * Key for a Place's stored image: the 1.16 normal form (trim, collapse whitespace, lower case),
     * kept unchanged so images saved by earlier versions keep their files.
     */
    fun imageKey(name: String): String = clean(name).trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    /** Removes invisible characters (BOM, zero-width spaces, CR) and turns no-break spaces into spaces. */
    fun clean(s: String): String = s
        .replace(Regex("[\\uFEFF\\u200B-\\u200D\\u2060\\r]"), "")
        .replace(Regex("[\\u00A0\\u2007\\u202F]"), " ")

    /** Street part (before the first comma) of a normalized address. */
    private fun street(n: String) = n.substringBefore(',').trim()

    /** Postcode/town part (after the first comma) of a normalized address; "" if missing. */
    private fun rest(n: String) = if (',' in n) n.substringAfter(',').trim() else ""

    private val POSTCODE = Regex("\\b\\d{4,5}\\b")

    /**
     * Postcode/town of two addresses agree: equal, missing on one side, or the same postcode
     * (the geocoder may name the town or municipality differently).
     */
    private fun restMatches(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty() || a == b) return true
        val pa = POSTCODE.find(a)?.value
        val pb = POSTCODE.find(b)?.value
        return pa != null && pa == pb
    }

    /** Splits a line into its trimmed, non-empty parts (`;`, full-width `；` or tab separate parts). */
    private fun parts(line: String): List<String> =
        clean(line).split(Regex("[;\uFF1B\t]")).map { it.trim() }.filter { it.isNotEmpty() }

    /** Looks like an address: has a street number or a 4–5 digit postcode. */
    private fun looksLikeAddress(s: String) = Regex("\\d").containsMatchIn(s)

    /**
     * Name of a line: the first part (`Name;address;...`). Tolerates the reversed order
     * `address;Name` when the first part looks like an address and the last part does not.
     */
    private fun nameOf(ps: List<String>): String =
        if (ps.size >= 2 && looksLikeAddress(ps.first()) && !looksLikeAddress(ps.last())) ps.last() else ps.first()

    /** Maps normalized address -> place name. The first line wins if an address is listed twice. */
    fun parse(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (raw in clean(text).split('\n')) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val ps = parts(line)
            if (ps.isEmpty()) continue
            val name = nameOf(ps)
            for (p in ps) norm(p).takeIf { it.isNotEmpty() }?.let { map.putIfAbsent(it, name) }
        }
        return map
    }

    /** Place names (first part of each line), in list order, without duplicates. */
    fun names(text: String): List<String> =
        parse(text).values.distinctBy { norm(it) }

    /** The listed place name matching [name] (case/whitespace-insensitive), or null if [name] is not a place. */
    fun placeName(map: Map<String, String>, name: String): String? =
        map.values.firstOrNull { norm(it) == norm(name) }

    /** Number of valid place lines (a name plus at least one address). */
    fun count(text: String): Int = clean(text).split('\n').count { l ->
        val t = l.trim()
        !t.startsWith("#") && parts(t).size >= 2
    }

    /** Returns the place name for [address] if it is listed, otherwise [address] unchanged. */
    fun resolve(ctx: Context, address: String): String = resolve(parse(text(ctx)), address)

    /**
     * Place name for [address]: an exact (normalized) match first; otherwise a listed address with
     * the same street part (before the first comma) whose postcode/town matches or is missing on
     * one side (e.g. "Example Street 1" matches "Example Street 1, 0000 Town").
     */
    fun resolve(map: Map<String, String>, address: String): String {
        val n = norm(address)
        if (n.isEmpty()) return address
        map[n]?.let { return it }
        val st = street(n)
        val rs = rest(n)
        if (st.isEmpty()) return address
        for ((k, name) in map) {
            if (street(k) == st && restMatches(rest(k), rs)) return name
        }
        return address
    }

    /**
     * Label shown for a stored visit: its name resolved with the *current* list, so stops logged
     * before a Place was added (or renamed) show the Place name right away.
     */
    fun label(map: Map<String, String>, stored: String): String = resolve(map, stored)

    /** Same place after resolving both sides (case/whitespace-insensitive). */
    fun same(map: Map<String, String>, a: String?, b: String?): Boolean =
        a != null && b != null && norm(resolve(map, a)) == norm(resolve(map, b))
}
