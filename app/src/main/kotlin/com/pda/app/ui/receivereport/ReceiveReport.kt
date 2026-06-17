package com.pda.app.ui.receivereport

import com.pda.app.data.api.model.ReceivedBatch
import java.time.LocalDate

/** Day-header kind; the visible label is formatted in the Composable (locale-aware). */
enum class DayKind { Today, Yesterday, Older }

/** Receive Report 中的一天分组：分类 + 日期 + 该天的批次（按收货时间倒序）。 */
data class ReceiveReportDay(
    val kind: DayKind,
    val date: LocalDate,
    val batches: List<ReceivedBatch>
)

/**
 * 纯函数（可单测）：把已收货批次过滤到最近三天（today、today-1、today-2），
 * 按天倒序分组，每天内按收货时间倒序。标签文案由 UI 层按语言格式化。
 */
fun buildReceiveReport(batches: List<ReceivedBatch>, today: LocalDate): List<ReceiveReportDay> {
    val windowStart = today.minusDays(2)
    return batches
        .filter { val d = it.receivedAt.toLocalDate(); !d.isBefore(windowStart) && !d.isAfter(today) }
        .groupBy { it.receivedAt.toLocalDate() }
        .toSortedMap(compareByDescending { it })
        .map { (date, list) ->
            ReceiveReportDay(
                kind = dayKind(date, today),
                date = date,
                batches = list.sortedByDescending { it.receivedAt }
            )
        }
}

private fun dayKind(date: LocalDate, today: LocalDate): DayKind = when (date) {
    today -> DayKind.Today
    today.minusDays(1) -> DayKind.Yesterday
    else -> DayKind.Older
}
