package no.grokbot.stedslogger

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Samtaler tab (1.32): calls, SMS and MMS from Android's own logs, newest first, with names from
 * contacts or the directory lookup, matched call recordings (play in the app) and "Legg til i kontakter".
 * Read on demand; nothing is stored or sent anywhere (except unknown numbers to the lookup).
 */
class CommsTab(private val act: Activity, private val askPermissions: () -> Unit) {
    private enum class Filter { ALL, CALLS, MSGS }

    private class Row(val item: CommItem, val contact: String?, val lookup: NumberLookup.Result?, val recs: List<Recordings.Rec>)

    private val list: ListView = act.findViewById(R.id.commList)
    private val info: TextView = act.findViewById(R.id.commInfo)
    private val permBtn: Button = act.findViewById(R.id.btnCommPerms)
    private val chips = listOf<Button>(act.findViewById(R.id.chipAll), act.findViewById(R.id.chipCalls), act.findViewById(R.id.chipMsgs))
    private var filter = Filter.ALL
    private var rows: List<Row> = emptyList()
    private var shown: List<Row> = emptyList()
    private var loading = false
    private var autoLookupRunning = false
    private val d = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.ROOT)
    private val dayFmt = SimpleDateFormat("d.M.yyyy HH:mm", Locale.ROOT)
    private val dayKey = SimpleDateFormat("yyyyMMdd", Locale.ROOT)

    init {
        list.adapter = adapter()
        chips.forEachIndexed { i, b -> b.setOnClickListener { filter = Filter.values()[i]; applyFilter() } }
        act.findViewById<Button>(R.id.btnCommRefresh).setOnClickListener { Phone.clearCache(); load() }
        permBtn.setOnClickListener { askPermissions() }
        list.setOnItemClickListener { _, _, pos, _ -> details(shown[pos]) }
    }

    fun load() {
        if (loading) return
        loading = true
        info.text = "Laster …"
        Thread {
            val items = try { Comms.all(act) } catch (_: Exception) { emptyList() }
            val recs = try { Recordings.all(act) } catch (_: Exception) { emptyList() }
            val built = items.map { Row(it, Phone.contactName(act, it.number), NumberLookup.cached(act, it.number), Recordings.forCall(recs, it)) }
            act.runOnUiThread {
                loading = false
                rows = built
                applyFilter()
                updateInfo(recs.size)
                autoLookup()
            }
        }.start()
    }

    private var recCount = 0

    private fun updateInfo(nRecs: Int = recCount) {
        recCount = nRecs
        val calls = Comms.canReadCalls(act)
        val sms = Comms.canReadSms(act)
        val missing = listOfNotNull(if (!calls) "samtalelogg" else null, if (!sms) "SMS/MMS" else null,
            if (!Recordings.canRead(act)) "lydopptak" else null)
        permBtn.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        val matched = rows.count { it.recs.isNotEmpty() }
        info.text = "${shown.size} av ${rows.size} · opptak: $nRecs funnet, $matched samtaler med opptak" +
            (if (missing.isNotEmpty()) "\n⚠ Mangler tilgang til: ${missing.joinToString(", ")}" else "") +
            (NumberLookup.status(act)?.let { "\nOppslag: $it" } ?: "")
    }

    private fun applyFilter() {
        shown = when (filter) {
            Filter.ALL -> rows
            Filter.CALLS -> rows.filter { it.item.kind == CommItem.Kind.CALL }
            Filter.MSGS -> rows.filter { it.item.kind != CommItem.Kind.CALL }
        }
        chips.forEachIndexed { i, b ->
            val on = i == filter.ordinal
            b.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF1565C0.toInt())
            b.background.mutate().setTint(if (on) 0xFF1565C0.toInt() else 0xFFE3F2FD.toInt())
        }
        (list.adapter as BaseAdapter).notifyDataSetChanged()
        updateInfo()
    }

    /** Looks up (in the background, rate limited) unknown numbers among the newest entries. */
    private fun autoLookup() {
        if (autoLookupRunning || !NumberLookup.enabled(act)) return
        val todo = rows.take(60).mapNotNull { r -> r.item.number?.takeIf { r.contact == null && r.lookup == null } }
            .distinctBy { Phone.key(it) }.filter { NumberLookup.needsLookup(act, it) }.take(15)
        if (todo.isEmpty()) return
        autoLookupRunning = true
        Thread {
            var changed = false
            for (n in todo) {
                val r = try { NumberLookup.lookup(act, n) } catch (_: Exception) { null } ?: break
                Contacts.maybeAutoAdd(act, n, r)
                if (r.name != null) changed = true
            }
            act.runOnUiThread { autoLookupRunning = false; if (changed) { Phone.clearCache(); load() } else updateInfo() }
        }.start()
    }

    private fun when_(t: Long): String {
        val now = System.currentTimeMillis()
        return when (dayKey.format(Date(t))) {
            dayKey.format(Date(now)) -> "i dag " + timeFmt.format(Date(t))
            dayKey.format(Date(now - 86_400_000L)) -> "i går " + timeFmt.format(Date(t))
            else -> dayFmt.format(Date(t))
        }
    }

    private fun dur(s: Long): String = when {
        s <= 0 -> ""
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min ${s % 60} s"
        else -> "${s / 3600} t ${(s % 3600) / 60} min"
    }

    private fun icon(it: CommItem): Pair<String, Int> = when (it.kind) {
        CommItem.Kind.CALL -> when (it.dir) {
            CommItem.Dir.IN -> "↙" to 0xFF2E7D32.toInt()
            CommItem.Dir.OUT -> "↗" to 0xFF1565C0.toInt()
            CommItem.Dir.MISSED -> "✕" to 0xFFC62828.toInt()
            CommItem.Dir.REJECTED -> "⊘" to 0xFFC62828.toInt()
            CommItem.Dir.BLOCKED -> "⛔" to 0xFF757575.toInt()
            CommItem.Dir.VOICEMAIL -> "✉" to 0xFF6A1B9A.toInt()
            else -> "☎" to 0xFF424242.toInt()
        }
        CommItem.Kind.SMS -> (if (it.dir == CommItem.Dir.OUT) "💬↗" else "💬↙") to (if (it.dir == CommItem.Dir.OUT) 0xFF1565C0.toInt() else 0xFF2E7D32.toInt())
        CommItem.Kind.MMS -> (if (it.dir == CommItem.Dir.OUT) "🖼↗" else "🖼↙") to (if (it.dir == CommItem.Dir.OUT) 0xFF1565C0.toInt() else 0xFF2E7D32.toInt())
    }

    private fun kindText(it: CommItem): String = when (it.kind) {
        CommItem.Kind.CALL -> when (it.dir) {
            CommItem.Dir.IN -> "Innkommende"
            CommItem.Dir.OUT -> "Utgående"
            CommItem.Dir.MISSED -> "Tapt"
            CommItem.Dir.REJECTED -> "Avvist"
            CommItem.Dir.BLOCKED -> "Blokkert"
            CommItem.Dir.VOICEMAIL -> "Talepost"
            else -> "Samtale"
        }
        CommItem.Kind.SMS -> if (it.dir == CommItem.Dir.OUT) "SMS sendt" else "SMS mottatt"
        CommItem.Kind.MMS -> if (it.dir == CommItem.Dir.OUT) "MMS sendt" else "MMS mottatt"
    }

    private fun title(r: Row): String = r.contact ?: r.lookup?.let { l -> l.name?.let { "$it (${l.source})" } }
        ?: r.item.cachedName ?: Phone.pretty(r.item.number)

    private fun small(text: String, onClick: () -> Unit) = Button(act, null, android.R.attr.buttonStyleSmall).apply {
        this.text = text
        textSize = 12f
        isAllCaps = false
        minHeight = 0; minimumHeight = 0
        setPadding(dp(8), dp(2), dp(8), dp(2))
        isFocusable = false
        setOnClickListener { onClick() }
    }

    private fun adapter() = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val r = shown[position]
            val it = r.item
            val (ic, color) = icon(it)
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(4), dp(6), dp(4), dp(6))
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            row.addView(TextView(act).apply {
                text = ic; textSize = 18f; setTextColor(color)
                layoutParams = LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            val col = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(col)
            col.addView(TextView(act).apply {
                text = title(r); textSize = 15f; setTypeface(typeface, Typeface.BOLD)
                if (it.dir == CommItem.Dir.MISSED || it.dir == CommItem.Dir.REJECTED) setTextColor(0xFFC62828.toInt())
            })
            val named = r.contact != null || r.lookup?.name != null || it.cachedName != null
            col.addView(TextView(act).apply {
                text = listOfNotNull(if (named) Phone.pretty(it.number) else null, kindText(it), when_(it.at),
                    dur(it.durationS).ifEmpty { null }).joinToString(" · ")
                textSize = 12.5f; setTextColor(0xFF616161.toInt())
            })
            it.subject?.let { sub -> col.addView(TextView(act).apply {
                text = "«$sub»"; textSize = 13f; setTypeface(typeface, Typeface.ITALIC); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }) }
            if (!it.text.isNullOrBlank()) col.addView(TextView(act).apply {
                text = it.text; textSize = 13f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (it.others.isNotEmpty()) col.addView(TextView(act).apply {
                text = groupLine(it, 3); textSize = 12f; setTextColor(0xFF616161.toInt()); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (it.parts.isNotEmpty()) col.addView(attachmentStrip(r, dp(72), 4))
            val buttons = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            r.recs.forEachIndexed { i, rec ->
                buttons.addView(small(if (r.recs.size > 1) "▶ Opptak ${i + 1}" else "▶ Opptak") { Player.show(act, rec, title(r)) })
            }
            val k = Phone.key(it.number)
            if (r.contact == null && Phone.isNorwegian(k)) {
                val lk = r.lookup
                if (lk?.name != null) buttons.addView(small("+ Legg til i kontakter") { addContact(r) })
                else if (lk == null) buttons.addView(small("🔍 Slå opp") { lookupMenu(r) })
                else buttons.addView(small("🌐 Nettvisning") { WebLookupActivity.open(act, it.number) })
            }
            if (buttons.childCount > 0) col.addView(buttons)
            return row
        }
    }

    /** "Gruppe: Kari, 912 34 567 +2" (other participants of a group MMS). */
    private fun groupLine(it: CommItem, max: Int): String {
        val names = it.others.map { n -> Phone.contactName(act, n) ?: NumberLookup.cached(act, n)?.name ?: Phone.pretty(n) }
        val head = if (it.dir == CommItem.Dir.OUT) "Til" else "Gruppe (${it.others.size + 1})"
        val shownNames = names.take(max).joinToString(", ")
        return "$head: $shownNames" + if (names.size > max) " +${names.size - max}" else ""
    }

    /** Image thumbnails (tap = fullscreen) and chips for video / audio / vCard / other (tap = open with …). */
    private fun attachmentStrip(r: Row, size: Int, maxImages: Int): View {
        val strip = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(2)) }
        val imgs = r.item.images
        imgs.take(maxImages).forEach { part ->
            val iv = ImageView(act).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFFE0E0E0.toInt())
                contentDescription = "Bilde"
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(4) }
                isFocusable = false
                setOnClickListener { MmsMedia.showImage(act, part, title(r)) }
            }
            val bmp = MmsMedia.cached(part)
            if (bmp != null) iv.setImageBitmap(bmp)
            else MmsMedia.thumb(act, part, dp(160)) { b ->
                if (b == null) return@thumb
                if (iv.isAttachedToWindow) iv.setImageBitmap(b) else (list.adapter as BaseAdapter).notifyDataSetChanged()
            }
            strip.addView(iv)
        }
        if (imgs.size > maxImages) strip.addView(small("+${imgs.size - maxImages} bilder") { details(r) })
        r.item.parts.filter { !it.isImage }.forEach { part -> strip.addView(small(part.label()) { MmsMedia.open(act, part) }) }
        return HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(strip) }
    }

    /** Automatic lookup, or the page in the in-app web view (1881 / gulesider personer / bedrifter). */
    private fun lookupMenu(r: Row) {
        AlertDialog.Builder(act)
            .setTitle("Slå opp ${Phone.pretty(r.item.number)}")
            .setItems(arrayOf("Slå opp automatisk", "Åpne 1881.no i nettvisning", "Åpne gulesider.no (personer)", "Åpne gulesider.no (bedrifter)")) { _, i ->
                when (i) {
                    0 -> lookupNow(r)
                    1 -> WebLookupActivity.open(act, r.item.number, WebLookupActivity.SITE_1881)
                    2 -> WebLookupActivity.open(act, r.item.number, WebLookupActivity.SITE_GS_PERSONS)
                    else -> WebLookupActivity.open(act, r.item.number, WebLookupActivity.SITE_GS_COMPANIES)
                }
            }
            .setNegativeButton("Avbryt", null)
            .show()
    }

    private fun lookupNow(r: Row) {
        Toast.makeText(act, "Slår opp ${Phone.pretty(r.item.number)} …", Toast.LENGTH_SHORT).show()
        NumberLookup.lookupAsync(act, r.item.number, manual = true) { res ->
            res?.let { Contacts.maybeAutoAdd(act, r.item.number, it) }
            act.runOnUiThread {
                if (res?.name != null) Toast.makeText(act, "Funnet: ${res.name} (${res.source})", Toast.LENGTH_LONG).show()
                else AlertDialog.Builder(act)
                    .setTitle(if (res == null) "Oppslag ikke mulig nå" else "Ingen treff")
                    .setMessage((if (res == null) "Kildene svarte ikke eller ba om en sikkerhetssjekk (se Logg › OPPSLAG)."
                        else "Ingen treff på ${res.source}.") + "\n\nÅpne søkesiden i nettvisning? Der kan du fullføre en sjekk og velge navnet selv.")
                    .setPositiveButton("Åpne nettvisning") { _, _ -> WebLookupActivity.open(act, r.item.number,
                        if (res == null) WebLookupActivity.SITE_GS_PERSONS else WebLookupActivity.SITE_1881) }
                    .setNegativeButton("Lukk", null)
                    .show()
                load()
            }
        }
    }

    private fun addContact(r: Row) {
        val lk = r.lookup ?: return
        val name = lk.name ?: return
        val number = r.item.number ?: return
        AlertDialog.Builder(act)
            .setTitle("Legg til i kontakter?")
            .setMessage("Ny kontakt «$name» med nummeret ${Phone.pretty(number)} (notat: Oppslag via LocationLogger).\n\nLagres i: ${Contacts.current(act).label} (endres under Innstillinger › Kontaktlager).\n\nEksisterende kontakter endres aldri.")
            .setPositiveButton("Legg til") { _, _ ->
                val err = Contacts.add(act, number, name, lk.source)
                Toast.makeText(act, err ?: "Lagt til i kontakter", Toast.LENGTH_LONG).show()
                if (err == null && act.checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) load()
                if (err != null && err.startsWith("Mangler")) askPermissions()
            }
            .setNegativeButton("Avbryt", null)
            .show()
    }

    private fun details(r: Row) {
        val it = r.item
        val msg = buildString {
            append(kindText(it)).append(" · ").append(when_(it.at))
            dur(it.durationS).takeIf { s -> s.isNotEmpty() }?.let { s -> append(" · ").append(s) }
            append(if (it.kind == CommItem.Kind.MMS && it.dir == CommItem.Dir.OUT) "\nTil: " else if (it.kind == CommItem.Kind.CALL) "\nNummer: " else "\nFra: ")
                .append(Phone.pretty(it.number))
            r.contact?.let { c -> append("\nKontakt: ").append(c) }
            r.lookup?.let { l -> append("\nOppslag (").append(l.source).append("): ").append(l.name ?: "ingen treff") }
            if (it.others.isNotEmpty()) {
                append(if (it.dir == CommItem.Dir.OUT) "\nOgså til:" else "\nAndre i gruppen:")
                it.others.forEach { n ->
                    append("\n  • ").append(Phone.pretty(n))
                    (Phone.contactName(act, n) ?: NumberLookup.cached(act, n)?.name)?.let { nm -> append(" – ").append(nm) }
                }
            }
            if (r.recs.isNotEmpty()) append("\nOpptak: ").append(r.recs.size).append(" (").append(r.recs.joinToString { x -> x.source }).append(")")
            it.subject?.let { sub -> append("\n\nEmne: ").append(sub) }
            if (!it.text.isNullOrBlank()) append("\n\n").append(it.text)
            if (it.parts.isNotEmpty()) append("\n\nVedlegg: ").append(Comms.attachmentSummary(it.parts)?.removePrefix("med ") ?: "")
        }
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(8)) }
        box.addView(TextView(act).apply { text = msg; textSize = 14f; setTextIsSelectable(true) })
        if (it.parts.isNotEmpty()) {
            val w = act.resources.displayMetrics.widthPixels - dp(100)
            it.images.forEach { part ->
                val iv = ImageView(act).apply {
                    adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
                    minimumHeight = dp(60); setBackgroundColor(0xFFF5F5F5.toInt())
                    setOnClickListener { MmsMedia.showImage(act, part, title(r)) }
                }
                Thread {
                    val b = MmsMedia.decode(act, part.uri, w)
                    act.runOnUiThread { if (b != null) { iv.setImageBitmap(b); iv.setBackgroundColor(0) } else iv.visibility = View.GONE }
                }.start()
                box.addView(iv)
            }
            val chips = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            it.parts.filter { p -> !p.isImage }.forEach { part -> chips.addView(small(part.label()) { MmsMedia.open(act, part) }) }
            if (chips.childCount > 0) box.addView(HorizontalScrollView(act).apply { addView(chips) })
        }
        val buttons = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        r.recs.forEachIndexed { i, rec -> buttons.addView(small(if (r.recs.size > 1) "▶ Opptak ${i + 1}" else "▶ Opptak") { Player.show(act, rec, title(r)) }) }
        if (r.contact == null && Phone.isNorwegian(Phone.key(it.number)))
            buttons.addView(small("🌐 Nettvisning") { WebLookupActivity.open(act, it.number) })
        if (buttons.childCount > 0) box.addView(buttons)
        AlertDialog.Builder(act).setTitle(title(r)).setView(ScrollView(act).apply { addView(box) }).setPositiveButton("OK", null).show()
    }
}
