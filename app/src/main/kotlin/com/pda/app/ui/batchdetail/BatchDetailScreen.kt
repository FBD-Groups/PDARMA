package com.pda.app.ui.batchdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pda.app.data.api.model.ReceivingItemUi
import com.pda.app.ui.components.PdaTopBar
import com.pda.app.ui.i18n.LocalAppStrings

private val ReviewBg = Color(0xFFFAEEDA)
private val ReviewFg = Color(0xFF854F0B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchDetailScreen(
    onBack: () -> Unit,
    viewModel: BatchDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val strings = LocalAppStrings.current
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingVoid by remember { mutableStateOf<ReceivingItemUi?>(null) }

    LaunchedEffect(uiState) {
        val msg = (uiState as? BatchDetailUiState.Success)?.message
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = { PdaTopBar(title = viewModel.batchNumber, onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                is BatchDetailUiState.Loading ->
                    Text(strings.common_loading, modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                is BatchDetailUiState.Empty ->
                    Text(strings.batch_empty, modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                is BatchDetailUiState.Error ->
                    Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::load) { Text(strings.common_retry) }
                    }
                is BatchDetailUiState.Success ->
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item(key = "summary") {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    strings.itemCount(state.items.size),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            HorizontalDivider()
                        }
                        items(state.items, key = { it.receivingItemId }) { item ->
                            ItemRow(
                                item = item,
                                voiding = state.voidingItemId == item.receivingItemId,
                                voidEnabled = state.voidingItemId == null,
                                onVoidClick = { pendingVoid = item }
                            )
                            HorizontalDivider()
                        }
                    }
            }
        }
    }

    val toVoid = pendingVoid
    if (toVoid != null) {
        AlertDialog(
            onDismissRequest = { pendingVoid = null },
            title = { Text(strings.batch_voidConfirmTitle) },
            text = {
                Text(
                    strings.batch_voidConfirmBody(
                        toVoid.trackingNo.ifBlank { strings.batch_noTracking }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = toVoid.receivingItemId
                        pendingVoid = null
                        viewModel.voidItem(id)
                    }
                ) {
                    Text(strings.batch_void, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingVoid = null }) {
                    Text(strings.common_cancel)
                }
            }
        )
    }
}

@Composable
private fun ItemRow(
    item: ReceivingItemUi,
    voiding: Boolean,
    voidEnabled: Boolean,
    onVoidClick: () -> Unit
) {
    val strings = LocalAppStrings.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.trackingNo.ifBlank { strings.batch_noTracking },
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (item.carrier.isNotBlank()) {
                Text(item.carrier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.customerName.isNotBlank()) {
                Text(
                    item.customerName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        if (item.needsReview) {
            Surface(color = ReviewBg, shape = MaterialTheme.shapes.small) {
                Text(
                    strings.batch_needsReview,
                    color = ReviewFg,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        val error = MaterialTheme.colorScheme.error
        OutlinedButton(
            onClick = onVoidClick,
            enabled = voidEnabled && !voiding,
            modifier = Modifier.heightIn(min = 36.dp),
            border = BorderStroke(1.dp, error.copy(alpha = if (voidEnabled && !voiding) 0.7f else 0.3f)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = error,
                containerColor = error.copy(alpha = 0.08f),
                disabledContentColor = error.copy(alpha = 0.38f),
                disabledContainerColor = error.copy(alpha = 0.04f)
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                if (voiding) strings.batch_voiding else strings.batch_void,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
