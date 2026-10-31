package no.grokbot.stedslogger

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Log tab (since 1.25): every entry of [EventLog] (checks, raw fixes, visit outcomes, adb reports,
 * network/service events, errors) plus the stored visits (arrival / last seen), newest first.
 * Times are shown in the phone's own time zone.
 */
class LogTab(private val act: Activity) {
    companion object {
        private const val ALL = "Alle typer"
        private const val VISIT_LIST = "BESØKSLISTE"
        /** Clipboard / share intents go through Binder (~1 MB limit): keep the text below this. */
        private const val MAX_SHARE_CHARS = 400_000
    }

    private val list: ListView = act.findViewById(R.id.logList)
    private val count: TextView = act.findViewById(R.id.logCount)
    private val filter: Spinner = act.findViewById(R.id.logFilter)
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    private var all: List<LogEntry> = emptyList()
    private var shown: List<LogEntry> = emptyList()
    private var loading = false
    private val types = listOf(ALL, EventLog.CHECK, EventLog.FIX, EventLog.VISIT, VISIT_LIST,
        EventLog.ADB, EventLog.SHIZUKU, EventLog.TAILSCALE, EventLog.BROWSER, EventLog.CALL, EventLog.SMS, EventLog.LOOKUP, EventLog.RECORDING, EventLog.TTS, EventLog.NET, EventLog.SERVICE, EventLog.ERROR)

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = (convertView as? TextView) ?: TextView(act).apply {
                textSize = 12.5f
                val d = act.resources.displayMetrics.density
                setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())
            }
            val e = shown[position]
            val head = "${fmt.format(Date(e.at))}  ${e.type}"
            val sp = android.text.SpannableString("$head\n${e.msg}")
            sp.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, head.length, 0)
            sp.setSpan(android.text.style.ForegroundColorSpan(colorFor(e.type)), 0, head.length, 0)
            tv.text = sp
            return tv
        }
    }

    private fun colorFor(type: String): Int = when (type) {
        EventLog.ERROR -> 0xFFC62828.toInt()
        EventLog.ADB -> 0xFF6A1B9A.toInt()
        EventLog.FIX -> 0xFF2E7D32.toInt()
        EventLog.VISIT, VISIT_LIST -> 0xFF1565C0.toInt()
        EventLog.NET -> 0xFFEF6C00.toInt()
        EventLog.SHIZUKU -> 0xFF00838F.toInt()
        EventLog.TAILSCALE -> 0xFF283593.toInt()
        EventLog.BROWSER -> 0xFF4E342E.toInt()
        EventLog.CALL, EventLog.SMS -> 0xFF00695C.toInt()
        EventLog.LOOKUP -> 0xFFAD1457.toInt()
        EventLog.RECORDING, EventLog.TTS -> 0xFF5D4037.toInt()
        else -> 0xFF424242.toInt()
    }

    init {
        list.adapter = adapter
        list.setOnItemLongClickListener { _, _, pos, _ ->
            copy(line(shown[pos]), "Oppføringen er kopiert"); true
        }
        filter.adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, types)
        filter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = applyFilter()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        act.findViewById<Button>(R.id.btnLogRefresh).setOnClickListener { load() }
        act.findViewById<Button>(R.id.btnLogCopy).setOnClickListener { copy(text(), "Loggen er kopiert (${shown.size} oppføringer)") }
        act.findViewById<Button>(R.id.btnLogShare).setOnClickListener { share() }
        act.findViewById<Button>(R.id.btnLogClear).setOnClickListener {
            AlertDialog.Builder(act)
                .setTitle("Tømme loggen?")
                .setMessage("Alle oppføringer i loggen slettes. Besøkene (fanen Visits) beholdes.")
                .setPositiveButton("Tøm") { _, _ ->
                    EventLog.afterPending {
                        EventLog.clear(act)
                        act.runOnUiThread { Toast.makeText(act, "Loggen er tømt", Toast.LENGTH_SHORT).show(); load() }
                    }
                }
                .setNegativeButton("Avbryt", null)
                .show()
        }
    }

    /** Reads the log and the visits in the background (after pending writes), then shows them. */
    fun load() {
        if (loading) return
        loading = true
        count.text = "Laster …"
        EventLog.afterPending {
            val events = EventLog.all(act)
            val visits = try { VisitStore.all(act) } catch (_: Exception) { emptyList() }
            val fromVisits = buildList {
                for (v in visits) {
                    val coords = if (v.lat != null && v.lon != null)
                        String.format(Locale.ROOT, " · %.6f, %.6f", v.lat, v.lon) else ""
                    val dist = v.travelledM?.let { String.format(Locale.ROOT, " · kjørt %.1f km", it / 1000) } ?: ""
                    add(LogEntry(v.arrival, VISIT_LIST, "Ankomst: ${v.name}$coords$dist" + if (v.imported) " (importert)" else ""))
                    v.departure?.takeIf { it > v.arrival }?.let { add(LogEntry(it, VISIT_LIST, "Sist sett: ${v.name}")) }
                }
            }
            val merged = (events + fromVisits).sortedByDescending { it.at }
            act.runOnUiThread {
                loading = false
                all = merged
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val t = types.getOrNull(filter.selectedItemPosition) ?: ALL
        shown = if (t == ALL) all else all.filter { it.type == t }
        adapter.notifyDataSetChanged()
        val nEvents = all.count { it.type != VISIT_LIST }
        count.text = "${shown.size} av ${all.size} oppføringer (logg: $nEvents av maks ${EventLog.MAX}, " +
            "besøksliste: ${all.size - nEvents}) · nyeste først · hold inne for å kopiere én"
    }

    private fun line(e: LogEntry) = "${fmt.format(Date(e.at))}\t${e.type}\t${e.msg}"

    /** The shown entries as text, newest first (cut to [MAX_SHARE_CHARS]). */
    private fun text(): String {
        val sb = StringBuilder()
        sb.append("LocationLogger-logg, ${shown.size} oppføringer, tidssone ${java.util.TimeZone.getDefault().id}\n")
        for (e in shown) {
            val l = line(e)
            if (sb.length + l.length + 1 > MAX_SHARE_CHARS) { sb.append("… (avkortet)\n"); break }
            sb.append(l).append('\n')
        }
        return sb.toString()
    }

    private fun copy(s: String, done: String) {
        try {
            val cm = act.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("LocationLogger-logg", s))
            Toast.makeText(act, done, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(act, "Kunne ikke kopiere: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun share() {
        try {
            act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "LocationLogger-logg")
                .putExtra(Intent.EXTRA_TEXT, text()), "Del loggen"))
        } catch (e: Exception) {
            Toast.makeText(act, "Kunne ikke dele: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }
}
