package no.grokbot.stedslogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.TelephonyManager

/**
 * Incoming / outgoing / missed calls (PHONE_STATE) and received SMS / MMS (1.32): writes SAMTALE / SMS
 * entries to the Log tab, looks unknown numbers up, shows the popup and reads the caller aloud when those
 * settings are on, and starts / stops the experimental recorder. Nothing is sent anywhere except the
 * unknown number to the directory lookup.
 */
open class CommReceiver : BroadcastReceiver() {
    companion object {
        private const val KEY_STATE = "call_state"
        private const val KEY_NUMBER = "call_number"
        private const val KEY_SINCE = "call_since"
        private const val KEY_ANNOUNCED = "call_announced_at"
        private const val ST_IDLE = "IDLE"
        private const val ST_RINGING = "RINGING"
        private const val ST_OFFHOOK = "OFFHOOK"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> onPhoneState(ctx, intent)
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> onSms(ctx, intent)
            Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION -> {
                EventLog.add(ctx, EventLog.SMS, "MMS mottatt")
                if (Announcer.ttsOn(ctx)) Announcer.speak(ctx, "Ny MMS")
            }
        }
    }

    private fun who(ctx: Context, raw: String?): String {
        val (name, src) = Announcer.label(ctx, raw)
        return when {
            name != null && src != null -> "$name (oppslag, ${Phone.masked(raw)})"
            name != null -> "$name (kontakt)"
            else -> Phone.masked(raw)
        }
    }

    @Suppress("DEPRECATION")
    private fun onPhoneState(ctx: Context, intent: Intent) {
        val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> ST_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> ST_OFFHOOK
            TelephonyManager.EXTRA_STATE_IDLE -> ST_IDLE
            else -> return
        }
        val hasNumberExtra = intent.hasExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.takeIf { it.isNotBlank() }
        val canNumber = ctx.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        // With READ_CALL_LOG Android sends each change twice (with and without the number): for ringing, wait for the one with it
        if (canNumber && !hasNumberExtra && state == ST_RINGING) return
        val p = LogStore.prefs(ctx)
        val prev = p.getString(KEY_STATE, ST_IDLE) ?: ST_IDLE
        val now = System.currentTimeMillis()
        if (state == prev) {
            // The second copy may carry the number the first one lacked
            if (!number.isNullOrEmpty() && p.getString(KEY_NUMBER, null).isNullOrEmpty() && state != ST_IDLE)
                p.edit().putString(KEY_NUMBER, number).apply()
            return
        }
        val prevNumber = p.getString(KEY_NUMBER, null)
        val since = p.getLong(KEY_SINCE, now)
        p.edit().putString(KEY_STATE, state).putLong(KEY_SINCE, now)
            .putString(KEY_NUMBER, number ?: if (state == ST_IDLE) null else prevNumber).apply()
        when (state) {
            ST_RINGING -> {
                EventLog.add(ctx, EventLog.CALL, "Innkommende anrop fra ${who(ctx, number)}")
                if (now - p.getLong(KEY_ANNOUNCED, 0L) < 5_000L) return
                p.edit().putLong(KEY_ANNOUNCED, now).apply()
                val needs = NumberLookup.enabled(ctx) && NumberLookup.needsLookup(ctx, number)
                Announcer.popupCall(ctx, number, lookingUp = needs)
                if (!needs) { Announcer.announceCall(ctx, number); return }
                val pending = goAsync()
                NumberLookup.lookupAsync(ctx, number) { r ->
                    try {
                        if (LogStore.prefs(ctx).getString(KEY_STATE, null) == ST_RINGING) {
                            Announcer.popupCall(ctx, number, lookingUp = false)
                            Announcer.announceCall(ctx, number)
                        }
                        Contacts.maybeAutoAdd(ctx, number, r)
                    } finally { pending.finish() }
                }
            }
            ST_OFFHOOK -> {
                Announcer.cancelCall(ctx)
                val incoming = prev == ST_RINGING
                val num = number ?: prevNumber
                EventLog.add(ctx, EventLog.CALL, if (incoming) "Besvart: ${who(ctx, num)}" else "Utgående anrop" + (num?.let { " til ${who(ctx, it)}" } ?: ""))
                if (CallRecorderService.enabled(ctx)) CallRecorderService.start(ctx, num, incoming)
            }
            ST_IDLE -> {
                if (prev == ST_RINGING) {
                    EventLog.add(ctx, EventLog.CALL, "Tapt/avvist anrop fra ${who(ctx, prevNumber)}")
                    Announcer.missed(ctx, prevNumber)
                } else {
                    Announcer.cancelCall(ctx)
                    EventLog.add(ctx, EventLog.CALL, "Samtale avsluttet etter ${(now - since) / 1000} s")
                }
                CallRecorderService.stop(ctx)
            }
        }
    }

    private fun onSms(ctx: Context, intent: Intent) {
        val msgs = try { Telephony.Sms.Intents.getMessagesFromIntent(intent) } catch (_: Exception) { null } ?: return
        // A long SMS arrives in parts: join them per sender
        val bySender = LinkedHashMap<String?, StringBuilder>()
        for (m in msgs) bySender.getOrPut(m?.displayOriginatingAddress) { StringBuilder() }.append(m?.displayMessageBody ?: "")
        val items = bySender.map { it.key to it.value.toString() }
        for ((from, body) in items) EventLog.add(ctx, EventLog.SMS, "SMS fra ${who(ctx, from)} (${body.length} tegn)")
        fun show() {
            for ((from, body) in items) {
                Announcer.popupSms(ctx, from, body)
                Announcer.announceSms(ctx, from, body)
            }
        }
        val toLookUp = items.map { it.first }.filter { NumberLookup.enabled(ctx) && NumberLookup.needsLookup(ctx, it) }
        if (toLookUp.isEmpty()) { show(); return }
        val pending = goAsync()
        Thread {
            try {
                for (n in toLookUp) {
                    val r = try { NumberLookup.lookup(ctx, n) } catch (_: Exception) { null }
                    Contacts.maybeAutoAdd(ctx, n, r)
                }
                show()
            } finally { pending.finish() }
        }.start()
    }
}

/** SMS_RECEIVED (guarded by BROADCAST_SMS, so only the system can send it). */
class SmsReceiver : CommReceiver()

/** WAP_PUSH_RECEIVED for MMS (guarded by BROADCAST_WAP_PUSH). */
class MmsReceiver : CommReceiver()
