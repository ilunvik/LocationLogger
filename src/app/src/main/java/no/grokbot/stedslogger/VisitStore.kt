package no.grokbot.stedslogger

import android.content.Context
import android.location.Location
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** One stop. [departure] is the last check still at this place (null = unknown, e.g. imported by an older version). */
data class Visit(
    val name: String,
    val arrival: Long,
    var departure: Long?,
    val lat: Double?,
    val lon: Double?,
    val imported: Boolean = false,
    /** Travelled distance in metres from leaving the previous stop to arriving here (null = unknown). */
    val travelledM: Double? = null,
)

/**
 * Visits (stops), stored as JSON in the app-private files dir (visits.json), oldest first.
 * The last visit is the current one while logging continues.
 */
object VisitStore {
    private const val TAG = "LocationLogger"
    private const val FILE = "visits.json"

    // Candidate place: seen once, not yet confirmed
    private const val KEY_CAND_NAME = "pending_address"
    private const val KEY_CAND_AT = "pending_at"
    private const val KEY_CAND_LAT = "pending_lat"
    private const val KEY_CAND_LON = "pending_lon"

    // Travelled distance: sum of straight legs between consecutive accurate fixes since the
    // last fix at the current stop.
    private const val KEY_TRIP_M = "trip_m"
    private const val KEY_TRIP_LEGS = "trip_legs"
    private const val KEY_FIX_LAT = "trip_fix_lat"
    private const val KEY_FIX_LON = "trip_fix_lon"
    /** Fixes less accurate than this are ignored for the travelled distance. */
    const val MAX_ACCURACY_M = 100f

    private var cache: MutableList<Visit>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    @Synchronized
    fun all(ctx: Context): List<Visit> = ArrayList(load(ctx))

    @Synchronized
    fun current(ctx: Context): Visit? = load(ctx).lastOrNull()

    private fun load(ctx: Context): MutableList<Visit> {
        cache?.let { return it }
        val list = mutableListOf<Visit>()
        val f = file(ctx)
        if (f.exists()) {
            try {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list += Visit(
                        name = o.getString("name"),
                        arrival = o.getLong("arrival"),
                        departure = if (o.has("departure")) o.getLong("departure") else null,
                        lat = if (o.has("lat")) o.getDouble("lat") else null,
                        lon = if (o.has("lon")) o.getDouble("lon") else null,
                        imported = o.optBoolean("imported", false),
                        travelledM = if (o.has("travelled_m")) o.getDouble("travelled_m") else null,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "could not read $FILE", e)
                // Keep the unreadable file aside instead of overwriting it
                f.renameTo(File(ctx.filesDir, "$FILE.broken-${System.currentTimeMillis()}"))
            }
        }
        cache = list
        return list
    }

