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
    private val COMMENT_COLOR = 0xFF6A1B9A.toInt()
    private fun dp(v: Int) = (v * d).toInt()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.ROOT)
    private val dayFmt = SimpleDateFormat("d.M.yyyy HH:mm", Locale.ROOT)
    private val dayKey = SimpleDateFormat("yyyyMMdd", Locale.ROOT)

    init {
        list.adapter = adapter()
        act.findViewById<SlimScrollHandle>(R.id.commScroll).attach(list)
        // folding/unfolding changes the width without recreating the activity: rebuild rows for iconsPerLine()
        list.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol && or - ol > 0) list.post { (list.adapter as BaseAdapter).notifyDataSetChanged() }
        }
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

    /** Compact square icon button (44 dp touch target) for the row's action column; [badge] = small number
     *  in the corner (several recordings). The label is the content description and long-press tooltip. */
    private fun iconBtn(icon: Int, tint: Int, label: String, badge: Int? = null, onClick: () -> Unit): View {
        val size = dp(44)
        val frame = android.widget.FrameLayout(act).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            contentDescription = label
            tooltipText = label
            isClickable = true
            isFocusable = false
            val tv = android.util.TypedValue()
            act.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            if (tv.resourceId != 0) setBackgroundResource(tv.resourceId)
            setOnClickListener { onClick() }
        }
        frame.addView(ImageView(act).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(tint)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(28), dp(28), android.view.Gravity.CENTER)
        })
        if (badge != null) frame.addView(TextView(act).apply {
            text = badge.toString(); textSize = 10f; setTypeface(typeface, Typeface.BOLD); setTextColor(tint)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = android.widget.FrameLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM or android.view.Gravity.END).apply {
                marginEnd = dp(5); bottomMargin = dp(3)
            }
        })
        return frame
    }

    /** Icons per line in a row (1.44): as many 46 dp icons as fit in 40 % of the list width, 2–5
     *  (3 on the folded Fold6 and the Note9, 5 unfolded); more icons wrap to another line. */
    private fun iconsPerLine(): Int {
        val w = if (list.width > 0) list.width / d else act.resources.configuration.screenWidthDp.toFloat()
        return (w * 0.4f / 46f).toInt().coerceIn(2, 5)
    }

    /** Row excerpt of a comment: first line, at most ~60 chars cut at a word boundary, "…" when shortened. */
    private fun excerpt(c: String, max: Int = 60): String {
        val first = c.trim().lineSequence().first().trim()
        val more = first.length < c.trim().length
        if (first.length <= max) return if (more) "$first …" else first
        val cut = first.lastIndexOf(' ', max).takeIf { i -> i >= max / 2 } ?: max
        return first.substring(0, cut).trimEnd() + "…"
    }

    private fun editComment(r: Row) {
        val it = r.item
        val old = Comments.get(act, it)
        val et = android.widget.EditText(act).apply {
            setText(old.orEmpty()); setSelection(text.length)
            hint = "Kommentar (lagres bare på telefonen)"
            minLines = 3; maxLines = 8; gravity = android.view.Gravity.TOP or android.view.Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val box = android.widget.FrameLayout(act).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        val what = when (it.kind) { CommItem.Kind.CALL -> "samtale"; CommItem.Kind.SMS -> "SMS"; CommItem.Kind.MMS -> "MMS" }
        val b = AlertDialog.Builder(act)
            .setTitle(if (old != null) "Rediger kommentar" else "Kommenter")
            .setMessage("${title(r)} · ${when_(it.at)}")
            .setView(box)
            .setPositiveButton("Lagre") { _, _ ->
                val t = et.text.toString()
                if (Comments.set(act, it, t)) {
                    EventLog.add(act, EventLog.COMMENT, if (t.isBlank()) "Kommentar slettet ($what)" else if (old != null) "Kommentar endret ($what)" else "Kommentar lagret ($what)")
                    (list.adapter as BaseAdapter).notifyDataSetChanged()
                }
            }
            .setNegativeButton("Avbryt", null)
        if (old != null) b.setNeutralButton("Slett") { _, _ ->
            AlertDialog.Builder(act).setMessage("Slette kommentaren?")
                .setPositiveButton("Slett") { _, _ ->
                    if (Comments.set(act, it, null)) {
                        EventLog.add(act, EventLog.COMMENT, "Kommentar slettet ($what)")
                        (list.adapter as BaseAdapter).notifyDataSetChanged()
                    }
                }
                .setNegativeButton("Avbryt", null).show()
        }
        val dlg = b.create()
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        et.requestFocus()
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
            // 1.43: name (bold) and meta (number · kind · time · duration, small grey) on one line; the meta
            // wraps after the name only when it does not fit, and only at the " · " separators
            val named = r.contact != null || r.lookup?.name != null || it.cachedName != null
            val meta = listOfNotNull(if (named) Phone.pretty(it.number) else null, kindText(it), when_(it.at),
                dur(it.durationS).ifEmpty { null }).joinToString(" · ") { p -> p.replace(' ', '\u00A0') }
            val comment = Comments.get(act, it)
            val name = title(r)
            val head = android.text.SpannableStringBuilder(name).apply {
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, name.length, 0)
                if (it.dir == CommItem.Dir.MISSED || it.dir == CommItem.Dir.REJECTED)
                    setSpan(android.text.style.ForegroundColorSpan(0xFFC62828.toInt()), 0, name.length, 0)
                append("   ")
                val s0 = length
                append(meta)
                setSpan(android.text.style.RelativeSizeSpan(12.5f / 15f), s0, length, 0)  // 12.5 sp
                setSpan(android.text.style.ForegroundColorSpan(0xFF616161.toInt()), s0, length, 0)
                // 1.45: comment excerpt after the details (last, so the 3-line ellipsis cuts the comment, not the date)
                if (comment != null) {
                    append("   ")
                    val c0 = length
                    append("💬\u00A0").append(excerpt(comment))
                    setSpan(android.text.style.RelativeSizeSpan(12.5f / 15f), c0, length, 0)
                    setSpan(android.text.style.StyleSpan(Typeface.ITALIC), c0, length, 0)
                    setSpan(android.text.style.ForegroundColorSpan(COMMENT_COLOR), c0, length, 0)
                }
            }
            col.addView(TextView(act).apply {
                text = head; textSize = 15f; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
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
            // Icon buttons in a narrow column to the right of the text, so the text keeps the width
            // Icon buttons side by side to the right of the text (1.42), vertically centred; at most
            // iconsPerLine() per line, extra icons wrap to a second line so the text keeps its width
            val icons = ArrayList<View>()
            r.recs.forEachIndexed { i, rec ->
                val n = if (r.recs.size > 1) i + 1 else null
                icons.add(iconBtn(R.drawable.ic_row_play, 0xFF2E7D32.toInt(),
                    if (n != null) "Spill av opptak $n" else "Spill av opptak", n) { Player.show(act, rec, title(r)) })
            }
            val k = Phone.key(it.number)
            if (r.contact == null && Phone.isNorwegian(k)) {
                val lk = r.lookup
                if (lk?.name != null) icons.add(iconBtn(R.drawable.ic_row_person_add, 0xFF1565C0.toInt(), "Legg til i kontakter") { addContact(r) })
                else if (lk == null) icons.add(iconBtn(R.drawable.ic_row_search, 0xFF1565C0.toInt(), "Slå opp nummer") { lookupMenu(r) })
                else icons.add(iconBtn(R.drawable.ic_row_globe, 0xFF1565C0.toInt(), "Åpne i nettvisning") { LookupTab.open(act, it.number) })
            }
            icons.add(if (comment != null) iconBtn(R.drawable.ic_row_comment_filled, COMMENT_COLOR, "Rediger kommentar") { editComment(r) }
                else iconBtn(R.drawable.ic_row_comment, 0xFF757575.toInt(), "Kommenter") { editComment(r) })
            if (icons.isNotEmpty()) {
                val buttons = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        gravity = android.view.Gravity.CENTER_VERTICAL; marginStart = dp(2)
                        // keep clear of the 20 dp SlimScrollHandle on the right edge (+4 dp row padding = 28 dp gap)
                        marginEnd = dp(24)
                    }
                }
                icons.chunked(iconsPerLine()).forEach { chunk ->
                    val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.END }
                    chunk.forEachIndexed { i, v ->
                        (v.layoutParams as LinearLayout.LayoutParams).marginStart = if (i > 0) dp(2) else 0
                        line.addView(v)
                    }
                    buttons.addView(line)
                }
                row.addView(buttons)
            }
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
                    1 -> LookupTab.open(act, r.item.number, LookupTab.SITE_1881)
                    2 -> LookupTab.open(act, r.item.number, LookupTab.SITE_GS_PERSONS)
                    else -> LookupTab.open(act, r.item.number, LookupTab.SITE_GS_COMPANIES)
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
                    .setPositiveButton("Åpne nettvisning") { _, _ -> LookupTab.open(act, r.item.number,
                        if (res == null) LookupTab.SITE_GS_PERSONS else LookupTab.SITE_1881) }
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

    private var dlgRef: AlertDialog? = null

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
        Comments.get(act, it)?.let { c -> box.addView(TextView(act).apply {
            text = "💬 $c"; textSize = 14f; setTypeface(typeface, Typeface.ITALIC); setTextColor(COMMENT_COLOR)
            setTextIsSelectable(true); setPadding(0, dp(8), 0, 0)
        }) }
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
            buttons.addView(small("🌐 Nettvisning") { LookupTab.open(act, it.number) })
        val hasComment = Comments.get(act, it) != null
        buttons.addView(small(if (hasComment) "💬 Rediger kommentar" else "💬 Kommenter") { dlgRef?.dismiss(); editComment(r) })
        if (buttons.childCount > 0) box.addView(HorizontalScrollView(act).apply { addView(buttons) })
        val dlg = AlertDialog.Builder(act).setTitle(title(r)).setView(ScrollView(act).apply { addView(box) }).setPositiveButton("OK", null).create()
        dlgRef = dlg
        dlg.show()
    }
}
