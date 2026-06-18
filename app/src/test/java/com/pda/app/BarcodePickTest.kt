package com.pda.app

import com.pda.app.ui.dockreceiving.pickTrackingBarcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BarcodePickTest {

    @Test
    fun `prefers UPS 1Z over a routing code`() {
        // 1Z tracking + a short routing code
        val picked = pickTrackingBarcode(listOf("420900001234", "1Z999AA10123456784"))
        assertEquals("1Z999AA10123456784", picked)
    }

    @Test
    fun `prefers USPS IMpb 94 prefix`() {
        val picked = pickTrackingBarcode(
            listOf("9400136110139348703814", "00123456")  // IMpb + short numeric
        )
        assertEquals("9400136110139348703814", picked)
    }

    @Test
    fun `strips spaces and hyphens to the compact form`() {
        val picked = pickTrackingBarcode(listOf("9400 1361 1013 9348 7038 14"))
        assertEquals("9400136110139348703814", picked)
    }

    @Test
    fun `longest wins when no known prefix matches`() {
        val picked = pickTrackingBarcode(listOf("12345678", "1234567890123456"))
        assertEquals("1234567890123456", picked)
    }

    @Test
    fun `returns null when no candidate looks like a tracking number`() {
        // too short / not enough digits
        assertNull(pickTrackingBarcode(listOf("ABC", "12", "HELLO")))
    }

    @Test
    fun `returns null for empty input`() {
        assertNull(pickTrackingBarcode(emptyList()))
    }
}