    private fun save(ctx: Context, list: List<Visit>) {
        val arr = JSONArray()
        for (v in list) {
            arr.put(JSONObject().apply {
                put("name", v.name)
                put("arrival", v.arrival)
                v.departure?.let { put("departure", it) }
                v.lat?.let { put("lat", it) }
                v.lon?.let { put("lon", it) }
                if (v.imported) put("imported", true)
                v.travelledM?.let { put("travelled_m", it) }
            })
        }
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file(ctx))) { file(ctx).writeText(arr.toString()); tmp.delete() }
        LogStore.bump(ctx)
    }

    /** Result of one successful check, for the status line. */
    enum class Outcome { SAME_PLACE, CANDIDATE, NEW_VISIT }

    /**
     * Applies one successful check: [place] is already mapped through the place list.
     * - same place as the current visit: extend it (departure = this check), drop any candidate
     * - same as the candidate: start a new visit, arrival = the candidate's first sighting
     * - otherwise: remember as candidate (first sighting)
     */
    @Synchronized
    fun onCheck(ctx: Context, place: String, at: Long, loc: Location): Outcome {
        val lat = loc.latitude
        val lon = loc.longitude
        val accurate = isAccurate(loc)
        val p = LogStore.prefs(ctx)
        val places = Places.parse(Places.text(ctx))
        val list = load(ctx)
        val cur = list.lastOrNull()
        if (cur != null && Places.same(places, cur.name, place)) {
            cur.departure = at
            save(ctx, list)
            clearCandidate(ctx)
            // Still at the stop: anything accumulated since the last fix here was jitter or a detour
            resetTrip(ctx, if (accurate) loc else null)
            return Outcome.SAME_PLACE
        }
        val candName = p.getString(KEY_CAND_NAME, null)
        val candAt = p.getLong(KEY_CAND_AT, 0L)
        if (candName != null && candAt > 0 && Places.same(places, candName, place)) {
            val cLat = p.getString(KEY_CAND_LAT, null)?.toDoubleOrNull()
            val cLon = p.getString(KEY_CAND_LON, null)?.toDoubleOrNull()
            // Coordinates: average of the first sighting and the confirming fix
            val vLat = if (cLat != null) (cLat + lat) / 2 else lat
            val vLon = if (cLon != null) (cLon + lon) / 2 else lon
            // Distance up to the first sighting (already added); the confirming fix is at the new stop
            val travelled = if (p.getInt(KEY_TRIP_LEGS, 0) > 0)
                p.getString(KEY_TRIP_M, null)?.toDoubleOrNull() ?: 0.0 else null
            list += Visit(place, candAt, at, vLat, vLon, travelledM = travelled)
            save(ctx, list)
            clearCandidate(ctx)
            resetTrip(ctx, if (accurate) loc else null)
            p.edit().putString(LogStore.KEY_LAST_ADDRESS, place).apply()
            return Outcome.NEW_VISIT
        }
        if (accurate) addLeg(ctx, loc)
        p.edit()
            .putString(KEY_CAND_NAME, place)
            .putLong(KEY_CAND_AT, at)
            .putString(KEY_CAND_LAT, lat.toString())
            .putString(KEY_CAND_LON, lon.toString())
            .apply()
        return Outcome.CANDIDATE
    }

    fun candidate(ctx: Context): Pair<String, Long>? {
        val p = LogStore.prefs(ctx)
        val n = p.getString(KEY_CAND_NAME, null) ?: return null
        val t = p.getLong(KEY_CAND_AT, 0L)
        return if (t > 0) n to t else null
    }

    private fun clearCandidate(ctx: Context) {
        LogStore.prefs(ctx).edit()
            .remove(KEY_CAND_NAME).remove(KEY_CAND_AT).remove(KEY_CAND_LAT).remove(KEY_CAND_LON)
            .apply()
    }

    fun isAccurate(loc: Location) = !loc.hasAccuracy() || loc.accuracy <= MAX_ACCURACY_M

    /** A fix without an address (geocoder failed): still counts towards the travelled distance. */
    @Synchronized
    fun onFixOnly(ctx: Context, loc: Location) {
        if (isAccurate(loc)) addLeg(ctx, loc)
    }

    /** Adds the leg from the previous fix to [loc] and makes [loc] the previous fix. */
    private fun addLeg(ctx: Context, loc: Location) {
        val p = LogStore.prefs(ctx)
        val pLat = p.getString(KEY_FIX_LAT, null)?.toDoubleOrNull()
        val pLon = p.getString(KEY_FIX_LON, null)?.toDoubleOrNull()
        val e = p.edit()
        if (pLat != null && pLon != null) {
            val r = FloatArray(1)
            Location.distanceBetween(pLat, pLon, loc.latitude, loc.longitude, r)
            val trip = (p.getString(KEY_TRIP_M, null)?.toDoubleOrNull() ?: 0.0) + r[0]
            e.putString(KEY_TRIP_M, trip.toString()).putInt(KEY_TRIP_LEGS, p.getInt(KEY_TRIP_LEGS, 0) + 1)
        }
        e.putString(KEY_FIX_LAT, loc.latitude.toString()).putString(KEY_FIX_LON, loc.longitude.toString()).apply()
    }

    /** Starts a new trip at the current stop; [anchor] (if accurate) becomes the previous fix. */
    private fun resetTrip(ctx: Context, anchor: Location?) {
        val e = LogStore.prefs(ctx).edit().remove(KEY_TRIP_M).remove(KEY_TRIP_LEGS)
        if (anchor != null) {
            e.putString(KEY_FIX_LAT, anchor.latitude.toString()).putString(KEY_FIX_LON, anchor.longitude.toString())
        }
        e.apply()
    }
}
