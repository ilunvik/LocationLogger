package no.grokbot.stedslogger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupTabTest {
    @Test fun allowsDirectorySites() {
        assertTrue(LookupTab.allowed("www.1881.no"))
        assertTrue(LookupTab.allowed("1881.no"))
        assertTrue(LookupTab.allowed("www.gulesider.no"))
        assertTrue(LookupTab.allowed("challenges.cloudflare.com"))
    }

    @Test fun blocksOtherSites() {
        assertFalse(LookupTab.allowed(null))
        assertFalse(LookupTab.allowed("example.com"))
        assertFalse(LookupTab.allowed("not1881.no"))
        assertFalse(LookupTab.allowed("gulesider.no.example.com"))
    }
}
