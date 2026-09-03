package com.pda.app

import com.pda.app.data.api.model.ActiveCustomer
import com.pda.app.ui.dockreceiving.CARRIERS
import com.pda.app.ui.dockreceiving.CONDITIONS
import com.pda.app.ui.dockreceiving.normalizeCarrier
import com.pda.app.ui.dockreceiving.normalizeCustomerCode
import com.pda.app.ui.dockreceiving.resolveCustomerFromAnalyze
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DockReceivingConstantsTest {

    @Test
    fun `carriers and conditions match web constants`() {
        assertEquals(listOf("UPS", "FedEx", "USPS", "DHL", "Amazon", "OnTrac", "Other"), CARRIERS)
        assertEquals(listOf("Good", "Fair", "Damaged", "Unknown"), CONDITIONS)
    }

    @Test
    fun `normalizeCarrier maps case-insensitively to canonical spelling`() {
        assertEquals("FedEx", normalizeCarrier("fedex"))
        assertEquals("FedEx", normalizeCarrier("FEDEX"))
        assertEquals("UPS", normalizeCarrier("ups"))
    }

    @Test
    fun `normalizeCarrier returns raw value when no match`() {
        assertEquals("LaserShip", normalizeCarrier("LaserShip"))
    }

    @Test
    fun `normalizeCarrier returns empty string for null or blank`() {
        assertEquals("", normalizeCarrier(null))
        assertEquals("", normalizeCarrier("  "))
    }

    @Test
    fun `normalizeCustomerCode strips RMA suffix`() {
        assertEquals("UF00162", normalizeCustomerCode("UF00162-RMA"))
        assertEquals("UF00162", normalizeCustomerCode("uf00162"))
        assertNull(normalizeCustomerCode(null))
        assertNull(normalizeCustomerCode("  "))
    }

    @Test
    fun `resolveCustomerFromAnalyze matches list name by UF code`() {
        val customers = listOf(ActiveCustomer(42, "UF00162", "RMA Technology"))
        val (id, name) = resolveCustomerFromAnalyze("UF00162-RMA", null, customers)
        assertEquals(42L, id)
        assertEquals("RMA Technology", name)
    }

    @Test
    fun `resolveCustomerFromAnalyze falls back to UF code when unmatched`() {
        val (id, name) = resolveCustomerFromAnalyze("UF00162-RMA", "ignored", emptyList())
        assertNull(id)
        assertEquals("UF00162", name)
    }

    @Test
    fun `resolveCustomerFromAnalyze uses AI name when no code`() {
        val (id, name) = resolveCustomerFromAnalyze(null, "Eco", emptyList())
        assertNull(id)
        assertEquals("Eco", name)
    }
}
