package no.grokbot.stedslogger

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Adds a looked-up name as a new phone contact (1.32). Never changes or overwrites an existing contact. */
object Contacts {
    /** Returns null on success, else a Norwegian error text. */
    fun add(ctx: Context, raw: String, name: String, source: String): String? {
        if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return "Mangler tilgang til å skrive kontakter"
        Phone.clearCache()
        Phone.contactName(ctx, raw)?.let { return "Nummeret finnes allerede i kontakter ($it) – ikke endret" }
        val ops = ArrayList<ContentProviderOperation>()
        ops += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
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
            EventLog.add(ctx, EventLog.LOOKUP, "Lagt til i kontakter: $name (${Phone.masked(raw)})")
            null
        } catch (e: Exception) {
            EventLog.error(ctx, "Kunne ikke legge til kontakt", e)
            "Kunne ikke legge til kontakt: ${e.javaClass.simpleName}"
        }
    }

    /** Auto-add after a lookup when the setting is on (silently skips existing contacts). */
    fun maybeAutoAdd(ctx: Context, raw: String?, r: NumberLookup.Result?) {
        if (raw == null || r?.name == null || !NumberLookup.autoContact(ctx)) return
        if (Phone.contactName(ctx, raw) != null) return
        add(ctx, raw, r.name, r.source)
    }
}
