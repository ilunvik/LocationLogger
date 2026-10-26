package no.grokbot.stedslogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!LogStore.prefs(context).getBoolean(LogStore.KEY_RUNNING, false)) return
        try {
            LocationService.start(context)
        } catch (e: Exception) {
            Log.w("LocationLogger", "Could not restart after boot", e)
        }
    }
}
