package com.pda.app

import com.pda.app.data.api.model.ActiveCustomer
import com.pda.app.ui.dockreceiving.CARRIERS
import com.pda.app.ui.dockreceiving.CONDITIONS
import com.pda.app.ui.dockreceiving.matchCustomerByCodeOrAlias
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

    @Test
    fun `resolveCustomerFromAnalyze matches by alias when no code hit`() {
        val ecoflow = ActiveCustomer(1, "UF00073", "EcoFlow", listOf("eco"))
        val (id, name) = resolveCustomerFromAnalyze(null, "ECO SORTING", listOf(ecoflow))
        assertEquals(1L, id)
        assertEquals("EcoFlow", name)
    }

    // ── matchCustomerByCodeOrAlias — 对齐 web returnClient.test.ts ──────────────────────

    @Test
    fun `matchCustomerByCodeOrAlias matches by code even when another customer's alias also matches the name`() {
        val eco = ActiveCustomer(1, "UF00073", "EcoFlow", listOf("eco"))
        val target = ActiveCustomer(2, "UF00190", "Geekbuy", listOf("geekbuy"))
        val result = matchCustomerByCodeOrAlias("uf00190", "ECO SORTING", listOf(eco, target))
        assertEquals(target, result)
    }

    @Test
    fun `matchCustomerByCodeOrAlias matches long alias via substring`() {
        val ecoflow = ActiveCustomer(1, "UF00073", "EcoFlow", listOf("ef tiktok"))
        assertEquals(ecoflow, matchCustomerByCodeOrAlias(null, "EF TikTok RETURN", listOf(ecoflow)))
    }

    @Test
    fun `matchCustomerByCodeOrAlias does not match E-CONOLOGY when only no-hyphen alias configured`() {
        val ecoflow = ActiveCustomer(1, "UF00073", "EcoFlow", listOf("econology"))
        assertNull(matchCustomerByCodeOrAlias(null, "E-CONOLOGY", listOf(ecoflow)))
    }

    @Test
    fun `matchCustomerByCodeOrAlias matches E-CONOLOGY when hyphenated alias configured`() {
        val ecoflow = ActiveCustomer(1, "UF00073", "EcoFlow", listOf("e-conology"))
        assertEquals(ecoflow, matchCustomerByCodeOrAlias(null, "E-CONOLOGY", listOf(ecoflow)))
    }

    @Test
    fun `matchCustomerByCodeOrAlias does not match BAMBU LAB when only no-space alias configured`() {
        val bambu = ActiveCustomer(1, "UF00162", "Bambulab", listOf("bambulab"))
        assertNull(matchCustomerByCodeOrAlias(null, "BAMBU LAB", listOf(bambu)))
    }

    @Test
    fun `matchCustomerByCodeOrAlias matches BAMBU LAB when spaced alias configured`() {
        val bambu = ActiveCustomer(1, "UF00162", "Bambulab", listOf("bambu lab"))
        assertEquals(bambu, matchCustomerByCodeOrAlias(null, "BAMBU LAB", listOf(bambu)))
    }

    @Test
    fun `matchCustomerByCodeOrAlias matches BAM BU LAB when double-spaced alias configured`() {
        val bambu = ActiveCustomer(1, "UF00162", "Bambulab", listOf("bam bu lab"))
        assertEquals(bambu, matchCustomerByCodeOrAlias(null, "BAM BU LAB", listOf(bambu)))
    }

    @Test
    fun `matchCustomerByCodeOrAlias matches short alias only as whole word`() {
        val ecoflow = ActiveCustomer(1, "UF00073", "EcoFlow", listOf("eco"))
        assertEquals(ecoflow, matchCustomerByCodeOrAlias(null, "ECO", listOf(ecoflow)))
        assertEquals(ecoflow, matchCustomerByCodeOrAlias(null, "ECO TECHNOLOGY", listOf(ecoflow)))
    }

    @Test
    fun `matchCustomerByCodeOrAlias does not let short alias match inside a longer word`() {
        val ecoflow = ActiveCustomer(1, "UF00073", "EcoFlow", listOf("eco"))
        assertNull(matchCustomerByCodeOrAlias(null, "DECOR", listOf(ecoflow)))
        assertNull(matchCustomerByCodeOrAlias(null, "ECOLOGY", listOf(ecoflow)))
    }

    @Test
    fun `matchCustomerByCodeOrAlias returns null when name matches aliases of more than one customer`() {
        val a = ActiveCustomer(1, "UF00001", "A", listOf("dupe"))
        val b = ActiveCustomer(2, "UF00002", "B", listOf("dupe"))
        assertNull(matchCustomerByCodeOrAlias(null, "SOME DUPE TEXT", listOf(a, b)))
    }

    @Test
    fun `matchCustomerByCodeOrAlias is case-insensitive and trims whitespace`() {
        val dji = ActiveCustomer(1, "UF00175", "DJI", listOf("dji"))
        assertEquals(dji, matchCustomerByCodeOrAlias("  uf00175  ", null, listOf(dji)))
        assertEquals(dji, matchCustomerByCodeOrAlias(null, "  dji official  ", listOf(dji)))
    }

    @Test
    fun `matchCustomerByCodeOrAlias returns null when nothing matches`() {
        val dji = ActiveCustomer(1, "UF00175", "DJI", listOf("dji"))
        assertNull(matchCustomerByCodeOrAlias("UF99999", "Some Random Text", listOf(dji)))
    }

    @Test
    fun `matchCustomerByCodeOrAlias returns null when both code and name are empty`() {
        val dji = ActiveCustomer(1, "UF00175", "DJI", listOf("dji"))
        assertNull(matchCustomerByCodeOrAlias(null, null, listOf(dji)))
    }
}
