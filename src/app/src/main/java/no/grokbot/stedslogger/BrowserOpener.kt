package no.grokbot.stedslogger

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Opens a stop's Street View in the Google Maps app (since 1.31), defrosting Maps with Ice Box first
 * when it is frozen (pm disable-user or pm hide). Falls back to a browser (the 1.30 path, which also
 * defrosts a frozen browser) when Maps is missing or Ice Box is unavailable or not allowed.
 *
 * Ice Box is driven only through its official SDK (github.com/heruoxin/IceBox-SDK): a content-provider
 * call to `content://com.catchingnow.icebox.SDK` ("set_enable"), which needs the runtime permission
 * `com.catchingnow.icebox.SDK` that the user grants once in the normal Android permission dialog.
 * The app never freezes anything; Ice Box's own rules decide when an app is frozen again.
 */
object BrowserOpener {
    const val ICEBOX = "com.catchingnow.icebox"
    const val ICEBOX_PERMISSION = "com.catchingnow.icebox.SDK"
    private val SDK_URI: Uri = Uri.parse("content://com.catchingnow.icebox.SDK")
    private val STATE_URI: Uri = Uri.parse("content://com.catchingnow.icebox.STATE")

    /** Browser chosen in Settings ("" / missing = automatic: the last browser that opened a link). */
    private const val KEY_BROWSER = "open_browser_pkg"
    private const val KEY_LAST_BROWSER = "last_browser_pkg"

    /** How long to wait for the defrosted browser to show up as enabled before trying anyway. */
    private const val DEFROST_WAIT_MS = 6_000L

    const val FROZEN_DISABLED = 1
    const val FROZEN_HIDDEN = 2

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    class Browser(val pkg: String, val label: String, val frozen: Int)

    private const val PM_FLAGS = PackageManager.MATCH_DISABLED_COMPONENTS or
        PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_ALL

    /** Every installed web browser, frozen or not (apps that handle any https link). */
    fun browsers(ctx: Context): List<Browser> {
        val pm = ctx.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val pkgs = try { pm.queryIntentActivities(probe, PM_FLAGS) } catch (e: Exception) { emptyList() }
            .map { it.activityInfo.packageName }
            .filter { it != ctx.packageName && it != "android" }
            .distinct()
        return pkgs.mapNotNull { p ->
            val state = frozenState(ctx, p) ?: return@mapNotNull null
            val label = try {
                pm.getApplicationInfo(p, PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS)
                    .loadLabel(pm).toString()
            } catch (e: Exception) { p }
            Browser(p, label, state)
        }.sortedBy { it.label.lowercase() }
    }

