package no.grokbot.stedslogger

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.Telephony

/** One call, SMS or MMS for the Samtaler tab (1.32). Read on demand from Android's own providers; never copied or sent anywhere. */
data class CommItem(
    val kind: Kind,
    val dir: Dir,
    val at: Long,
    val number: String?,
    val cachedName: String?,
    /** Call: seconds; SMS/MMS: 0. */
    val durationS: Long = 0,
    val text: String? = null,
    /** MMS (1.33): row id, subject, attachments (no SMIL) and all other participants (group MMS). */
    val mmsId: Long = 0,
    val subject: String? = null,
    val parts: List<MmsPart> = emptyList(),
    val others: List<String> = emptyList(),
) {
    val images get() = parts.filter { it.isImage }
    val attachments get() = parts.filter { !it.isText }
    enum class Kind { CALL, SMS, MMS }
    enum class Dir { IN, OUT, MISSED, REJECTED, BLOCKED, VOICEMAIL, OTHER }
}

/** One MMS part (1.33). Text parts carry [text]; the rest are read through content://mms/part/<id>. */
data class MmsPart(val id: Long, val ct: String, val name: String?, val text: String? = null) {
    val isText get() = ct == "text/plain"
    val isImage get() = ct.startsWith("image/")
    val uri: Uri get() = Uri.parse("content://mms/part/$id")
    /** Chip label: "🎬 Video", "🔊 Lyd", "👤 Kontaktkort", "📎 <navn>". */
    fun label(): String = when {
        isImage -> "🖼 Bilde"
        ct.startsWith("video/") -> "🎬 Video"
        ct.startsWith("audio/") -> "🔊 Lyd"
        ct.contains("vcard", true) -> "👤 Kontaktkort"
        ct.contains("calendar", true) -> "📅 Kalender"
        else -> "📎 " + (name?.takeIf { it.isNotBlank() }?.take(24) ?: ct)
    }
}

object Comms {
    /** PDU message types (m_type): 128 send-req, 130 notification-ind (not downloaded), 132 retrieve-conf, 134 delivery-ind. */
    const val MMS_NOTIFICATION = 130
    const val MMS_DELIVERY_IND = 134
    const val MMS_READ_ORIG = 136
    const val ADDR_FROM = 137
    const val ADDR_TO = 151
    const val ADDR_CC = 130
    const val ADDR_BCC = 129

    /** Summary for popup / read-aloud / log: "med bilde", "med 2 bilder", "med vedlegg" … (null when none). */
    fun attachmentSummary(parts: List<MmsPart>): String? {
        val imgs = parts.count { it.isImage }
        val other = parts.count { !it.isText && !it.isImage }
        return when {
            imgs == 0 && other == 0 -> null
            imgs == 1 && other == 0 -> "med bilde"
            imgs > 1 && other == 0 -> "med $imgs bilder"
            imgs == 0 && parts.any { it.ct.startsWith("video/") } && other == 1 -> "med video"
            imgs == 0 && parts.any { it.ct.startsWith("audio/") } && other == 1 -> "med lydklipp"
            imgs == 0 -> if (other == 1) "med vedlegg" else "med $other vedlegg"
            else -> "med bilde og vedlegg"
        }
    }

    const val MAX_CALLS = 400
    const val MAX_SMS = 400
    const val MAX_MMS = 150

    private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun canReadCalls(ctx: Context) = granted(ctx, android.Manifest.permission.READ_CALL_LOG)
    fun canReadSms(ctx: Context) = granted(ctx, android.Manifest.permission.READ_SMS)

