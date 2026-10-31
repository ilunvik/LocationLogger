package no.grokbot.stedslogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import java.io.File

/**
 * EXPERIMENTAL call recorder (1.32, off by default). Android does not let ordinary apps record call audio
 * (the VOICE_CALL source is reserved for system apps), so this records the microphone during a call
 * (VOICE_COMMUNICATION, falling back to MIC) – it most likely only captures your own voice, and Android 14+
 * may give a background-started recorder silence. Files go to the app-specific folder
 * Android/data/<package>/files/Samtaleopptak (removed on uninstall), named <number>-<dir>-<epoch ms>.m4a.
 */
class CallRecorderService : Service() {
    companion object {
        const val KEY_ENABLED = "comm_rec_experimental"
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val CHANNEL = "comm_recorder"
        private const val NOTIF_ID = 3202

        fun enabled(ctx: Context) = LogStore.prefs(ctx).getBoolean(KEY_ENABLED, false)

        fun start(ctx: Context, number: String?, incoming: Boolean) {
            if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                EventLog.add(ctx, EventLog.RECORDING, "Opptak ikke startet: mangler mikrofontilgang")
                return
            }
            try {
                ctx.startForegroundService(Intent(ctx, CallRecorderService::class.java).setAction(ACTION_START)
                    .putExtra("number", number).putExtra("incoming", incoming))
            } catch (e: Exception) { EventLog.add(ctx, EventLog.RECORDING, "Opptak kunne ikke starte: ${e.javaClass.simpleName} ${e.message ?: ""}") }
        }

        fun stop(ctx: Context) {
            if (!running) return
            try { ctx.startService(Intent(ctx, CallRecorderService::class.java).setAction(ACTION_STOP)) } catch (_: Exception) {}
        }

        @Volatile private var running = false
    }

    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent.getStringExtra("number"), intent.getBooleanExtra("incoming", false))
            else -> { finish(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun begin(number: String?, incoming: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Samtaleopptak (eksperimentell)", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Tar opp samtalen (eksperimentell)")
            .setContentText("Tar trolig bare opp din egen stemme")
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            EventLog.add(this, EventLog.RECORDING, "Opptak: Android tillot ikke mikrofontjeneste fra bakgrunnen (${e.javaClass.simpleName})")
            stopSelf(); return
        }
        if (rec != null) return
        val dir = Recordings.ownDir(this) ?: run { stopSelf(); return }
        dir.mkdirs()
        val k = Phone.key(number).ifEmpty { "000" }
        startedAt = System.currentTimeMillis()
        val f = File(dir, "$k-${if (incoming) 1 else 2}-$startedAt.m4a")
        for (src in listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)) {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            try {
                r.setAudioSource(src)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(64_000)
                r.setAudioSamplingRate(44_100)
                r.setOutputFile(f.absolutePath)
                r.prepare()
                r.start()
                rec = r; file = f; running = true
                EventLog.add(this, EventLog.RECORDING, "Eksperimentelt opptak startet (kilde ${if (src == MediaRecorder.AudioSource.MIC) "MIC" else "VOICE_COMMUNICATION"})")
                return
            } catch (e: Exception) {
                try { r.release() } catch (_: Exception) {}
                EventLog.add(this, EventLog.RECORDING, "Opptakskilde $src feilet: ${e.javaClass.simpleName}")
            }
        }
        f.delete()
        stopSelf()
    }

    private fun finish() {
        val r = rec ?: return
        rec = null; running = false
        try { r.stop() } catch (_: Exception) { file?.delete() }
        try { r.release() } catch (_: Exception) {}
        val f = file
        if (f != null && f.exists()) EventLog.add(this, EventLog.RECORDING,
            "Eksperimentelt opptak lagret (${(System.currentTimeMillis() - startedAt) / 1000} s, ${f.length() / 1024} kB)")
        file = null
    }

    override fun onDestroy() { finish(); super.onDestroy() }
}
