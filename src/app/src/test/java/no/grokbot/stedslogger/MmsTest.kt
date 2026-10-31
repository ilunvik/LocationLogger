package no.grokbot.stedslogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MmsTest {
    private fun p(ct: String) = MmsPart(1, ct, null)

    @Test fun summary() {
        assertNull(Comms.attachmentSummary(emptyList()))
        assertNull(Comms.attachmentSummary(listOf(MmsPart(1, "text/plain", null, "hei"))))
        assertEquals("med bilde", Comms.attachmentSummary(listOf(p("image/jpeg"))))
        assertEquals("med 2 bilder", Comms.attachmentSummary(listOf(p("image/jpeg"), p("image/png"))))
        assertEquals("med video", Comms.attachmentSummary(listOf(p("video/mp4"))))
        assertEquals("med lydklipp", Comms.attachmentSummary(listOf(p("audio/amr"))))
        assertEquals("med vedlegg", Comms.attachmentSummary(listOf(p("text/x-vcard"))))
        assertEquals("med bilde og vedlegg", Comms.attachmentSummary(listOf(p("image/jpeg"), p("text/x-vcard"))))
    }

    @Test fun subject() {
        val latin1 = String("Blåbær".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        assertEquals("Blåbær", Comms.decodeSubject(latin1, 106))
        assertEquals("Blåbær", Comms.decodeSubject("Blåbær", 106))
        assertEquals("Hei", Comms.decodeSubject(" Hei ", 0))
        assertNull(Comms.decodeSubject("  ", 106))
    }

    @Test fun labelsAndTypes() {
        assertEquals("🎬 Video", p("video/3gpp").label())
        assertEquals("👤 Kontaktkort", p("text/x-vcard").label())
        assertEquals("image/jpeg", Comms.guessType("IMG_1.JPG"))
        assertNull(Comms.guessType("noext"))
    }
}
