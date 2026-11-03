package no.grokbot.stedslogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CommentsTest {
    private fun v(at: Long, lat: Double?, lon: Double?) = Visit("Test", at, null, lat, lon)

    @Test fun visitKeyStableWithinRounding() {
        // Dummy coordinates; differences below ~50 m round to the same key
        assertEquals(Comments.key(v(1000L, 10.00001, 20.00001)), Comments.key(v(1000L, 10.00002, 20.00004)))
    }

    @Test fun visitKeyDiffersByTimeAndPlace() {
        assertNotEquals(Comments.key(v(1000L, 10.0, 20.0)), Comments.key(v(2000L, 10.0, 20.0)))
        assertNotEquals(Comments.key(v(1000L, 10.0, 20.0)), Comments.key(v(1000L, 10.5, 20.0)))
        assertEquals("V:1000:-", Comments.key(v(1000L, null, null)))
    }

    @Test fun visitKeyHasNoCoordinates() {
        val k = Comments.key(v(1000L, 10.123, 20.456))
        assert(!k.contains("10.123") && !k.contains("20.456"))
    }

    @Test fun excerpt() {
        assertEquals("Kort", Comments.excerpt("Kort"))
        assertEquals("Første …", Comments.excerpt("Første\nAndre"))
        val long = "ord ".repeat(30).trim()
        val e = Comments.excerpt(long)
        assert(e.endsWith("…") && e.length <= 61)
    }
}
