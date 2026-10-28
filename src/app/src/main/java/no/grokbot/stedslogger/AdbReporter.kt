package no.grokbot.stedslogger

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Optional: reports this phone's wireless-debugging address (ip:port) to a dashboard URL the user
 * enters in Settings (empty = off), so the current adb port is always known. Strictly read-only:
 * it only reads Settings.Global `adb_wifi_enabled` and looks up the phone's own
 * `_adb-tls-connect._tcp` service via NSD (mDNS); it never turns anything on or off.
 *
 * Posts JSON {ip, tailscaleIp, lanIp, port, adbWifi, ts, device} only when the value changed, or as
 * a heartbeat about once an hour.
 */
object AdbReporter {
    private const val TAG = "LocationLogger"
    const val KEY_URL = "dashboard_url"
    private const val KEY_SIG = "adb_report_sig"
    private const val KEY_AT = "adb_report_at"
    const val KEY_STATUS = "adb_report_status"
    private const val SERVICE_TYPE = "_adb-tls-connect._tcp"
    private const val DISCOVERY_MS = 6_000L
    private const val HEARTBEAT_MS = 60 * 60 * 1000L
    private const val MIN_GAP_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    @Volatile private var lastRunAt = 0L

    fun url(ctx: Context): String = LogStore.prefs(ctx).getString(KEY_URL, "")?.trim() ?: ""

    fun saveUrl(ctx: Context, u: String) {
        LogStore.prefs(ctx).edit().putString(KEY_URL, u.trim()).remove(KEY_SIG).remove(KEY_AT).apply()
    }

    fun status(ctx: Context): String? = LogStore.prefs(ctx).getString(KEY_STATUS, null)

    private fun setStatus(ctx: Context, s: String) {
        LogStore.prefs(ctx).edit().putString(KEY_STATUS, s).apply()
    }

    private fun hhmm() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    /** The phone's own device name (Settings › About phone), else the model. */
    private fun deviceName(ctx: Context): String =
        (try { Settings.Global.getString(ctx.contentResolver, "device_name") } catch (_: Exception) { null })
            ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL ?: "phone"

    /** This device's own IPv4 addresses: (tailnet 100.64/10, LAN private range, all). */
    fun ownIps(): Triple<String?, String?, Set<String>> {
        val all = mutableSetOf<String>()
        var ts: String? = null
        var lan: String? = null
        try {
            for (ni in NetworkInterface.getNetworkInterfaces() ?: return Triple(null, null, all)) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a !is Inet4Address) continue
                    val ip = a.hostAddress ?: continue
                    all += ip
                    val b = a.address.map { it.toInt() and 0xFF }
                    if (b[0] == 100 && b[1] in 64..127) { if (ts == null) ts = ip }
                    else if (b[0] == 10 || (b[0] == 192 && b[1] == 168) || (b[0] == 172 && b[1] in 16..31)) { if (lan == null || ni.name.startsWith("wlan")) lan = ip }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "interfaces", e) }
        return Triple(ts, lan, all)
    }

    private fun adbWifi(ctx: Context): String = when (
        try { Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", -1) } catch (_: Exception) { -1 }
    ) { 1 -> "on"; 0 -> "off"; else -> "unknown" }

    /** Looks up and reports if a URL is set; [force] posts even if nothing changed. */
    fun maybeReport(ctx0: Context, force: Boolean = false) {
        val ctx = ctx0.applicationContext
        val u = url(ctx)
        if (u.isEmpty()) return
        val now = System.currentTimeMillis()
        if (running || (!force && now - lastRunAt < MIN_GAP_MS)) return
        running = true
        lastRunAt = now
        val wifi = adbWifi(ctx)
        if (wifi == "off") { finish(ctx, u, wifi, null, force); return }
        discoverPort(ctx) { port -> finish(ctx, u, wifi, port, force) }
    }

    private fun finish(ctx: Context, u: String, wifi: String, port: Int?, force: Boolean) {
        val (ts, lan, _) = ownIps()
        if (wifi != "off" && port == null) {
            setStatus(ctx, "${hhmm()}: wireless debugging port not found (not reported)")
            running = false
            return
        }
        val ip = ts ?: lan
        val sig = "$wifi|$ts|$lan|$port"
        val p = LogStore.prefs(ctx)
        val due = force || sig != p.getString(KEY_SIG, null) ||
            System.currentTimeMillis() - p.getLong(KEY_AT, 0L) > HEARTBEAT_MS
        if (!due) { running = false; return }
        val body = JSONObject().apply {
            put("ip", ip ?: JSONObject.NULL); put("tailscaleIp", ts ?: JSONObject.NULL); put("lanIp", lan ?: JSONObject.NULL)
            put("port", port ?: JSONObject.NULL); put("adbWifi", wifi); put("ts", System.currentTimeMillis())
            put("device", deviceName(ctx))
        }.toString()
        io.execute {
            val shown = if (wifi == "off") "wireless debugging off" else "$ip:$port"
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 10_000; c.readTimeout = 10_000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                c.disconnect()
                if (code in 200..299) {
                    p.edit().putString(KEY_SIG, sig).putLong(KEY_AT, System.currentTimeMillis()).apply()
                    setStatus(ctx, "Last reported: $shown at ${hhmm()}")
                } else setStatus(ctx, "${hhmm()}: dashboard answered HTTP $code ($shown not reported)")
            } catch (e: Exception) {
                setStatus(ctx, "${hhmm()}: could not reach the dashboard (${e.javaClass.simpleName})")
            } finally { running = false }
        }
    }

    /** Finds this device's own `_adb-tls-connect._tcp` service and returns its port (null if not found). */
    private fun discoverPort(ctx: Context, done: (Int?) -> Unit) {
        val nsd = ctx.getSystemService(NsdManager::class.java)
        if (nsd == null) { done(null); return }
        val own = ownIps().third
        val found = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        var result: Int? = null
        var finished = false

        lateinit var listener: NsdManager.DiscoveryListener
        fun end() {
            if (finished) return
            finished = true
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
            done(result)
        }
        fun resolveNext() {
            if (finished || resolving || result != null) return
            val s = found.removeFirstOrNull() ?: return
            resolving = true
            @Suppress("DEPRECATION")
            try {
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, err: Int) { main.post { resolving = false; resolveNext() } }
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        main.post {
                            resolving = false
                            val host = si.host?.hostAddress
                            if (host != null && host in own) { result = si.port; end() } else resolveNext()
                        }
                    }
                })
            } catch (e: Exception) { resolving = false }
        }
        listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) { main.post { end() } }
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(si: NsdServiceInfo) { main.post { found.addLast(si); resolveNext() } }
            override fun onServiceLost(si: NsdServiceInfo) {}
        }
        main.post {
            try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            catch (e: Exception) { Log.w(TAG, "nsd", e); end(); return@post }
            main.postDelayed({ end() }, DISCOVERY_MS)
        }
    }
}
