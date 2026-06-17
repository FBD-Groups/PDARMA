package com.pda.app

import com.pda.app.data.api.model.ReceivedBatch
import com.pda.app.ui.receivereport.buildReceiveReport
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ReceiveReportTest {

    private val today = LocalDate.of(2026, 6, 17)

    private fun batch(no: String, dt: LocalDateTime, count: Int = 1) =
        ReceivedBatch(receivingBatchId = no.hashCode(), batchNumber = no, receivedAt = dt, itemCount = count)

    @Test
    fun `groups by day descending with Today and Yesterday labels`() {
        val batches = listOf(
            batch("A", LocalDateTime.of(2026, 6, 17, 9, 0)),
            batch("B", LocalDateTime.of(2026, 6, 16, 14, 0)),
            batch("C", LocalDateTime.of(2026, 6, 15, 8, 0))
        )
        val days = buildReceiveReport(batches, today)
        assertEquals(listOf("Today", "Yesterday", "Jun 15"), days.map { it.label })
    }

    @Test
    fun `within a day batches are sorted by time descending`() {
        val batches = listOf(
            batch("early", LocalDateTime.of(2026, 6, 17, 8, 0)),
            batch("late", LocalDateTime.of(2026, 6, 17, 17, 0))
        )
        val days = buildReceiveReport(batches, today)
        assertEquals(1, days.size)
        assertEquals(listOf("late", "early"), days[0].batches.map { it.batchNumber })
    }

    @Test
    fun `excludes batches older than three days`() {
        val batches = listOf(
            batch("in", LocalDateTime.of(2026, 6, 15, 9, 0)),   // today-2, included
            batch("out", LocalDateTime.of(2026, 6, 14, 9, 0))   // today-3, excluded
        )
        val days = buildReceiveReport(batches, today)
        assertEquals(listOf("in"), days.flatMap { it.batches }.map { it.batchNumber })
    }

    @Test
    fun `excludes future dates`() {
        val batches = listOf(batch("future", LocalDateTime.of(2026, 6, 18, 9, 0)))
        assertEquals(0, buildReceiveReport(batches, today).size)
    }

    @Test
    fun `empty input yields empty report`() {
        assertEquals(0, buildReceiveReport(emptyList(), today).size)
    }
}
