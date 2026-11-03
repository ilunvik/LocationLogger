package no.grokbot.stedslogger

import android.graphics.Bitmap
import android.os.Looper
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.49: renders every tab to build/screenshots at phone and unfolded-Fold6 sizes, light and dark, to check
 * the layout. Synthetic data only (made-up places, names and numbers; no coordinates). Skipped unless the
 * environment variable LL_SHOTS is set: `LL_SHOTS=1 ./gradlew testDebugUnitTest --tests '*UiShots*'`.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class UiShots {
    @Before fun only() = assumeTrue(System.getenv("LL_SHOTS") != null)

    private val now = System.currentTimeMillis()
    private val min = 60_000L

    private fun seed() {
        val ctx = RuntimeEnvironment.getApplication()
        fun v(name: String, arr: Long, dep: Long?, km: Double?) = org.json.JSONObject().apply {
            put("name", name); put("arrival", arr); dep?.let { put("departure", it) }; km?.let { put("travelled_m", it * 1000) }
        }
        val d = 24 * 60 * min
        val arr = org.json.JSONArray(listOf(
            v("Hjem", now - d - 14 * 60 * min, now - d - 13 * 60 * min + 5 * min, null),
            v("Kontoret", now - d - 12 * 60 * min, now - d - 4 * 60 * min, 18.3),
            v("Matbutikken", now - d - 3 * 60 * min - 30 * min, now - d - 3 * 60 * min, 4.1),
            v("Hjem", now - d - 2 * 60 * min - 40 * min, now - 9 * 60 * min, 3.9),
            v("Eksempelveien 12, 0000 Sted", now - 8 * 60 * min - 20 * min, now - 7 * 60 * min, 12.6),
            v("Treningssenteret", now - 6 * 60 * min - 30 * min, now - 5 * 60 * min - 15 * min, 2.2),
            v("Hjem", now - 4 * 60 * min - 50 * min, now - 2 * min, 6.0),
        ))
        File(ctx.filesDir, "visits.json").writeText(arr.toString())
        LogStore.prefs(ctx).edit().putBoolean(LogStore.KEY_RUNNING, true)
            .putString(LogStore.KEY_LAST_CHECK, "kl. 21:14 – Hjem (nettverk, 18 m)").putLong(LogStore.KEY_LAST_CHECK_AT, now - 2 * min)
            .putString(LogStore.KEY_LAST_RAW_ADDRESS, "Eksempelveien 1, 0000 Sted").apply()
        Places.save(ctx, "Hjem;Eksempelveien 1, 0000 Sted\nKontoret;Testgata 5, 0000 Sted")
        val visits = VisitStore.all(ctx)
        Comments.set(ctx, visits[1], "Planleggingsmøte med teamet, tok med kaffe")
        listOf(
            EventLog.CHECK to "Sjekk startet (intervall 5 min)",
            EventLog.FIX to "Posisjon: nettverk, nøyaktighet 18 m, alder 4 s",
            EventLog.VISIT to "Fortsatt på Hjem",
            EventLog.NET to "Nettverk tilgjengelig (Wi-Fi)",
            EventLog.ADB to "Rapporterte adresse til dashbordet",
            EventLog.CALL to "Innkommende samtale",
            EventLog.COMMENT to "Kommentar lagret (stans)",
            EventLog.ERROR to "GPS-bekreftelse feilet – ignorerer oppslaget (bruker ikke gjenbrukt posisjon)",
        ).forEach { (t, m) -> EventLog.add(ctx, t, m) }
    }

    private fun idle(act: MainActivity) {
        repeat(6) { Thread.sleep(150); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
    }

    private fun comms(act: MainActivity) {
        val tab = MainActivity::class.java.getDeclaredField("commsTab").apply { isAccessible = true }.get(act)
        val rowC = Class.forName("no.grokbot.stedslogger.CommsTab\$Row").declaredConstructors.first().apply { isAccessible = true }
        val h = 60 * min
        val items = listOf(
            CommItem(CommItem.Kind.CALL, CommItem.Dir.IN, now - 20 * min, "+4790000000", null, 312, rowId = 1) to "Kari Nordmann",
            CommItem(CommItem.Kind.SMS, CommItem.Dir.IN, now - h, "+4790000001", null, text = "Hei! Rekker du middag kl. 18?", rowId = 2) to "Ola Nordmann",
            CommItem(CommItem.Kind.CALL, CommItem.Dir.MISSED, now - 3 * h, "+4740000002", null, rowId = 3) to null,
            CommItem(CommItem.Kind.CALL, CommItem.Dir.OUT, now - 5 * h, "+4790000003", null, 75, rowId = 4) to "Eksempel AS",
            CommItem(CommItem.Kind.SMS, CommItem.Dir.OUT, now - 26 * h, "+4790000001", null, text = "Takk for i dag!", rowId = 5) to "Ola Nordmann",
            CommItem(CommItem.Kind.CALL, CommItem.Dir.IN, now - 28 * h, "+4790000004", null, 1250, rowId = 6) to "Legekontoret",
        )
        Comments.set(act, items[0].first, "Avtalte tid neste uke")
        val rows = items.map { (it, name) -> rowC.newInstance(it, name, null, emptyList<Recordings.Rec>()) }
        CommsTab::class.java.getDeclaredField("rows").apply { isAccessible = true }.set(tab, rows)
        CommsTab::class.java.getDeclaredMethod("applyFilter").apply { isAccessible = true }.invoke(tab)
    }

    private fun shot(name: String, tab: Int) {
        seed()
        LogStore.prefs(RuntimeEnvironment.getApplication()).edit().putInt("tab", tab).apply()
        val ctl = Robolectric.buildActivity(MainActivity::class.java,
            android.content.Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab)).setup()
        val act = ctl.get()
        idle(act)
        if (tab == 3) { comms(act); idle(act) }
        val v = act.window.decorView
        // lay out again at the window size (text and auto-size changed after the last traversal)
        repeat(2) {
            v.measure(android.view.View.MeasureSpec.makeMeasureSpec(v.width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(v.height, android.view.View.MeasureSpec.EXACTLY))
            v.layout(0, 0, v.width, v.height)
        }
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // Phone portrait (folded Fold6 / Note9 width), dark
    @Test @Config(qualifiers = "w392dp-h850dp-night-xxhdpi") fun phoneVisitsDark() = shot("phone_dark_1_besok", 0)
    @Test @Config(qualifiers = "w392dp-h850dp-night-xxhdpi") fun phoneCommsDark() = shot("phone_dark_2_samtaler", 3)
    @Test @Config(qualifiers = "w392dp-h850dp-night-xxhdpi") fun phoneWebDark() = shot("phone_dark_3_oppslag", 4)
    @Test @Config(qualifiers = "w392dp-h850dp-night-xxhdpi") fun phoneSettingsDark() = shot("phone_dark_4_innstillinger", 1)
    @Test @Config(qualifiers = "w392dp-h850dp-night-xxhdpi") fun phoneLogDark() = shot("phone_dark_5_logg", 2)
    // Phone portrait, light
    @Test @Config(qualifiers = "w392dp-h850dp-notnight-xxhdpi") fun phoneVisitsLight() = shot("phone_light_1_besok", 0)
    @Test @Config(qualifiers = "w392dp-h850dp-notnight-xxhdpi") fun phoneCommsLight() = shot("phone_light_2_samtaler", 3)
    @Test @Config(qualifiers = "w392dp-h850dp-notnight-xxhdpi") fun phoneSettingsLight() = shot("phone_light_4_innstillinger", 1)
    // Fold6 unfolded
    @Test @Config(qualifiers = "w707dp-h823dp-night-xhdpi") fun unfoldedVisits() = shot("unfolded_dark_1_besok", 0)
    @Test @Config(qualifiers = "w707dp-h823dp-night-xhdpi") fun unfoldedComms() = shot("unfolded_dark_2_samtaler", 3)
    @Test @Config(qualifiers = "w707dp-h823dp-night-xhdpi") fun unfoldedLog() = shot("unfolded_dark_5_logg", 2)
}
