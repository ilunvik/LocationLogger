package no.grokbot.stedslogger

import android.content.Context
import java.util.Locale

/**
 * User-editable list that merges several geocoder addresses into one place name.
 *
 * One place per line: `Name;address 1;address 2;...` (e.g. `Home;Example Street 1, 0000 Town`).
 * When a reverse-geocoded address matches any listed address (or the name itself), the name is
 * logged instead. Matching ignores case, surrounding whitespace and repeated spaces.
 * Empty lines and lines starting with `#` are ignored.
 */
object Places {
    const val KEY_PLACES = "places"

    fun text(ctx: Context): String = LogStore.prefs(ctx).getString(KEY_PLACES, "") ?: ""

    fun save(ctx: Context, text: String) {
        LogStore.prefs(ctx).edit().putString(KEY_PLACES, text.trimEnd()).apply()
    }

    fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    /** Maps normalized address -> place name. The first line wins if an address is listed twice. */
    fun parse(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            val name = parts[0]
            for (p in parts) map.putIfAbsent(norm(p), name)
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
    fun count(text: String): Int = text.lines().count { l ->
        val t = l.trim()
        !t.startsWith("#") && t.split(';').count { it.isNotBlank() } >= 2
    }

    /** Returns the place name for [address] if it is listed, otherwise [address] unchanged. */
    fun resolve(ctx: Context, address: String): String = resolve(parse(text(ctx)), address)

    fun resolve(map: Map<String, String>, address: String): String = map[norm(address)] ?: address

    /** Same place after resolving both sides (case/whitespace-insensitive). */
    fun same(map: Map<String, String>, a: String?, b: String?): Boolean =
        a != null && b != null && norm(resolve(map, a)) == norm(resolve(map, b))
}
