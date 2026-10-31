package no.grokbot.stedslogger

import android.app.Activity
import android.app.AlertDialog
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** Simple in-app player for a call recording (1.32): play / pause, seek bar, position. */
object Player {
    private fun fmt(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    fun show(act: Activity, rec: Recordings.Rec, title: String) {
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(act, rec.uri)
            mp.prepare()
        } catch (e: Exception) {
            mp.release()
            EventLog.add(act, EventLog.RECORDING, "Kunne ikke spille av opptak (${rec.source}): ${e.javaClass.simpleName}")
            Toast.makeText(act, "Kunne ikke spille av: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            return
        }
        EventLog.add(act, EventLog.RECORDING, "Spiller av opptak (${rec.source}, ${fmt(mp.duration)})")
        val d = act.resources.displayMetrics.density
        val pos = TextView(act).apply { textSize = 14f }
        val seek = SeekBar(act).apply { max = mp.duration.coerceAtLeast(1) }
        val btn = Button(act).apply { text = "⏸ Pause" }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (12 * d).toInt(), (20 * d).toInt(), 0)
            addView(TextView(act).apply { text = "Kilde: ${rec.source}"; textSize = 12f; setTextColor(0xFF757575.toInt()) })
            addView(seek); addView(pos); addView(btn)
        }
        val h = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                try {
                    seek.progress = mp.currentPosition
                    pos.text = "${fmt(mp.currentPosition)} / ${fmt(mp.duration)}"
                } catch (_: Exception) { return }
                h.postDelayed(this, 300)
            }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) try { mp.seekTo(p) } catch (_: Exception) {} }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        btn.setOnClickListener {
            if (mp.isPlaying) { mp.pause(); btn.text = "▶ Spill av" } else { mp.start(); btn.text = "⏸ Pause" }
        }
        mp.setOnCompletionListener { btn.text = "▶ Spill av" }
        val dlg = AlertDialog.Builder(act).setTitle(title).setView(box).setNegativeButton("Lukk", null).create()
        dlg.setOnDismissListener { h.removeCallbacks(tick); try { mp.stop() } catch (_: Exception) {}; mp.release() }
        dlg.show()
        mp.start()
        h.post(tick)
    }
}
