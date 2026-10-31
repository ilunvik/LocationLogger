package no.grokbot.stedslogger

import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.ContactsContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Adds a looked-up name as a new phone contact (1.32). Never changes or overwrites an existing contact. */
object Contacts {
    /** A place new contacts can be saved (1.35). type/name null = the phone's plain local storage. */
    data class Store(val type: String?, val name: String?, val label: String) {
        val id get() = "${type ?: ""}\u0001${name ?: ""}"
    }

    private const val KEY_STORE_TYPE = "contact_store_type"
    private const val KEY_STORE_NAME = "contact_store_name"
    private const val KEY_STORE_SET = "contact_store_set"
    const val TYPE_GOOGLE = "com.google"
    const val TYPE_SAMSUNG_LOCAL = "vnd.sec.contact.phone"
    private val SIM_TYPES = setOf("vnd.sec.contact.sim", "vnd.sec.contact.sim2", "com.android.sim", "com.android.contacts.sim")

    /** Chat apps that sync contacts both ways but are no place to keep an address book entry. */
    private val MESSENGERS = listOf("telegram", "whatsapp", "viber", "signal", "skype", "facebook", "tachyon", "threema", "wire")

    private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** "Telefon (lokal)": Samsung's own phone storage when the phone has it, else no account. */
    private fun localStore(ctx: Context): Store {
        val samsung = Build.MANUFACTURER.equals("samsung", true) || rawAccountTypes(ctx).contains(TYPE_SAMSUNG_LOCAL)
        return if (samsung) Store(TYPE_SAMSUNG_LOCAL, TYPE_SAMSUNG_LOCAL, "Telefon (lokal)") else Store(null, null, "Telefon (lokal)")
    }

    /** Account types already used by contacts on the phone (needs READ_CONTACTS). */
    @Volatile private var rawTypes: Pair<Long, Set<String>>? = null

    private fun rawAccountTypes(ctx: Context): Set<String> {
        rawTypes?.let { (t, v) -> if (System.currentTimeMillis() - t < 10 * 60_000L && v.isNotEmpty()) return v }
        return queryRawAccountTypes(ctx).also { rawTypes = System.currentTimeMillis() to it }
    }

