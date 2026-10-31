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
 * Opens links (the Street View view of a stop) in Google Maps or a browser, and when every app that
 * could open the link is frozen by Ice Box (pm disable-user or pm hide), asks Ice Box to defrost the
 * chosen browser first (since 1.30).
 *
 * Ice Box is driven only through its official SDK (github.com/heruoxin/IceBox-SDK): a content-provider
 * call to `content://com.catchingnow.icebox.SDK` ("set_enable"), which needs the runtime permission
 * `com.catchingnow.icebox.SDK` that the user grants once in the normal Android permission dialog.
 * The app never freezes anything; Ice Box's own rules decide when the browser is frozen again.
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

    /** One-line state for Settings. */
    fun statusLine(ctx: Context): String {
        val t = target(ctx)
        val b = when {
            t == null -> "Browser: automatic (asks the first time one is needed)"
            else -> "Browser: ${label(ctx, t)}" + (if (chosen(ctx) == null) " (last used)" else "") +
                (if ((frozenState(ctx, t) ?: 0) != 0) " – frozen now" else "")
        }
        val ice = when {
            !iceBoxInstalled(ctx) -> "Ice Box: not installed"
            else -> "Ice Box: " + when (iceBoxMode(ctx)) {
                "MODE_PM_DISABLE_USER" -> "freezes by disabling"
                "MODE_PM_HIDE" -> "freezes by hiding"
                else -> "not active"
            } + if (hasIceBoxPermission(ctx)) ", may defrost ✓" else ", permission not granted yet (asked on first use)"
        }
        return "$b\n$ice"
    }

    // ---------- opening ----------

    /** Pending link while the Ice Box permission dialog is open. */
    private var pending: Pair<String, String>? = null

    /**
     * Opens [url]: normally if some app (Google Maps or a browser) can open it; otherwise defrosts the
     * browser with Ice Box first. [reqIceBox] is the request code used for the Ice Box permission.
     */
    fun open(act: Activity, url: String, what: String, reqIceBox: Int) {
        val intent = viewIntent(url)
        val ri = act.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val handler = ri?.activityInfo?.packageName
        if (ri != null && handler != null) {
            if (browsers(act).any { it.pkg == handler })
                LogStore.prefs(act).edit().putString(KEY_LAST_BROWSER, handler).apply()
            try { act.startActivity(intent); return }
            catch (e: Exception) { EventLog.add(act, EventLog.BROWSER, "$what: could not open ($handler)", e) }
        }
        // Nothing can open it: every browser (and Maps) is frozen or missing
        val t = target(act)
        if (t == null) { pick(act) { p -> openWith(act, url, p, what, reqIceBox) }; return }
        openWith(act, url, t, what, reqIceBox)
    }

    private fun viewIntent(url: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)

    private fun openWith(act: Activity, url: String, pkg: String, what: String, reqIceBox: Int) {
        val name = label(act, pkg)
        val state = frozenState(act, pkg)
        if (state == null) { toastLog(act, "$name is not installed", "$what: $pkg is not installed"); return }
        if (state == 0) { start(act, url, pkg, what, null); return }
        if (!iceBoxInstalled(act)) {
            toastLog(act, "$name is frozen and Ice Box is not available", "$what: $pkg frozen ($state), Ice Box not installed/enabled")
            return
        }
        if (!hasIceBoxPermission(act)) {
            pending = url to pkg
            EventLog.add(act, EventLog.BROWSER, "$what: $pkg frozen ($state), asking for the Ice Box permission")
            act.requestPermissions(arrayOf(ICEBOX_PERMISSION), reqIceBox)
            return
        }
        defrostAndStart(act, url, pkg, state, what)
    }

    /** Continue after the Ice Box permission dialog. */
    fun onPermissionResult(act: Activity) {
        val (url, pkg) = pending ?: return
        pending = null
        if (!hasIceBoxPermission(act)) {
            toastLog(act, "Ice Box permission denied – ${label(act, pkg)} stays frozen",
                "Street View: Ice Box permission denied, $pkg not defrosted")
            return
        }
        EventLog.add(act, EventLog.BROWSER, "Ice Box permission granted")
        defrostAndStart(act, url, pkg, frozenState(act, pkg) ?: 0, "Street View")
    }

    private fun defrostAndStart(act: Activity, url: String, pkg: String, state: Int, what: String) {
        val app = act.applicationContext
        val name = label(act, pkg)
        Toast.makeText(act, "Defrosting $name with Ice Box…", Toast.LENGTH_SHORT).show()
        val started = System.currentTimeMillis()
        io.execute {
            var err: Exception? = null
            try { defrost(app, pkg) } catch (e: Exception) { err = e }
            var now = frozenState(app, pkg)
            while (err == null && now != 0 && System.currentTimeMillis() - started < DEFROST_WAIT_MS) {
                Thread.sleep(200)
                now = frozenState(app, pkg)
            }
            val ms = System.currentTimeMillis() - started
            main.post {
                when {
                    err != null -> toastLog(act, "Ice Box could not defrost $name",
                        "$what: Ice Box defrost of $pkg failed", err)
                    now != 0 -> toastLog(act, "$name is still frozen (Ice Box did not defrost it)",
                        "$what: $pkg still frozen ($now) ${ms} ms after asking Ice Box (mode ${iceBoxMode(app)})")
                    else -> start(act, url, pkg, what, "defrosted by Ice Box (was $state) in $ms ms")
                }
            }
        }
    }

    private fun start(act: Activity, url: String, pkg: String, what: String, note: String?) {
        try {
            act.startActivity(viewIntent(url).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            LogStore.prefs(act).edit().putString(KEY_LAST_BROWSER, pkg).apply()
            EventLog.add(act, EventLog.BROWSER, "$what opened in $pkg" + (note?.let { ", $it" } ?: ""))
        } catch (e: Exception) {
            toastLog(act, "${label(act, pkg)} could not open the link", "$what: $pkg could not open the link", e)
        }
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
            .setTitle("Open Street View with")
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
