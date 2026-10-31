package no.grokbot.stedslogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LookupTest {
    private fun box(name: String, tel: String) =
        "<div class=\"box listing listing--business  \"><h2 class=\"listing-name\">\n <a href=\"/x\">$name</a>\n</h2>" +
            "<a href=\"tel:$tel\">tel</a></div>"

    @Test fun keys() {
        assertEquals("21000000", Phone.key("+47 21 00 00 00"))
        assertEquals("21000000", Phone.key("004721000000"))
        assertEquals("40000000", Phone.key("400 00 000"))
        assertEquals("+4612345678", Phone.key("+46 12 34 56 78"))
        assertEquals("", Phone.key(null))
        assertEquals("400 00 000", Phone.pretty("+4740000000"))
        assertEquals("21 00 00 00", Phone.pretty("21000000"))
        assertEquals("Eksempel", Phone.pretty("Eksempel"))
    }

    @Test fun parse1881PicksExactNumber() {
        val html = "<html>" + box("Annen Bedrift AS", "004722222222") + box("Eksempel &amp; S&#248;nn AS", "004721000000") + "</html>"
        assertEquals("Eksempel & Sønn AS", NumberLookup.parse1881(html, "21000000"))
        assertNull(NumberLookup.parse1881("<h1>Søk etter 21000000 ga dessverre ingen treff</h1>", "21000000"))
        assertNull(NumberLookup.parse1881("<html>" + box("A", "004722222222") + box("B", "004733333333") + "</html>", "21000000"))
    }

    @Test fun parseGulesider() {
        val html = "{\"@type\":\"Organization\",\"name\":\"Eksempel AS\",\"telephone\":\"+47 21 00 00 00\"}"
        assertEquals("Eksempel AS", NumberLookup.parseGulesider(html, "21000000"))
        assertNull(NumberLookup.parseGulesider(html, "22000000"))
    }

    @Test fun parse1881DetailPage() {
        val html = "<html><h1 class=\"details-name\">Testfirma AS</h1><a href=\"tel:004721000000\">21 00 00 00</a></html>"
        assertEquals("Testfirma AS", NumberLookup.parse1881(html, "21000000"))
        assertEquals(null, NumberLookup.parse1881(html, "22222222"))
    }
}
