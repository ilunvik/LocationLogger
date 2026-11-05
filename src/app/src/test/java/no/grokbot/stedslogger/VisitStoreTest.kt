package no.grokbot.stedslogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Dummy data only (this repo is public). */
class VisitStoreTest {

    private val addr1 = "Example Street 6, 0000 Town"
    private val addr2 = "Example Lane 2, 0000 Town"
    private val addr3 = "Other Road 1, 0000 Town"

    private fun visit(name: String, arrival: Long, departure: Long?, travelledM: Double? = null) =
        Visit(name = name, arrival = arrival, departure = departure, lat = null, lon = null, travelledM = travelledM)

    // ---- mergeConsecutive -------------------------------------------------------------------

    @Test fun mergeConsecutive_noVisits() {
        assertEquals(emptyList<Visit>(), VisitStore.mergeConsecutive(emptyList(), emptyMap()))
    }

    @Test fun mergeConsecutive_singleVisit() {
        val v = visit(addr1, 1000L, 2000L)
        val result = VisitStore.mergeConsecutive(listOf(v), emptyMap())
        assertEquals(1, result.size)
        assertEquals(v, result[0])
    }

    @Test fun mergeConsecutive_twoDifferentPlaces() {
        val map = Places.parse("Cozy Hotel;$addr1\nOther Place;$addr3")
        val visits = listOf(visit(addr1, 1000L, 2000L), visit(addr3, 3000L, 4000L))
        val result = VisitStore.mergeConsecutive(visits, map)
        assertEquals(2, result.size)
    }

    /** Three consecutive stops that all resolve to the same place via newly added aliases. */
    @Test fun mergeConsecutive_threeStopsSamePlaceViaAliases() {
        // addr1 and addr2 are both aliases for "Cozy Hotel" (the user added addr2 later).
        // addr1, addr2, addr1 in a row → one merged visit.
        val map = Places.parse("Cozy Hotel;$addr1;$addr2")
        val visits = listOf(
            visit(addr1, 1_000L, 2_000L, travelledM = 500.0),
            visit(addr2, 2_300L, 3_000L, travelledM = 1.5),   // GPS jitter
            visit(addr1, 3_600L, 4_000L, travelledM = 2.0),   // GPS jitter
        )
        val result = VisitStore.mergeConsecutive(visits, map)
        assertEquals(1, result.size)
        assertEquals(1_000L, result[0].arrival)
        assertEquals(4_000L, result[0].departure)
        assertEquals(addr1, result[0].name)   // first sub-visit's stored name is kept
    }

    /** The first sub-visit's travelledM (actual distance from previous stop) is preserved;
     *  the GPS-jitter distances of intermediate sub-visits are dropped. */
    @Test fun mergeConsecutive_keepsTravelledMFromFirstSubVisit() {
        val map = Places.parse("Cozy Hotel;$addr1;$addr2")
        val visits = listOf(
            visit(addr1, 1_000L, 2_000L, travelledM = 1800.0),
            visit(addr2, 2_003L, 3_000L, travelledM = 1.0),
        )
        val result = VisitStore.mergeConsecutive(visits, map)
        assertEquals(1, result.size)
        assertEquals(1800.0, result[0].travelledM)
    }

    /** A different place between two same-place visits prevents merging across the gap. */
    @Test fun mergeConsecutive_noMergeAcrossDifferentPlace() {
        val map = Places.parse("Cozy Hotel;$addr1\nOther Place;$addr3")
        val visits = listOf(
            visit(addr1, 1_000L, 2_000L),
            visit(addr3, 2_500L, 3_000L),   // genuinely different stop
            visit(addr1, 4_000L, 5_000L),   // back at the same place, NOT merged
        )
        val result = VisitStore.mergeConsecutive(visits, map)
        assertEquals(3, result.size)
    }

    /** Consecutive visits whose stored names already resolve to the same place are merged. */
    @Test fun mergeConsecutive_resolvedNameMatch() {
        // addr1 and "Cozy Hotel" (the place name itself) both resolve to "Cozy Hotel"
        val map = Places.parse("Cozy Hotel;$addr1")
        val visits = listOf(
            visit(addr1, 1_000L, 2_000L),
            visit("Cozy Hotel", 2_100L, 3_000L),
        )
        val result = VisitStore.mergeConsecutive(visits, map)
        assertEquals(1, result.size)
        assertEquals(1_000L, result[0].arrival)
        assertEquals(3_000L, result[0].departure)
    }

    /** Departure of null in the last (current) sub-visit propagates correctly. */
    @Test fun mergeConsecutive_nullDepartureInLastSubVisit() {
        val map = Places.parse("Cozy Hotel;$addr1;$addr2")
        val visits = listOf(
            visit(addr1, 1_000L, 2_000L),
            visit(addr2, 2_010L, null),   // current ongoing visit, no departure yet
        )
        val result = VisitStore.mergeConsecutive(visits, map)
        assertEquals(1, result.size)
        assertEquals(1_000L, result[0].arrival)
        assertNull(result[0].departure)
    }

    /** Departure of null in an earlier sub-visit: the later non-null departure is taken. */
    @Test fun mergeConsecutive_nullDepartureInFirstSubVisit() {
        val map = Places.parse("Cozy Hotel;$addr1;$addr2")
        val visits = listOf(
            visit(addr1, 1_000L, null),
            visit(addr2, 2_010L, 3_000L),
        )
        val result = VisitStore.mergeConsecutive(visits, map)
        assertEquals(1, result.size)
        assertEquals(1_000L, result[0].arrival)
        assertEquals(3_000L, result[0].departure)
    }

    /** Four visits, middle two are the same place: they merge, outer two are different. */
    @Test fun mergeConsecutive_middlePairMerged() {
        val map = Places.parse("Cozy Hotel;$addr1;$addr2")
        val visits = listOf(
            visit(addr3, 1_000L, 2_000L),
            visit(addr1, 2_500L, 3_000L),
            visit(addr2, 3_020L, 4_000L),
            visit(addr3, 5_000L, 6_000L),
        )
        val result = VisitStore.mergeConsecutive(visits, map)
        assertEquals(3, result.size)
        assertEquals(addr3, result[0].name)
        assertEquals(addr1, result[1].name)
        assertEquals(2_500L, result[1].arrival)
        assertEquals(4_000L, result[1].departure)
        assertEquals(addr3, result[2].name)
    }

    /** Without a place list, two visits with the same exact address are merged. */
    @Test fun mergeConsecutive_sameRawAddressWithEmptyMap() {
        val visits = listOf(
            visit(addr1, 1_000L, 2_000L),
            visit(addr1, 2_300L, 3_000L),
        )
        val result = VisitStore.mergeConsecutive(visits, emptyMap())
        assertEquals(1, result.size)
        assertEquals(1_000L, result[0].arrival)
        assertEquals(3_000L, result[0].departure)
    }
}
