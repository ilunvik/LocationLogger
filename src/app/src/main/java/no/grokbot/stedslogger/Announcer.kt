package no.grokbot.stedslogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Popup (heads-up notification) and read-aloud (text-to-speech, nb-NO) for incoming calls and SMS (1.32).
 * Both are off by default and set in Settings › Samtaler og SMS. Nothing leaves the phone.
 */
object Announcer {
    const val KEY_POPUP = "comm_popup"
    const val KEY_TTS = "comm_tts"
    const val KEY_TTS_SMS_TEXT = "comm_tts_sms_text"
    private const val CHANNEL_POPUP = "comm_popup"
    private const val CHANNEL_INFO = "comm_info"
    const val ID_CALL = 3201
    private const val ID_SMS_BASE = 3300

    fun popupOn(ctx: Context) = LogStore.prefs(ctx).getBoolean(KEY_POPUP, false)
    fun ttsOn(ctx: Context) = LogStore.prefs(ctx).getBoolean(KEY_TTS, false)
    fun ttsSmsText(ctx: Context) = LogStore.prefs(ctx).getBoolean(KEY_TTS_SMS_TEXT, false)

    /** Best name: contact → looked-up name (with source) → null. */
    fun label(ctx: Context, raw: String?): Pair<String?, String?> {
        Phone.contactName(ctx, raw)?.let { return it to null }
        NumberLookup.cached(ctx, raw)?.name?.let { n -> return n to NumberLookup.cached(ctx, raw)?.source }
        return null to null
    }

    private fun channels(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_POPUP) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_POPUP, "Innringer / SMS (popup)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Viser hvem som ringer eller sender SMS, med navn fra kontakter eller nummeroppslag"
                setSound(null, null)
                enableVibration(false)
            })
        }
        if (nm.getNotificationChannel(CHANNEL_INFO) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_INFO, "Tapte anrop (oppslag)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
            })
        }
        return nm
    }

    private fun openApp(ctx: Context, req: Int): PendingIntent =
        PendingIntent.getActivity(ctx, req, Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_COMMS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun title(ctx: Context, raw: String?): Pair<String, String?> {
        val (name, src) = label(ctx, raw)
        val num = Phone.pretty(raw)
        return if (name != null) name to (if (src != null) "$num · oppslag via $src" else num) else num to null
    }

    fun popupCall(ctx: Context, raw: String?, lookingUp: Boolean) {
        if (!popupOn(ctx)) return
        try {
            val (t, sub) = title(ctx, raw)
            val text = listOfNotNull(sub, if (lookingUp) "Slår opp nummeret …" else null).joinToString(" · ").ifEmpty { "Innkommende anrop" }
            val n = Notification.Builder(ctx, CHANNEL_POPUP)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle("📞 $t")
                .setContentText(text)
                .setCategory(Notification.CATEGORY_CALL)
                .setContentIntent(openApp(ctx, 1))
                .setOnlyAlertOnce(false)
                .setTimeoutAfter(90_000L)
                .setAutoCancel(true)
                .build()
            channels(ctx).notify(ID_CALL, n)
        } catch (e: Exception) { EventLog.error(ctx, "Popup feilet", e) }
    }

    fun cancelCall(ctx: Context) = try { channels(ctx).cancel(ID_CALL) } catch (_: Exception) {}

    fun missed(ctx: Context, raw: String?) {
        if (!popupOn(ctx)) return
        val (name, src) = label(ctx, raw)
        if (name == null || src == null) return // contacts: the phone's own missed-call notice is enough
        try {
            channels(ctx).notify(ID_CALL, Notification.Builder(ctx, CHANNEL_INFO)
                .setSmallIcon(android.R.drawable.sym_call_missed)
                .setContentTitle("Tapt anrop: $name")
                .setContentText("${Phone.pretty(raw)} · oppslag via $src")
                .setContentIntent(openApp(ctx, 2))
                .setAutoCancel(true)
                .build())
        } catch (_: Exception) {}
    }

    fun popupSms(ctx: Context, raw: String?, body: String?) {
        if (!popupOn(ctx)) return
        try {
            val (t, sub) = title(ctx, raw)
            val n = Notification.Builder(ctx, CHANNEL_POPUP)
                .setSmallIcon(android.R.drawable.sym_action_email)
                .setContentTitle("💬 $t")
                .setContentText(body ?: sub ?: "SMS")
                .setStyle(Notification.BigTextStyle().bigText(listOfNotNull(sub, body).joinToString("\n")))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(openApp(ctx, 3))
                .setTimeoutAfter(10 * 60_000L)
                .setAutoCancel(true)
                .build()
            channels(ctx).notify(ID_SMS_BASE + (Phone.key(raw).hashCode() and 0xff), n)
        } catch (e: Exception) { EventLog.error(ctx, "Popup feilet", e) }
    }

    // ---------- text-to-speech ----------

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayList<String>()

    /** "Anrop fra X" / "SMS fra X: …" in Norwegian. */
    fun spokenName(ctx: Context, raw: String?): String = label(ctx, raw).first ?: Phone.spoken(raw)

    @Synchronized
    fun speak(ctx: Context, text: String, what: String = "melding") {
        val app = ctx.applicationContext
        EventLog.add(app, EventLog.TTS, "Leser opp $what")
        if (tts != null && ready) { say(app, text); return }
        pending += text
        if (tts != null) return
        tts = TextToSpeech(app) { status ->
            synchronized(this) {
                if (status != TextToSpeech.SUCCESS) {
                    EventLog.add(app, EventLog.TTS, "Tekst-til-tale kunne ikke starte (status $status)")
                    tts = null; pending.clear(); return@TextToSpeech
                }
                val t = tts ?: return@TextToSpeech
                val r = t.setLanguage(Locale("nb", "NO")).let { if (it < 0) t.setLanguage(Locale("no", "NO")) else it }
                if (r < 0) EventLog.add(app, EventLog.TTS, "Norsk stemme mangler (kode $r) – installer norsk i Tekst-til-tale-innstillingene")
                t.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {}
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) {
                        EventLog.add(app, EventLog.TTS, "Opplesing feilet")
                    }
                })
                ready = true
                for (p in pending) say(app, p)
                pending.clear()
            }
        }
    }

    private fun say(ctx: Context, text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "ll-${System.nanoTime()}")
    }

    fun announceCall(ctx: Context, raw: String?) {
        if (!ttsOn(ctx)) return
        speak(ctx, "Anrop fra ${spokenName(ctx, raw)}", "innringer")
    }

    fun announceSms(ctx: Context, raw: String?, body: String?) {
        if (!ttsOn(ctx)) return
        val who = spokenName(ctx, raw)
        speak(ctx, if (ttsSmsText(ctx) && !body.isNullOrBlank()) "SMS fra $who: ${body.take(400)}" else "SMS fra $who",
            if (ttsSmsText(ctx)) "SMS-avsender og tekst" else "SMS-avsender")
    }
}
