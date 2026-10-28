package no.grokbot.stedslogger

import org.junit.Assert.assertEquals
import org.junit.Test

/** Dummy data only (this repo is public). */
class PlacesTest {
    private val addr = "Example Street 6, 0000 Town"

    @Test fun storedVisitIsRelabelledAtDisplayTime() {
        // A stop logged before the Place existed keeps the raw address as its stored name;
        // the label must come from the current list.
        val map = Places.parse("Cozy Hotel;$addr")
        assertEquals("Cozy Hotel", Places.label(map, addr))
    }

    @Test fun hiddenCharactersAndCase() {
        val map = Places.parse("\uFEFFCozy Hotel ; example\u00A0street 6,0000  TOWN\r\n")
        assertEquals("Cozy Hotel", Places.label(map, addr))
        assertEquals(1, Places.count("\uFEFFCozy Hotel ; example\u00A0street 6,0000  TOWN\r\n"))
    }

    @Test fun fullWidthSemicolonAndTab() {
        assertEquals("Cozy Hotel", Places.label(Places.parse("Cozy Hotel\uFF1B$addr"), addr))
        assertEquals("Cozy Hotel", Places.label(Places.parse("Cozy Hotel\t$addr"), addr))
    }

    @Test fun reversedOrder() {
        val map = Places.parse("$addr;Cozy Hotel")
        assertEquals("Cozy Hotel", Places.label(map, addr))
        assertEquals(listOf("Cozy Hotel"), Places.names("$addr;Cozy Hotel"))
    }

    @Test fun streetOnlyEitherSide() {
        assertEquals("Cozy Hotel", Places.label(Places.parse("Cozy Hotel;Example Street 6"), addr))
        assertEquals("Cozy Hotel", Places.label(Places.parse("Cozy Hotel;$addr"), "Example Street 6"))
        assertEquals("Example Street 6, 1111 Other", Places.label(Places.parse("Cozy Hotel;$addr"), "Example Street 6, 1111 Other"))
    }

    @Test fun unknownAddressUnchanged() {
        assertEquals("Other Road 1, 0000 Town", Places.label(Places.parse("Cozy Hotel;$addr"), "Other Road 1, 0000 Town"))
    }

    @Test fun nameItselfResolvesAndImageKeyStable() {
        val map = Places.parse("Cozy Hotel;$addr")
        assertEquals("Cozy Hotel", Places.label(map, "cozy  hotel"))
        assertEquals("cozy hotel", Places.imageKey(" Cozy   Hotel "))
        assertEquals("Cozy Hotel", Places.placeName(map, "Cozy Hotel"))
    }

    @Test fun samePlaceAcrossOldAndNewNames() {
        val map = Places.parse("Cozy Hotel;$addr")
        assertEquals(true, Places.same(map, addr, "Cozy Hotel"))
    }
}
