package no.grokbot.stedslogger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        EventLog.add(context, EventLog.SERVICE,
            if (action == Intent.ACTION_BOOT_COMPLETED) "Telefonen startet (boot)" else "Appen ble oppdatert")
        // Report the adb address (incl. tcp 5555) right after boot, if a dashboard URL is set
        if (AdbReporter.url(context).isNotEmpty()) {
            val pr = goAsync()
            try { AdbReporter.maybeReport(context, force = true) } catch (e: Exception) { EventLog.error(context, "adb-rapport feilet", e) }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ pr.finish() }, 9000)
        }
        if (!LogStore.prefs(context).getBoolean(LogStore.KEY_RUNNING, false)) return
        try {
            LocationService.start(context)
        } catch (e: Exception) {
            EventLog.error(context, "Kunne ikke starte tjenesten etter boot/oppdatering", e)
        }
    }
}
