package com.pda.app

import com.pda.app.ui.dockreceiving.sanitizeTracking
import com.pda.app.ui.dockreceiving.shortenFedExTracking
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingSanitizeTest {

    @Test
    fun `accepts real tracking numbers`() {
        assertEquals("9400136110139348703814", sanitizeTracking("9400136110139348703814")) // USPS
        assertEquals("1Z999AA10123456784", sanitizeTracking("1Z999AA10123456784"))         // UPS
        assertEquals("1Z999AA10123456784", sanitizeTracking("  1Z999AA10123456784  "))     // trims
        assertEquals("876506835781", sanitizeTracking("876506835781"))                   // FedEx 12
        assertEquals("123456789012345", sanitizeTracking("123456789012345"))             // FedEx 15 保留
    }

    @Test
    fun `FedEx long 96 barcode shortens to last 12 digits`() {
        assertEquals("792666828163", sanitizeTracking("9622013700009956233900792666828163"))
        assertEquals("792653340425", sanitizeTracking("9622013700009956233900792653340425"))
        assertEquals("875226265811", sanitizeTracking("9632080060203377981000875226265811"))
        assertEquals("792695417702", sanitizeTracking("9622013700009956233900792695417702"))
    }

    @Test
    fun `shortenFedExTracking only shortens 96 long barcodes`() {
        assertEquals("792695417702", shortenFedExTracking("9622013700009956233900792695417702"))
        assertEquals("876506835781", shortenFedExTracking("876506835781"))
        assertEquals("123456789012345", shortenFedExTracking("123456789012345"))
    }

    @Test
    fun `rejects empty and garbage values`() {
        assertEquals("", sanitizeTracking(null))
        assertEquals("", sanitizeTracking(""))
        assertEquals("", sanitizeTracking("N/A"))
        assertEquals("", sanitizeTracking("未找到"))
        assertEquals("", sanitizeTracking("tracking number not found"))
        assertEquals("", sanitizeTracking("1234"))
        assertEquals("", sanitizeTracking("ABCDEFGHIJ"))
        assertEquals("", sanitizeTracking("FWD260823000044"))
    }
}
