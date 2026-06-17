package com.pda.app.ui.receivereport

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pda.app.data.api.model.ReceivedBatch
import com.pda.app.ui.components.PdaTopBar
import com.pda.app.ui.i18n.AppStrings
import com.pda.app.ui.i18n.LocalAppLanguage
import com.pda.app.ui.i18n.LocalAppStrings
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val StatusDot = Color(0xFF1D9E75)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReceiveReportScreen(
    onBack: () -> Unit,
    onOpenBatch: (batchId: Int, batchNumber: String) -> Unit,
    viewModel: ReceiveReportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val strings = LocalAppStrings.current
    val locale = LocalAppLanguage.current.locale

    Scaffold(
        topBar = { PdaTopBar(title = strings.report_title, onBack = onBack) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                is ReceiveReportUiState.Loading ->
                    Text(strings.common_loading, modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                is ReceiveReportUiState.Empty ->
                    Text(strings.report_empty, modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                is ReceiveReportUiState.Error ->
                    Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::load) { Text(strings.common_retry) }
                    }
                is ReceiveReportUiState.Success ->
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        state.days.forEach { day ->
                            stickyHeader(key = "header-${day.date}") {
                                val total = day.batches.sumOf { it.itemCount }
                                DayHeader(
                                    label = dayHeaderLabel(day.kind, day.date, strings, locale),
                                    summary = strings.report_daySummary(day.batches.size, total)
                                )
                            }
                            items(day.batches, key = { it.receivingBatchId }) { batch ->
                                BatchRow(batch, onClick = { onOpenBatch(batch.receivingBatchId, batch.batchNumber) })
                                HorizontalDivider()
                            }
                        }
                    }
            }
        }
    }
}

private fun dayHeaderLabel(kind: DayKind, date: LocalDate, strings: AppStrings, locale: Locale): String = when (kind) {
    DayKind.Today -> strings.report_today
    DayKind.Yesterday -> strings.report_yesterday
    DayKind.Older -> date.format(DateTimeFormatter.ofPattern("MMM d", locale))
}

@Composable
private fun DayHeader(label: String, summary: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f)
        )
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun BatchRow(batch: ReceivedBatch, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(StatusDot))
        Box(modifier = Modifier.width(10.dp))
        Text(
            batch.batchNumber,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            batch.receivedAt.format(TIME_FORMAT),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(modifier = Modifier.width(10.dp))
        Text(
            batch.itemCount.toString(),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(24.dp)
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
