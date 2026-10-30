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
 * Posts JSON {ip, tailscaleIp, lanIp, port, adbWifi, ts, device, deviceId, model, tcp5555, shizuku} only when the value changed, or as
 * a heartbeat about once an hour.
 */
object AdbReporter {
    private const val TAG = "LocationLogger"
    const val KEY_URL = "dashboard_url"
    private const val KEY_SIG = "adb_report_sig"
    private const val KEY_AT = "adb_report_at"
    const val KEY_STATUS = "adb_report_status"
    const val KEY_KEEP_WIFI = "keep_adb_wifi_on"
    private const val KEY_TCP = "adb_tcp5555_last"
    const val KEY_KEEP_STATUS = "keep_adb_wifi_status"
    private const val KEY_DEVICE_ID = "adb_report_device_id"
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
        EventLog.add(ctx, EventLog.ADB, s)
    }

    private fun hhmm() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    /** The phone's own device name (Settings › About phone), else the model. */
    private fun deviceName(ctx: Context): String =
        (try { Settings.Global.getString(ctx.contentResolver, "device_name") } catch (_: Exception) { null })
            ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL ?: "phone"

    /** Random, app-generated id (stored in prefs) so the dashboard can keep several phones apart. */
    fun deviceId(ctx: Context): String {
        val p = LogStore.prefs(ctx)
        p.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        p.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    private fun isCgnat(b: List<Int>) = b[0] == 100 && b[1] in 64..127

    /** Names of interfaces that belong to a VPN network (Tailscale runs as a VPN, usually on `tun0`). */
    private fun vpnInterfaces(ctx: Context?): Set<String> = try {
        val cm = ctx?.getSystemService(android.net.ConnectivityManager::class.java)
        cm?.allNetworks?.filter { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        }?.mapNotNull { cm.getLinkProperties(it)?.interfaceName }?.toSet() ?: emptySet()
    } catch (_: Exception) { emptySet() }

    /**
     * This device's own IPv4 addresses: (tailnet IP, LAN private range, all). The tailnet IP is only taken
     * from a VPN interface (ConnectivityManager TRANSPORT_VPN, or a `tun*` interface) with an address in
     * 100.64/10 – never from the mobile data interface, where carriers also hand out 100.64/10 (CGNAT) addresses.
     */
    fun ownIps(ctx: Context? = null): Triple<String?, String?, Set<String>> {
        val all = mutableSetOf<String>()
        var ts: String? = null
        var lan: String? = null
        val vpn = vpnInterfaces(ctx)
        try {
            for (ni in NetworkInterface.getNetworkInterfaces() ?: return Triple(null, null, all)) {
                if (!ni.isUp || ni.isLoopback) continue
                val isVpn = ni.name in vpn || ni.name.startsWith("tun")
                for (a in ni.inetAddresses) {
                    if (a !is Inet4Address) continue
                    val ip = a.hostAddress ?: continue
                    all += ip
                    val b = a.address.map { it.toInt() and 0xFF }
                    if (isCgnat(b)) { if (isVpn && ts == null) ts = ip }
                    else if (b[0] == 10 || (b[0] == 192 && b[1] == 168) || (b[0] == 172 && b[1] in 16..31)) {
                        if (!isVpn && (lan == null || ni.name.startsWith("wlan"))) lan = ip
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, "interfaces", e) }
        return Triple(ts, lan, all)
    }

    /** "Keep wireless debugging on": default on when WRITE_SECURE_SETTINGS is granted. */
    fun keepWifiOn(ctx: Context): Boolean = LogStore.prefs(ctx).getBoolean(KEY_KEEP_WIFI, true)

    fun setKeepWifiOn(ctx: Context, on: Boolean) {
        LogStore.prefs(ctx).edit().putBoolean(KEY_KEEP_WIFI, on).apply()
    }

    private fun onWifi(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        cm?.allNetworks?.any { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        } == true
    } catch (_: Exception) { false }

    /**
     * If enabled and permitted (WRITE_SECURE_SETTINGS, granted via adb), turns wireless debugging back
     * on (Settings.Global adb_wifi_enabled = 1) when it is off and the phone is on Wi-Fi. This is the
     * only setting written here.
     */
    fun ensureWifiDebugging(ctx: Context): Boolean {
        if (!keepWifiOn(ctx) || !GpsToggle.hasPermission(ctx)) return false
        val cur = try { Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", -1) } catch (_: Exception) { -1 }
        if (cur != 0 || !onWifi(ctx)) return false
        val ok = try { Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 1) } catch (e: Exception) { EventLog.error(ctx, "adb_wifi_enabled", e); false }
        EventLog.add(ctx, EventLog.ADB, if (ok) "Trådløs feilsøking var av – slått på igjen" else "Trådløs feilsøking var av – kunne ikke slå den på")
        LogStore.prefs(ctx).edit().putString(KEY_KEEP_STATUS,
            "${hhmm()}: " + (if (ok) "turned wireless debugging on" else "could not turn wireless debugging on")).apply()
        return ok
    }

    /**
     * Whether adbd listens on TCP 5555 (adb tcpip mode): /proc/net/tcp(6) if readable, else a short
     * local connection attempt to 127.0.0.1:5555 (closed at once). Must not run on the main thread.
     */
    fun tcp5555(): Boolean {
        for (f in listOf("/proc/net/tcp6", "/proc/net/tcp")) {
            try {
                val lines = java.io.File(f).readLines()
                if (lines.drop(1).any { l ->
                        val c = l.trim().split(Regex("\\s+"))
                        c.size > 3 && c[1].endsWith(":15B3", ignoreCase = true) && c[3] == "0A"
                    }) return true
            } catch (_: Exception) {}
        }
        return try {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", 5555), 700) }
            true
        } catch (_: Exception) { false }
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
        if (running) { if (force) pendingForce = true; return }
        if (!force && now - lastRunAt < MIN_GAP_MS) return
        running = true
        lastRunAt = now
        val turnedOn = try { ensureWifiDebugging(ctx) } catch (_: Exception) { false }
        val wifi = adbWifi(ctx)
        if (wifi == "off") { io.execute { finish(ctx, u, wifi, null, force, tcp5555()) }; return }
        val find = { discoverPort(ctx) { port -> io.execute { finish(ctx, u, wifi, port, force || turnedOn, tcp5555()) } } }
        // Just switched on: give adbd a few seconds to publish its new port via NSD before looking it up
        if (turnedOn) main.postDelayed(find, 4000) else find()
    }

    /** A forced report that arrived while one was running is sent right after it. */
    @Volatile private var pendingForce = false
    private fun done(ctx: Context) {
        running = false
        if (pendingForce) { pendingForce = false; main.postDelayed({ maybeReport(ctx, force = true) }, 2000) }
    }

    /** Runs on [io]. */
    private fun finish(ctx: Context, u: String, wifi: String, port: Int?, force: Boolean, tcp: Boolean) {
        val (ts, lan, _) = ownIps(ctx)
        LogStore.prefs(ctx).edit().putBoolean(KEY_TCP, tcp).apply()
        if (wifi != "off" && port == null && !tcp) {
            setStatus(ctx, "${hhmm()}: wireless debugging port not found (not reported)")
            done(ctx)
            return
        }
        val ip = ts ?: lan
        val shizuku = ShizukuWatch.lastState(ctx)
        val sig = "$wifi|$ts|$lan|$port|$tcp|$shizuku"
        val p = LogStore.prefs(ctx)
        val due = force || sig != p.getString(KEY_SIG, null) ||
            System.currentTimeMillis() - p.getLong(KEY_AT, 0L) > HEARTBEAT_MS
        if (!due) {
            EventLog.add(ctx, EventLog.ADB, "Uendret (${if (wifi == "off") "trådløs feilsøking av" else "port ${port ?: "?"}"}${if (tcp) ", tcp 5555" else ""}) – ikke rapportert")
            done(ctx); return
        }
        val body = JSONObject().apply {
            put("ip", ip ?: JSONObject.NULL); put("tailscaleIp", ts ?: JSONObject.NULL); put("lanIp", lan ?: JSONObject.NULL)
            put("port", port ?: JSONObject.NULL); put("adbWifi", wifi); put("ts", System.currentTimeMillis())
            put("device", deviceName(ctx))
            put("deviceId", deviceId(ctx))
            put("model", android.os.Build.MODEL ?: JSONObject.NULL)
            put("tcp5555", tcp)
            put("shizuku", shizuku)
        }.toString()
        val shown = (if (wifi == "off") "wireless debugging off" else "$ip:${port ?: "?"}") + (if (tcp) " · tcp 5555" else "") + " · Shizuku $shizuku"
        try {
            when (val r = try { post(ctx, u, body) } catch (e: Exception) { PostResult.Failed(e) }) {
                is PostResult.Ok -> {
                    p.edit().putString(KEY_SIG, sig).putLong(KEY_AT, System.currentTimeMillis()).apply()
                    setStatus(ctx, "Last reported: $shown at ${hhmm()}" + (r.via?.let { " (via fallback IP $it)" } ?: ""))
                }
                is PostResult.Http -> setStatus(ctx, "${hhmm()}: dashboard answered HTTP ${r.code} ($shown not reported)")
                is PostResult.Failed -> {
                    setStatus(ctx, "${hhmm()}: could not reach the dashboard (${r.e.javaClass.simpleName})")
                    EventLog.error(ctx, "Dashbord-rapport feilet" + vpnHint(ctx), r.e)
                }
            }
        } finally { done(ctx) }
    }

    // ---------- POST with DNS retry and fallback IP (1.26) ----------

    const val KEY_FALLBACK_IP = "dashboard_fallback_ip"
    private const val KEY_LEARNED_IP = "dashboard_learned_ip"     // "host|ip" of the last successful lookup
    /** Waits before the 2nd, 3rd and 4th attempt when the host name cannot be resolved. */
    private val DNS_BACKOFF_MS = longArrayOf(5_000L, 15_000L, 45_000L)

    fun fallbackIp(ctx: Context): String = LogStore.prefs(ctx).getString(KEY_FALLBACK_IP, "")?.trim() ?: ""

    fun saveFallbackIp(ctx: Context, ip: String) {
        LogStore.prefs(ctx).edit().putString(KEY_FALLBACK_IP, ip.trim()).apply()
    }

    /** Accepts an IPv4 or IPv6 literal only (never a host name, so no DNS lookup is needed). */
    fun isIpLiteral(s: String): Boolean {
        val t = s.trim().removePrefix("[").removeSuffix("]")
        if (Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matches(t))
            return t.split('.').all { it.toInt() in 0..255 }
        return t.contains(':') && Regex("""^[0-9a-fA-F:.]+$""").matches(t)
    }

    /** The IP the dashboard host last resolved to (null if none or for another host). */
    fun learnedIp(ctx: Context, host: String? = null): String? {
        val v = LogStore.prefs(ctx).getString(KEY_LEARNED_IP, null) ?: return null
        val (h, ip) = v.split('|', limit = 2).let { if (it.size == 2) it[0] to it[1] else return null }
        return if (host == null || h.equals(host, ignoreCase = true)) ip else null
    }

    private fun learn(ctx: Context, host: String) {
        if (isIpLiteral(host)) return
        try {
            val ip = java.net.InetAddress.getAllByName(host).let { a -> a.firstOrNull { it is Inet4Address } ?: a.firstOrNull() }
                ?.hostAddress ?: return
            if (learnedIp(ctx, host) != ip) {
                LogStore.prefs(ctx).edit().putString(KEY_LEARNED_IP, "$host|$ip").apply()
                EventLog.add(ctx, EventLog.ADB, "Lærte dashbord-IP: $host → $ip (brukes som reserve)")
            }
        } catch (_: Exception) {}
    }

    private fun vpnHint(ctx: Context) = if (vpnInterfaces(ctx).isEmpty()) " (ingen VPN aktiv – er Tailscale på?)" else ""

    private sealed class PostResult {
        class Ok(val via: String?) : PostResult()
        class Http(val code: Int) : PostResult()
        class Failed(val e: Exception) : PostResult()
    }

    private fun isDnsFailure(e: Throwable?): Boolean {
        var c = e
        while (c != null) { if (c is java.net.UnknownHostException) return true; c = c.cause }
        return false
    }

    /**
     * POSTs [body] to [u]. If the host name cannot be resolved, tries the fallback IPs (the user's
     * setting, then the learned IP) – connecting to the IP but keeping TLS SNI and certificate/host
     * name verification for the original host – and retries with backoff ([DNS_BACKOFF_MS]).
     * Runs on [io]; every attempt is written to the Log tab.
     */
    private fun post(ctx: Context, u: String, body: String): PostResult {
        val url = URL(u)
        val host = url.host
        var last: Exception? = null
        for (attempt in 0..DNS_BACKOFF_MS.size) {
            if (attempt > 0) {
                val wait = DNS_BACKOFF_MS[attempt - 1]
                EventLog.add(ctx, EventLog.ADB, "Venter ${wait / 1000} s før nytt forsøk (${attempt + 1}/${DNS_BACKOFF_MS.size + 1})")
                try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
            }
            val n = attempt + 1
            try {
                val code = postDirect(url, body)
                EventLog.add(ctx, EventLog.ADB, "Forsøk $n via vertsnavn $host: HTTP $code")
                if (code in 200..299) { learn(ctx, host); return PostResult.Ok(null) }
                return PostResult.Http(code)
            } catch (e: Exception) {
                last = e
                if (!isDnsFailure(e)) {
                    EventLog.add(ctx, EventLog.ADB, "Forsøk $n via vertsnavn $host feilet: ${e.javaClass.simpleName}")
                    return PostResult.Failed(e)
                }
                EventLog.add(ctx, EventLog.ADB, "Forsøk $n: kunne ikke slå opp $host (DNS)${vpnHint(ctx)}")
            }
            val ips = linkedMapOf<String, String>()
            fallbackIp(ctx).takeIf { isIpLiteral(it) }?.let { ips[it] = "innstilling" }
            learnedIp(ctx, host)?.let { if (it !in ips) ips[it] = "lært" }
            if (attempt == 0 && ips.isEmpty())
                EventLog.add(ctx, EventLog.ADB, "Ingen reserve-IP (sett Dashbord-IP (reserve), eller vent til IP-en læres)")
            for ((ip, src) in ips) {
                try {
                    val code = postViaIp(url, ip, body)
                    EventLog.add(ctx, EventLog.ADB, "Forsøk $n via reserve-IP $ip ($src), TLS for $host: HTTP $code")
                    if (code in 200..299) return PostResult.Ok(ip)
                    return PostResult.Http(code)
                } catch (e: Exception) {
                    last = e
                    EventLog.add(ctx, EventLog.ADB, "Forsøk $n via reserve-IP $ip ($src) feilet: ${e.javaClass.simpleName}" +
                        (e.message?.let { " – $it" } ?: ""))
                }
            }
        }
        return PostResult.Failed(last ?: java.io.IOException("unknown"))
    }

    private fun postDirect(url: URL, body: String): Int {
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            return c.responseCode
        } finally { c.disconnect() }
    }

    /**
     * Minimal HTTP/1.1 POST to [ip], with the Host header, TLS SNI and certificate + host name
     * verification all for the URL's real host (so HTTPS stays valid without DNS).
     */
    private fun postViaIp(url: URL, ip: String, body: String): Int {
        val https = url.protocol.equals("https", ignoreCase = true)
        val port = if (url.port > 0) url.port else url.defaultPort
        val host = url.host
        val raw = java.net.Socket()
        raw.connect(java.net.InetSocketAddress(java.net.InetAddress.getByName(ip.removePrefix("[").removeSuffix("]")), port), 10_000)
        raw.soTimeout = 10_000
        val sock: java.net.Socket = if (https) {
            val f = javax.net.ssl.SSLContext.getDefault().socketFactory
            val ssl = f.createSocket(raw, host, port, true) as javax.net.ssl.SSLSocket
            try {
                val params = ssl.sslParameters
                params.serverNames = listOf(javax.net.ssl.SNIHostName(host))
                ssl.sslParameters = params
            } catch (_: Exception) {}
            ssl.startHandshake() // validates the certificate chain against the system trust store
            if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                ssl.close()
                throw javax.net.ssl.SSLPeerUnverifiedException("certificate does not match $host")
            }
            ssl
        } else raw
        sock.use { s ->
            val bytes = body.toByteArray()
            val path = (url.path.ifEmpty { "/" }) + (url.query?.let { "?$it" } ?: "")
            val hostHeader = if (url.port > 0 && url.port != url.defaultPort) "$host:${url.port}" else host
            val head = "POST $path HTTP/1.1\r\nHost: $hostHeader\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            val out = s.getOutputStream()
            out.write(head.toByteArray(Charsets.US_ASCII)); out.write(bytes); out.flush()
            val status = s.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine()
                ?: throw java.io.IOException("no response")
            return status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw java.io.IOException("bad status line")
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
