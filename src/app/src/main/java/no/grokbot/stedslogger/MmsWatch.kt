package no.grokbot.stedslogger

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.Telephony

/**
 * Detects newly received MMS (1.33). Only the default SMS app gets WAP_PUSH_DELIVER, and SMS_RECEIVED is
 * never sent for MMS, so the MMS store is watched instead (ContentObserver on content://mms and
 * content://mms-sms/). A new inbox row (msg_box = 1) after the last one seen is logged, and the popup and
 * read-aloud run once its parts have been downloaded (re-checked for up to ~30 s).
 *
 * The highest id seen is kept in prefs, so a message is announced once only, and on the very first start
 * everything already there counts as seen. Messages older than [MAX_AGE_MS] are never announced (restore,
 * sync, a phone that was off). Nothing is sent anywhere and the log never holds the text.
 */
object MmsWatch {
    private const val KEY_LAST_ID = "mms_last_id"
    private const val MAX_AGE_MS = 15 * 60_000L
    private const val RETRY_MS = 5_000L
    private const val MAX_WAIT_MS = 30_000L
    private const val DEBOUNCE_MS = 1_500L

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var observer: ContentObserver? = null
    private lateinit var app: Context
    /** id → first time seen while waiting for the download. */
    private val waiting = HashMap<Long, Long>()

    private val scan = Runnable { scanNow() }

    @Synchronized
    fun register(ctx: Context) {
        if (observer != null) return
        if (!Comms.canReadSms(ctx)) return
        app = ctx.applicationContext
        val t = HandlerThread("mms-watch").apply { start() }
        val h = Handler(t.looper)
        val o = object : ContentObserver(h) {
            override fun onChange(selfChange: Boolean, uri: Uri?) { poke() }
        }
        try {
            app.contentResolver.registerContentObserver(Uri.parse("content://mms"), true, o)
            app.contentResolver.registerContentObserver(Uri.parse("content://mms-sms/"), true, o)
        } catch (e: Exception) {
            EventLog.error(app, "MMS-lytter kunne ikke starte", e); t.quitSafely(); return
        }
        thread = t; handler = h; observer = o
        h.post {
            // First start ever: everything already in the store counts as seen
            if (!LogStore.prefs(app).contains(KEY_LAST_ID)) LogStore.prefs(app).edit().putLong(KEY_LAST_ID, maxInboxId()).apply()
            scanNow()
        }
    }

    @Synchronized
    fun unregister() {
        observer?.let { try { app.contentResolver.unregisterContentObserver(it) } catch (_: Exception) {} }
        observer = null
        thread?.quitSafely()
        thread = null; handler = null
        waiting.clear()
    }

    /** Called by the observer and by WAP_PUSH_RECEIVED: scan shortly (changes come in bursts). */
    fun poke(ctx: Context? = null) {
        if (handler == null && ctx != null) register(ctx)
        val h = handler ?: return
        h.removeCallbacks(scan)
        h.postDelayed(scan, DEBOUNCE_MS)
    }

    private fun maxInboxId(): Long = try {
        app.contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID), null, null, "${Telephony.Mms._ID} DESC")?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        } ?: 0L
    } catch (_: Exception) { 0L }

    private fun scanNow() {
        if (!Comms.canReadSms(app)) return
        val p = LogStore.prefs(app)
        var last = p.getLong(KEY_LAST_ID, 0L)
        val now = System.currentTimeMillis()
        data class Row(val id: Long, val at: Long, val mType: Int, val sub: String?)
        val rows = ArrayList<Row>()
        try {
            app.contentResolver.query(Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_TYPE, Telephony.Mms.SUBJECT, Telephony.Mms.SUBJECT_CHARSET),
                "${Telephony.Mms._ID} > ? AND ${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_INBOX}",
                arrayOf(last.toString()), "${Telephony.Mms._ID} ASC")?.use { c ->
                while (c.moveToNext()) rows += Row(c.getLong(0), c.getLong(1) * 1000L, if (c.isNull(2)) 0 else c.getInt(2),
                    Comms.decodeSubject(c.getString(3), if (c.isNull(4)) 0 else c.getInt(4)))
            }
        } catch (e: Exception) { EventLog.error(app, "Kunne ikke lese nye MMS", e); return }
        // Rows that vanished while waiting (replaced after download) are forgotten
        waiting.keys.retainAll(rows.map { it.id }.toSet())
        for (r in rows) {
            if (r.mType == Comms.MMS_DELIVERY_IND || r.mType == Comms.MMS_READ_ORIG) { last = r.id; continue }
            if (now - r.at > MAX_AGE_MS && r.at > 0) { last = r.id; continue } // old: restore / sync, never announced
            val item = Comms.mmsItem(app, r.id, r.at, Telephony.Mms.MESSAGE_BOX_INBOX, r.mType, r.sub)
            val ready = r.mType != Comms.MMS_NOTIFICATION && (item.parts.isNotEmpty() || item.text != null)
            val since = waiting.getOrPut(r.id) { now }
            if (!ready && now - since < MAX_WAIT_MS) {
                // Not downloaded yet: look again (the observer usually fires first when it is)
                handler?.removeCallbacks(scan)
                handler?.postDelayed(scan, RETRY_MS)
                break // keep order; later ones wait for this one
            }
            waiting.remove(r.id)
            last = r.id
            p.edit().putLong(KEY_LAST_ID, last).apply()
            announce(item, ready)
        }
        if (last > p.getLong(KEY_LAST_ID, 0L)) p.edit().putLong(KEY_LAST_ID, last).apply()
    }

    private fun announce(item: CommItem, downloaded: Boolean) {
        val from = item.number
        val text = item.text?.takeIf { downloaded }
        val att = Comms.attachmentSummary(item.parts)
        val group = if (item.others.size > 1) " · gruppe med ${item.others.size + 1}" else ""
        EventLog.add(app, EventLog.SMS, "MMS fra ${Announcer.who(app, from)} (${text?.length ?: 0} tegn" +
            (att?.let { ", $it" } ?: "") + (if (!downloaded) ", ikke lastet ned" else "") + ")$group")
        if (NumberLookup.enabled(app) && NumberLookup.needsLookup(app, from)) {
            val r = try { NumberLookup.lookup(app, from) } catch (_: Exception) { null }
            Contacts.maybeAutoAdd(app, from, r)
        }
        Announcer.popupMms(app, from, text, att, item.subject, item.images.firstOrNull())
        Announcer.announceMms(app, from, text, att)
    }
}