    private fun queryRawAccountTypes(ctx: Context): Set<String> = try {
        if (!granted(ctx, android.Manifest.permission.READ_CONTACTS)) emptySet()
        else ctx.contentResolver.query(ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts.ACCOUNT_TYPE),
            "${ContactsContract.RawContacts.DELETED}=0", null, null)?.use { c ->
            val out = HashSet<String>(); while (c.moveToNext()) c.getString(0)?.let { out += it }; out
        } ?: emptySet()
    } catch (_: Exception) { emptySet() }

    /** Readable Norwegian label: "Google – name", "Samsung-konto – name", "<App> – name". */
    fun label(ctx: Context, type: String?, name: String?): String {
        if (type == null || type == TYPE_SAMSUNG_LOCAL) return "Telefon (lokal)"
        val kind = when {
            type == TYPE_GOOGLE -> "Google"
            type == "com.osp.app.signin" -> "Samsung-konto"
            type.contains("exchange", true) || type.contains("outlook", true) || type.contains("microsoft", true) -> "Microsoft/Exchange"
            else -> try {
                AccountManager.get(ctx).authenticatorTypes.firstOrNull { it.type == type }?.let { a ->
                    ctx.packageManager.getText(a.packageName, a.labelId, null)?.toString()
                }
            } catch (_: Exception) { null } ?: type
        }
        return if (name.isNullOrBlank()) kind else "$kind – $name"
    }

    /**
     * Writable contact stores: accounts whose contacts sync adapter supports uploading (Google, Samsung,
     * Exchange …), plus the phone's local storage. SIM cards are left out. Accounts are visible to the app
     * through READ_CONTACTS (Android 8+), so ask for that first.
     */
    fun stores(ctx: Context): List<Store> {
        val out = ArrayList<Store>()
        try {
            val types = ContentResolver.getSyncAdapterTypes()
                .filter { it.authority == ContactsContract.AUTHORITY && it.supportsUploading() && it.accountType !in SIM_TYPES &&
                    MESSENGERS.none { m -> it.accountType.contains(m, true) } }
                .map { it.accountType }.toSet()
            val am = AccountManager.get(ctx)
            for (a in am.accounts.sortedWith(compareBy({ it.type != TYPE_GOOGLE }, { it.type }, { it.name }))) {
                if (a.type in types && a.type != TYPE_SAMSUNG_LOCAL) out += Store(a.type, a.name, label(ctx, a.type, a.name))
            }
        } catch (e: Exception) { EventLog.error(ctx, "Kunne ikke lese kontoer", e) }
        out += localStore(ctx)
        return out.distinctBy { it.id }
    }

    /** System-like default: the first Google account, else the phone's local storage. */
    fun defaultStore(ctx: Context): Store = stores(ctx).firstOrNull { it.type == TYPE_GOOGLE } ?: localStore(ctx)

    fun chosenSet(ctx: Context) = LogStore.prefs(ctx).getBoolean(KEY_STORE_SET, false)

    fun choose(ctx: Context, s: Store) {
        LogStore.prefs(ctx).edit().putBoolean(KEY_STORE_SET, true)
            .putString(KEY_STORE_TYPE, s.type).putString(KEY_STORE_NAME, s.name).apply()
        EventLog.add(ctx, EventLog.LOOKUP, "Kontaktlager valgt: ${kindOnly(s)}")
    }

    /**
     * The store new contacts go to: the user's choice when it still exists, otherwise the default (and the
     * missing choice is logged once per use).
     */
    fun current(ctx: Context, log: Boolean = false): Store {
        if (!chosenSet(ctx)) return defaultStore(ctx)
        val p = LogStore.prefs(ctx)
        val type = p.getString(KEY_STORE_TYPE, null); val name = p.getString(KEY_STORE_NAME, null)
        if (type == null || type == TYPE_SAMSUNG_LOCAL) return localStore(ctx)
        val all = stores(ctx)
        all.firstOrNull { it.type == type && it.name == name }?.let { return it }
        val fb = all.firstOrNull { it.type == TYPE_GOOGLE } ?: localStore(ctx)
        if (log) EventLog.add(ctx, EventLog.LOOKUP, "Valgt kontaktlager (${label(ctx, type, name)}) finnes ikke lenger – bruker ${fb.label}")
        return fb
    }

    /** Returns null on success, else a Norwegian error text. [store] defaults to [current]. */
    fun add(ctx: Context, raw: String, name: String, source: String, store: Store? = null): String? {
        if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return "Mangler tilgang til å skrive kontakter"
        Phone.clearCache()
        Phone.contactName(ctx, raw)?.let { return "Nummeret finnes allerede i kontakter ($it) – ikke endret" }
        val st = store ?: current(ctx, log = true)
        val ops = ArrayList<ContentProviderOperation>()
        ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, st.type)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, st.name)
            .build()
        ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .build()
        val k = Phone.key(raw)
        val number = if (Phone.isNorwegian(k)) "+47$k" else raw.trim()
        ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                if (k.startsWith("4") || k.startsWith("9")) ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                else ContactsContract.CommonDataKinds.Phone.TYPE_OTHER)
            .build()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        ops += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Note.NOTE, "Oppslag via LocationLogger ($source, $day)")
            .build()
        return try {
            ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            Phone.clearCache()
            EventLog.add(ctx, EventLog.LOOKUP, "Lagt til i kontakter (${kindOnly(st)}): $name (${Phone.masked(raw)})")
            null
        } catch (e: Exception) {
            if (st.type != null && st.type != TYPE_SAMSUNG_LOCAL && store == null) {
                // The account refused the insert (removed, no contacts sync …): save locally instead
                EventLog.add(ctx, EventLog.LOOKUP, "Kontaktlager ${kindOnly(st)} avviste ny kontakt (${e.javaClass.simpleName}) – lagrer på telefonen")
                return add(ctx, raw, name, source, localStore(ctx))
            }
            EventLog.error(ctx, "Kunne ikke legge til kontakt", e)
            "Kunne ikke legge til kontakt: ${e.javaClass.simpleName}"
        }
    }

    /** Account kind without the account name, for the log ("Google", "Telefon (lokal)"). */
    private fun kindOnly(s: Store) = s.label.substringBefore(" – ")

    /** Auto-add after a lookup when the setting is on (silently skips existing contacts). */
    fun maybeAutoAdd(ctx: Context, raw: String?, r: NumberLookup.Result?) {
        if (raw == null || r?.name == null || !NumberLookup.autoContact(ctx)) return
        if (Phone.contactName(ctx, raw) != null) return
        add(ctx, raw, r.name, r.source)
    }
}
