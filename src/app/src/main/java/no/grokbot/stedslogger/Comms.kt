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
) {
    enum class Kind { CALL, SMS, MMS }
    enum class Dir { IN, OUT, MISSED, REJECTED, BLOCKED, VOICEMAIL, OTHER }
}

object Comms {
    const val MAX_CALLS = 400
    const val MAX_SMS = 400
    const val MAX_MMS = 100

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
            ctx.contentResolver.query(Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.SUBJECT),
                null, null, "${Telephony.Mms.DATE} DESC")?.use { c ->
                while (c.moveToNext() && out.size < MAX_MMS) {
                    val id = c.getLong(0)
                    val box = c.getInt(2)
                    val dir = when (box) {
                        Telephony.Mms.MESSAGE_BOX_INBOX -> CommItem.Dir.IN
                        Telephony.Mms.MESSAGE_BOX_SENT, Telephony.Mms.MESSAGE_BOX_OUTBOX -> CommItem.Dir.OUT
                        else -> CommItem.Dir.OTHER
                    }
                    val addr = mmsAddress(ctx, id, dir == CommItem.Dir.IN)
                    val body = mmsText(ctx, id) ?: c.getString(3)
                    // MMS dates are in seconds
                    out += CommItem(CommItem.Kind.MMS, dir, c.getLong(1) * 1000L, addr, null, text = body ?: "(bilde/vedlegg)")
                }
            }
        } catch (e: Exception) { EventLog.error(ctx, "Kunne ikke lese MMS", e) }
        return out
    }

    /** From (137) for received MMS, first To (151) for sent ones. */
    private fun mmsAddress(ctx: Context, id: Long, incoming: Boolean): String? = try {
        ctx.contentResolver.query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), null, null, null)?.use { c ->
            var found: String? = null
            while (c.moveToNext()) {
                val a = c.getString(0) ?: continue
                if (a == "insert-address-token") continue
                val t = c.getInt(1)
                if ((incoming && t == 137) || (!incoming && t == 151)) { found = a; break }
            }
            found
        }
    } catch (_: Exception) { null }

    private fun mmsText(ctx: Context, id: Long): String? = try {
        ctx.contentResolver.query(Uri.parse("content://mms/part"), arrayOf("ct", "text"), "mid=?", arrayOf(id.toString()), null)?.use { c ->
            val sb = StringBuilder()
            while (c.moveToNext()) if (c.getString(0) == "text/plain") c.getString(1)?.let { sb.append(it).append(' ') }
            sb.toString().trim().ifEmpty { null }
        }
    } catch (_: Exception) { null }

    /** Calls + SMS + MMS, newest first. Do not call on the main thread. */
    fun all(ctx: Context): List<CommItem> = (calls(ctx) + sms(ctx) + mms(ctx)).sortedByDescending { it.at }
}