    /**
     * Freeze state like Ice Box's SDK reports it: 0 normal, [FROZEN_DISABLED] (pm disable-user),
     * [FROZEN_HIDDEN] (pm hide) or both; null if the app is not installed.
     */
    fun frozenState(ctx: Context, pkg: String): Int? {
        val pm = ctx.packageManager
        val ai = try {
            pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS)
        } catch (e: PackageManager.NameNotFoundException) { return null }
        // Only on another user / profile (hidden apps keep FLAG_INSTALLED)
        if (ai.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED == 0) return null
        var s = 0
        if (!ai.enabled) s = s or FROZEN_DISABLED
        // A hidden app is only found with MATCH_UNINSTALLED_PACKAGES
        if (!isVisibleWithoutUninstalled(pm, pkg)) s = s or FROZEN_HIDDEN
        return s
    }

    private fun isVisibleWithoutUninstalled(pm: PackageManager, pkg: String) = try {
        pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS); true
    } catch (e: PackageManager.NameNotFoundException) { false }

    fun label(ctx: Context, pkg: String): String = try {
        ctx.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS)
            .loadLabel(ctx.packageManager).toString()
    } catch (e: Exception) { pkg }

    fun chosen(ctx: Context): String? =
        LogStore.prefs(ctx).getString(KEY_BROWSER, null)?.takeIf { it.isNotEmpty() }

    fun lastBrowser(ctx: Context): String? =
        LogStore.prefs(ctx).getString(KEY_LAST_BROWSER, null)?.takeIf { it.isNotEmpty() }

    /** null = automatic. */
    fun setChosen(ctx: Context, pkg: String?) {
        LogStore.prefs(ctx).edit().putString(KEY_BROWSER, pkg ?: "").apply()
        if (pkg != null) LogStore.prefs(ctx).edit().putString(KEY_LAST_BROWSER, pkg).apply()
    }

    /** Browser to defrost: the one chosen in Settings, else the last one that opened a link (if still installed). */
    fun target(ctx: Context): String? =
        (chosen(ctx) ?: lastBrowser(ctx))?.takeIf { frozenState(ctx, it) != null }

    // ---------- Ice Box ----------

    fun iceBoxInstalled(ctx: Context) = frozenState(ctx, ICEBOX) == 0

    fun hasIceBoxPermission(ctx: Context) =
        ctx.checkSelfPermission(ICEBOX_PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun authorize(ctx: Context): PendingIntent = PendingIntent.getBroadcast(ctx, 0x333,
        Intent(ctx, IceBoxStateReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)

    /** "MODE_PM_DISABLE_USER", "MODE_PM_HIDE" or "MODE_NOT_AVAILABLE" (no permission needed). */
    fun iceBoxMode(ctx: Context): String {
        if (!iceBoxInstalled(ctx)) return "MODE_NOT_AVAILABLE"
        return try {
            val extra = Bundle().apply { putParcelable("authorize", authorize(ctx)) }
            ctx.contentResolver.call(STATE_URI, "query_mode", null, extra)
                ?.getString("work_mode", "MODE_NOT_AVAILABLE") ?: "MODE_NOT_AVAILABLE"
        } catch (e: Exception) { "MODE_NOT_AVAILABLE" }
    }

    /** Asks Ice Box to defrost [pkg] (blocking IPC: call off the main thread). */
    private fun defrost(ctx: Context, pkg: String) {
        val extra = Bundle().apply {
            putParcelable("authorize", authorize(ctx))
            putStringArray("package_names", arrayOf(pkg))
            putInt("user_handle", android.os.Process.myUserHandle().hashCode())
            putBoolean("enable", true)
        }
        ctx.contentResolver.call(SDK_URI, "set_enable", null, extra)
    }

    /** State for Settings: Google Maps, Ice Box, fallback browser. */
    fun statusLine(ctx: Context): String {
        val maps = "Google Maps: " + when (frozenState(ctx, MAPS)) {
            null -> "not installed (the browser is used)"
            0 -> "ready"
            else -> "frozen – Ice Box defrosts it on tap"
        }
        val ice = when {
            !iceBoxInstalled(ctx) -> "Ice Box: not installed"
            else -> "Ice Box: " + when (iceBoxMode(ctx)) {
                "MODE_PM_DISABLE_USER" -> "freezes by disabling"
                "MODE_PM_HIDE" -> "freezes by hiding"
                else -> "not active"
            } + if (hasIceBoxPermission(ctx)) ", may defrost ✓" else ", permission not granted yet (asked on first tap)"
        }
        val t = target(ctx)
        val b = "Fallback browser: " + (if (t == null) "automatic (asks if one is needed)"
            else label(ctx, t) + (if (chosen(ctx) == null) " (last used)" else "") +
                (if ((frozenState(ctx, t) ?: 0) != 0) " – frozen now" else ""))
        return "$maps\n$ice\n$b"
    }

    // ---------- opening ----------

    const val MAPS = "com.google.android.apps.maps"

    /** What to open: a package and the links to try in it, in order. */
    private class Target(val pkg: String, val uris: List<String>, val what: String, val fallbackUrl: String?)

    /** Link waiting for the Ice Box permission dialog. */
    private var pending: Target? = null

    /**
     * 1.31: opens Street View at [lat],[lon] in the Google Maps app (google.streetview: intent, then
     * the https pano link, both with setPackage Maps). If Maps is frozen by Ice Box it is defrosted
     * first. Without Maps, or when Ice Box is unavailable or not allowed, falls back to a browser
     * ([httpsUrl]).
     */
    fun openStreetView(act: Activity, lat: Double, lon: Double, heading: Int?, httpsUrl: String, reqIceBox: Int) {
        val sv = String.format(java.util.Locale.US, "google.streetview:cbll=%.6f,%.6f", lat, lon) +
            (heading?.let { "&cbp=0,$it,0,0,0" } ?: "")
        val t = Target(MAPS, listOf(sv, httpsUrl), "Street View", httpsUrl)
        when (val state = frozenState(act, MAPS)) {
            null -> fallback(act, t, "Google Maps is not installed", reqIceBox)
            0 -> start(act, t, null)
            else -> viaIceBox(act, t, state, reqIceBox)
        }
    }

    /** Browser fallback (the 1.30 path): any app that opens the link, else a (defrosted) browser. */
    private fun fallback(act: Activity, t: Target, why: String, reqIceBox: Int?) {
        val url = t.fallbackUrl ?: run { toastLog(act, why, "${t.what}: $why"); return }
        Toast.makeText(act, "$why – opening the browser", Toast.LENGTH_SHORT).show()
        EventLog.add(act, EventLog.BROWSER, "${t.what}: $why, falling back to the browser")
        open(act, url, t.what, reqIceBox)
    }

    /**
     * Opens [url] with any app that can (Maps or a browser); otherwise with the chosen / last-used
     * browser, defrosted by Ice Box if needed. [reqIceBox] null = do not ask for the Ice Box permission.
     */
    fun open(act: Activity, url: String, what: String, reqIceBox: Int?) {
        val intent = viewIntent(url)
        val ri = act.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val handler = ri?.activityInfo?.packageName
        if (ri != null && handler != null) {
            if (browsers(act).any { it.pkg == handler })
                LogStore.prefs(act).edit().putString(KEY_LAST_BROWSER, handler).apply()
            try { act.startActivity(intent); EventLog.add(act, EventLog.BROWSER, "$what opened ($handler)"); return }
            catch (e: Exception) { EventLog.add(act, EventLog.BROWSER, "$what: could not open ($handler)", e) }
        }
        // Nothing can open it: every browser is frozen or missing
        val go = { p: String ->
            val t = Target(p, listOf(url), what, null)
            when (val st = frozenState(act, p)) {
                null -> toastLog(act, "${label(act, p)} is not installed", "$what: $p is not installed")
                0 -> start(act, t, null)
                else -> viaIceBox(act, t, st, reqIceBox)
            }
        }
        val b = target(act)
        if (b == null) pick(act) { p -> go(p) } else go(b)
    }

    private fun viewIntent(url: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)

    /** [t] is frozen ([state]): defrost it with Ice Box (asking for the permission once), else fall back. */
    private fun viaIceBox(act: Activity, t: Target, state: Int, reqIceBox: Int?) {
        val name = label(act, t.pkg)
        if (!iceBoxInstalled(act)) {
            if (t.fallbackUrl != null) fallback(act, t, "$name is frozen and Ice Box is not available", null)
            else toastLog(act, "$name is frozen and Ice Box is not available", "${t.what}: ${t.pkg} frozen ($state), Ice Box not installed/enabled")
            return
        }
        if (!hasIceBoxPermission(act)) {
            if (reqIceBox == null) {
                toastLog(act, "$name is frozen (Ice Box not allowed)", "${t.what}: ${t.pkg} frozen ($state), no Ice Box permission")
                return
            }
            pending = t
            EventLog.add(act, EventLog.BROWSER, "${t.what}: ${t.pkg} frozen ($state), asking for the Ice Box permission")
            act.requestPermissions(arrayOf(ICEBOX_PERMISSION), reqIceBox)
            return
        }
        defrostAndStart(act, t, state)
    }

    /** Continue after the Ice Box permission dialog. */
    fun onPermissionResult(act: Activity) {
        val t = pending ?: return
        pending = null
        if (!hasIceBoxPermission(act)) {
            val why = "Ice Box permission denied – ${label(act, t.pkg)} stays frozen"
            if (t.fallbackUrl != null) fallback(act, t, why, null)
            else toastLog(act, why, "${t.what}: Ice Box permission denied, ${t.pkg} not defrosted")
            return
        }
        EventLog.add(act, EventLog.BROWSER, "Ice Box permission granted")
        val st = frozenState(act, t.pkg) ?: 0
        if (st == 0) start(act, t, null) else defrostAndStart(act, t, st)
    }

    private fun defrostAndStart(act: Activity, t: Target, state: Int) {
        val app = act.applicationContext
        val name = label(act, t.pkg)
        Toast.makeText(act, "Defrosting $name with Ice Box…", Toast.LENGTH_SHORT).show()
        val started = System.currentTimeMillis()
        io.execute {
            var err: Exception? = null
            try { defrost(app, t.pkg) } catch (e: Exception) { err = e }
            var now = frozenState(app, t.pkg)
            while (err == null && now != 0 && System.currentTimeMillis() - started < DEFROST_WAIT_MS) {
                Thread.sleep(200)
                now = frozenState(app, t.pkg)
            }
            val ms = System.currentTimeMillis() - started
            main.post {
                when {
                    err != null -> {
                        EventLog.add(app, EventLog.BROWSER, "${t.what}: Ice Box defrost of ${t.pkg} failed", err)
                        if (t.fallbackUrl != null) fallback(act, t, "Ice Box could not defrost $name", null)
                        else Toast.makeText(act, "Ice Box could not defrost $name", Toast.LENGTH_LONG).show()
                    }
                    now != 0 -> {
                        EventLog.add(app, EventLog.BROWSER,
                            "${t.what}: ${t.pkg} still frozen ($now) $ms ms after asking Ice Box (mode ${iceBoxMode(app)})")
                        if (t.fallbackUrl != null) fallback(act, t, "$name is still frozen", null)
                        else Toast.makeText(act, "$name is still frozen (Ice Box did not defrost it)", Toast.LENGTH_LONG).show()
                    }
                    else -> start(act, t, "defrosted by Ice Box (was $state) in $ms ms")
                }
            }
        }
    }

    private fun start(act: Activity, t: Target, note: String?) {
        var last: Exception? = null
        for (u in t.uris) {
            try {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(u)).setPackage(t.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                act.startActivity(i)
                if (t.pkg != MAPS) LogStore.prefs(act).edit().putString(KEY_LAST_BROWSER, t.pkg).apply()
                EventLog.add(act, EventLog.BROWSER, "${t.what} opened in ${t.pkg}" +
                    (if (u.startsWith("google.streetview")) " (Street View intent)" else "") + (note?.let { ", $it" } ?: ""))
                return
            } catch (e: Exception) { last = e }
        }
        EventLog.add(act, EventLog.BROWSER, "${t.what}: ${t.pkg} could not open the link", last)
        if (t.fallbackUrl != null) fallback(act, t, "${label(act, t.pkg)} could not open Street View", null)
        else Toast.makeText(act, "${label(act, t.pkg)} could not open the link", Toast.LENGTH_LONG).show()
    }

    private fun toastLog(ctx: Context, toast: String, log: String, e: Throwable? = null) {
        Toast.makeText(ctx, toast, Toast.LENGTH_LONG).show()
        EventLog.add(ctx, EventLog.BROWSER, log, e)
    }

    /**
     * Lets the user pick a browser (frozen ones marked); [auto] adds "Automatic" at the top (Settings).
     * The choice is saved.
     */
    fun pick(act: Activity, auto: Boolean = false, done: (String) -> Unit) {
        val list = browsers(act)
        if (list.isEmpty()) {
            toastLog(act, "No browser installed", "No browser found to open the link"); return
        }
        val names = (if (auto) listOf("Automatic (last browser used)") else emptyList()) +
            list.map { it.label + if (it.frozen != 0) "  (frozen)" else "" }
        AlertDialog.Builder(act)
            .setTitle("Fallback browser for Street View")
            .setItems(names.toTypedArray()) { _, which ->
                if (auto && which == 0) { setChosen(act, null); done(""); return@setItems }
                val b = list[which - if (auto) 1 else 0]
                setChosen(act, b.pkg)
                EventLog.add(act, EventLog.BROWSER, "Browser for links set to ${b.pkg}")
                done(b.pkg)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

/** Target of the PendingIntent Ice Box uses to identify this app (does nothing). */
class IceBoxStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}
}