    fun calls(ctx: Context): List<CommItem> {
        if (!canReadCalls(ctx)) return emptyList()
        val out = ArrayList<CommItem>()
        try {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME),
                null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
                while (c.moveToNext() && out.size < MAX_CALLS) {
                    val dir = when (c.getInt(3)) {
                        CallLog.Calls.INCOMING_TYPE -> CommItem.Dir.IN
                        CallLog.Calls.OUTGOING_TYPE -> CommItem.Dir.OUT
                        CallLog.Calls.MISSED_TYPE -> CommItem.Dir.MISSED
                        CallLog.Calls.REJECTED_TYPE -> CommItem.Dir.REJECTED
                        CallLog.Calls.BLOCKED_TYPE -> CommItem.Dir.BLOCKED
                        CallLog.Calls.VOICEMAIL_TYPE -> CommItem.Dir.VOICEMAIL
                        else -> CommItem.Dir.OTHER
                    }
                    out += CommItem(CommItem.Kind.CALL, dir, c.getLong(1), c.getString(0), c.getString(4), c.getLong(2))
                }
            }
        } catch (e: Exception) { EventLog.error(ctx, "Kunne ikke lese samtaleloggen", e) }
        return out
    }

    fun sms(ctx: Context): List<CommItem> {
        if (!canReadSms(ctx)) return emptyList()
        val out = ArrayList<CommItem>()
        try {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.BODY, Telephony.Sms.TYPE),
                null, null, "${Telephony.Sms.DATE} DESC")?.use { c ->
                while (c.moveToNext() && out.size < MAX_SMS) {
                    val dir = when (c.getInt(3)) {
                        Telephony.Sms.MESSAGE_TYPE_INBOX -> CommItem.Dir.IN
                        Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.MESSAGE_TYPE_OUTBOX -> CommItem.Dir.OUT
                        else -> CommItem.Dir.OTHER
                    }
                    out += CommItem(CommItem.Kind.SMS, dir, c.getLong(1), c.getString(0), null, text = c.getString(2))
                }
            }
        } catch (e: Exception) { EventLog.error(ctx, "Kunne ikke lese SMS", e) }
        return out
    }

    fun mms(ctx: Context): List<CommItem> {
        if (!canReadSms(ctx)) return emptyList()
        val out = ArrayList<CommItem>()
        try {
            ctx.contentResolver.query(Telephony.Mms.CONTENT_URI, null, null, null, "${Telephony.Mms.DATE} DESC")?.use { c ->
                val iId = c.getColumnIndex(Telephony.Mms._ID)
                val iDate = c.getColumnIndex(Telephony.Mms.DATE)
                val iBox = c.getColumnIndex(Telephony.Mms.MESSAGE_BOX)
                val iType = c.getColumnIndex(Telephony.Mms.MESSAGE_TYPE)
                val iSub = c.getColumnIndex(Telephony.Mms.SUBJECT)
                val iSubCs = c.getColumnIndex(Telephony.Mms.SUBJECT_CHARSET)
                while (c.moveToNext() && out.size < MAX_MMS) {
                    val mType = if (iType >= 0 && !c.isNull(iType)) c.getInt(iType) else 0
                    // Delivery / read reports are not messages
                    if (mType == MMS_DELIVERY_IND || mType == MMS_READ_ORIG) continue
                    val id = c.getLong(iId)
                    val sub = if (iSub >= 0) decodeSubject(c.getString(iSub), if (iSubCs >= 0 && !c.isNull(iSubCs)) c.getInt(iSubCs) else 0) else null
                    // MMS dates are in seconds
                    out += mmsItem(ctx, id, c.getLong(iDate) * 1000L, c.getInt(iBox), mType, sub)
                }
            }
        } catch (e: Exception) { EventLog.error(ctx, "Kunne ikke lese MMS", e) }
        return out
    }

    /** Builds one MMS item (also used by [MmsWatch] for a newly received one). */
    fun mmsItem(ctx: Context, id: Long, atMs: Long, box: Int, mType: Int, subject: String?): CommItem {
        val dir = when (box) {
            Telephony.Mms.MESSAGE_BOX_INBOX -> CommItem.Dir.IN
            Telephony.Mms.MESSAGE_BOX_SENT, Telephony.Mms.MESSAGE_BOX_OUTBOX -> CommItem.Dir.OUT
            else -> CommItem.Dir.OTHER
        }
        val (main, others) = mmsAddresses(ctx, id, dir != CommItem.Dir.OUT)
        val parts = mmsParts(ctx, id)
        val body = parts.filter { it.isText }.mapNotNull { it.text?.trim()?.ifEmpty { null } }.joinToString("\n").ifEmpty { null }
        val text = body ?: when {
            mType == MMS_NOTIFICATION -> "(MMS ikke lastet ned ennå)"
            parts.isEmpty() -> null
            else -> null
        }
        return CommItem(CommItem.Kind.MMS, dir, atMs, main, null, text = text, mmsId = id, subject = subject,
            parts = parts.filter { !it.isText }, others = others)
    }

    /**
     * Sender (137) for received MMS, first recipient (151) for sent ones; [others] = every other participant
     * (To / Cc / Bcc, plus the sender for a sent one), deduplicated by number. The own number is not known to
     * the app, so for a received group MMS it may appear among the recipients; it is dropped when it equals
     * the line number Android reports.
     */
    fun mmsAddresses(ctx: Context, id: Long, incoming: Boolean): Pair<String?, List<String>> = try {
        val from = ArrayList<String>(); val to = ArrayList<String>()
        ctx.contentResolver.query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val a = c.getString(0)?.trim() ?: continue
                if (a.isEmpty() || a == "insert-address-token") continue
                when (c.getInt(1)) {
                    ADDR_FROM -> from += a
                    ADDR_TO, ADDR_CC, ADDR_BCC -> to += a
                }
            }
        }
        val main = if (incoming) from.firstOrNull() ?: to.firstOrNull() else to.firstOrNull() ?: from.firstOrNull()
        val own = ownKeys(ctx)
        val seen = HashSet<String>().apply { add(Phone.key(main)) }
        val others = (to + if (incoming) emptyList() else from).filter { a ->
            val k = Phone.key(a); k.isNotEmpty() && k !in own && seen.add(k)
        }
        main to others
    } catch (_: Exception) { null to emptyList() }

    @Volatile private var ownCache: Set<String>? = null

    /** The phone's own number(s) when Android knows them (often it does not); only used to tidy group lists. */
    @Suppress("DEPRECATION", "MissingPermission")
    private fun ownKeys(ctx: Context): Set<String> = ownCache ?: try {
        val tm = ctx.getSystemService(android.telephony.TelephonyManager::class.java)
        setOfNotNull(tm?.line1Number?.let { Phone.key(it) }?.takeIf { it.isNotEmpty() })
    } catch (_: Exception) { emptySet() }.also { ownCache = it }

    /** All parts except SMIL; text from the text column, or from the part stream when it is stored as a file. */
    fun mmsParts(ctx: Context, id: Long): List<MmsPart> = try {
        val out = ArrayList<MmsPart>()
        ctx.contentResolver.query(Uri.parse("content://mms/part"), null, "mid=?", arrayOf(id.toString()), "_id")?.use { c ->
            val iId = c.getColumnIndex("_id"); val iCt = c.getColumnIndex("ct"); val iText = c.getColumnIndex("text")
            val iData = c.getColumnIndex("_data"); val iName = c.getColumnIndex("name"); val iCl = c.getColumnIndex("cl")
            val iFn = c.getColumnIndex("fn")
            while (c.moveToNext()) {
                val pid = c.getLong(iId)
                val ct = (if (iCt >= 0) c.getString(iCt) else null)?.lowercase()?.trim()
                val name = listOf(iName, iFn, iCl).firstNotNullOfOrNull { i -> if (i >= 0) c.getString(i)?.takeIf { it.isNotBlank() } else null }
                if (ct == "application/smil") continue
                val type = ct ?: guessType(name) ?: "application/octet-stream"
                if (type == "text/plain") {
                    var t = if (iText >= 0) c.getString(iText) else null
                    if (t == null && iData >= 0 && c.getString(iData) != null) t = readText(ctx, pid)
                    out += MmsPart(pid, type, name, t)
                } else out += MmsPart(pid, type, name)
            }
        }
        out
    } catch (_: Exception) { emptyList() }

    private fun readText(ctx: Context, partId: Long): String? = try {
        ctx.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))?.use {
            String(it.readBytes().let { b -> if (b.size > 20_000) b.copyOf(20_000) else b }, Charsets.UTF_8)
        }
    } catch (_: Exception) { null }

    fun guessType(name: String?): String? {
        val ext = name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "heic" -> "image/heic"
            "mp4" -> "video/mp4"; "3gp" -> "video/3gpp"; "amr" -> "audio/amr"; "m4a" -> "audio/mp4"; "mp3" -> "audio/mpeg"
            "vcf" -> "text/x-vcard"; "txt" -> "text/plain"; "ics", "vcs" -> "text/calendar"; "pdf" -> "application/pdf"
            else -> null
        }
    }

    /**
     * The provider hands the subject out as a Latin-1 string of the raw PDU bytes on some phones; re-decode
     * it as UTF-8 when the charset says UTF-8 (106) and the result is clean.
     */
    fun decodeSubject(raw: String?, charset: Int): String? {
        val s = raw?.trim()?.ifEmpty { null } ?: return null
        if (charset == 106 && s.all { it.code < 256 } && s.any { it.code >= 0x80 }) {
            val re = String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            if (!re.contains('\uFFFD')) return re
        }
        return s
    }

    /** Calls + SMS + MMS, newest first. Do not call on the main thread. */
    fun all(ctx: Context): List<CommItem> = (calls(ctx) + sms(ctx) + mms(ctx)).sortedByDescending { it.at }
}
