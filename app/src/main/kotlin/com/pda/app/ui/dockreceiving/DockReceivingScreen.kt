package com.pda.app.ui.dockreceiving

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pda.app.data.api.model.ReceivingItemUi
import com.pda.app.ui.components.PdaTopBar
import java.io.File
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DockReceivingScreen(
    onBack: () -> Unit,
    viewModel: DockReceivingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            PdaTopBar(
                title = when (uiState.phase) {
                    Phase.Idle -> "Dock Receive"
                    else -> uiState.batchNumber?.let { "Batch $it" } ?: "Dock Receive"
                },
                onBack = onBack
            )
        },
        bottomBar = {
            if (uiState.phase == Phase.Recording) {
                RecordingBottomBar(
                    state = uiState,
                    onConfirm = viewModel::saveItem,
                    onCloseBatch = viewModel::requestCloseBatch
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (uiState.phase) {
                Phase.Idle -> IdleContent(
                    busy = uiState.isBusy,
                    onStart = viewModel::startBatch
                )
                Phase.Recording -> RecordingContent(
                    state = uiState,
                    onPhotoCaptured = viewModel::onPhotoCaptured,
                    onTrackingChange = viewModel::onTrackingChanged,
                    onCarrierChange = viewModel::onCarrierChanged,
                    onConditionChange = viewModel::onConditionChanged
                )
            }

            if (uiState.showCloseDialog) {
                CloseBatchDialog(
                    itemCount = uiState.itemCount,
                    needsReviewCount = uiState.needsReviewCount,
                    busy = uiState.isBusy,
                    onConfirm = viewModel::confirmCloseBatch,
                    onDismiss = viewModel::dismissCloseDialog
                )
            }
        }
    }
}

@Composable
private fun IdleContent(busy: Boolean, onStart: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(onClick = onStart, enabled = !busy, modifier = Modifier.height(56.dp)) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Start Batch")
        }
    }
}

@Composable
private fun RecordingContent(
    state: DockReceivingUiState,
    onPhotoCaptured: (File) -> Unit,
    onTrackingChange: (String) -> Unit,
    onCarrierChange: (String) -> Unit,
    onConditionChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Confirm fields on top (after capture).
        state.confirm?.let { confirm ->
            ConfirmFields(
                confirm = confirm,
                onTrackingChange = onTrackingChange,
                onCarrierChange = onCarrierChange,
                onConditionChange = onConditionChange
            )
            Spacer(Modifier.height(12.dp))
        }

        // Recorded items fill the middle; this also pushes the preview down to the
        // bottom of the screen (even right after starting a new batch, with no items).
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(state.items, key = { it.receivingItemId }) { item -> RecordedItemRow(item) }
        }

        Spacer(Modifier.height(8.dp))
        CameraCapture(
            modifier = Modifier.fillMaxWidth(),
            onPhotoCaptured = onPhotoCaptured
        )
    }
}

@Composable
private fun RecordingBottomBar(
    state: DockReceivingUiState,
    onConfirm: () -> Unit,
    onCloseBatch: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onCloseBatch,
                    enabled = !state.isBusy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("Close Batch", maxLines = 1) }
                Button(
                    onClick = onConfirm,
                    enabled = state.confirm?.canSave == true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    if (state.confirm?.saving == true)
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Confirm", maxLines = 1)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${state.itemCount} items", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                val c = state.confirm
                when {
                    c?.uploading == true -> ProcessingStatus("Uploading…")
                    c?.analyzing == true -> ProcessingStatus("Analyzing…")
                    c?.uploadFailed == true -> Text(
                        "Upload failed — retake",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    state.recentlySaved -> Text(
                        "Saved",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcessingStatus(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RecordedItemRow(item: ReceivingItemUi) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.trackingNo.ifBlank { "(no tracking #)" }, fontWeight = FontWeight.Medium)
            Text(item.carrier.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall)
        }
        if (item.needsReview) {
            Icon(Icons.Default.Warning, contentDescription = "Needs review", tint = MaterialTheme.colorScheme.error)
        }
    }
    HorizontalDivider()
}

@Composable
private fun CameraCapture(
    modifier: Modifier = Modifier,
    onPhotoCaptured: (File) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasPermission) {
        Box(modifier = modifier.height(200.dp), contentAlignment = Alignment.Center) {
            Text(
                "Camera permission required. Enable it in Settings and come back.",
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        return
    }

    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            // 拍前等对焦/曝光（3A）收敛再出片，减少糊片，运单号更清晰。
            // 点按预览对焦由 PreviewView+controller 默认开启（isTapToFocusEnabled 默认 true）。
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            // Preview is always enabled when bound to PreviewView; only configure capture.
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        }
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.unbind()
            cameraExecutor.shutdown()
        }
    }

    Column(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    // Some devices/emulators render black preview unless using COMPATIBLE.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = controller
                }
            },
            modifier = Modifier.fillMaxWidth().height(200.dp)
        )
        Spacer(Modifier.height(12.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ShutterButton(onClick = { capturePhoto(context, controller, cameraExecutor, onPhotoCaptured) })
        }
    }
}

/** 相机快门样式的大圆点：外圈描边 + 内部实心圆。 */
@Composable
private fun ShutterButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .border(width = 4.dp, color = MaterialTheme.colorScheme.primary, shape = CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
    )
}

private fun capturePhoto(
    context: Context,
    controller: LifecycleCameraController,
    executor: java.util.concurrent.ExecutorService,
    onPhotoCaptured: (File) -> Unit
) {
    val file = File.createTempFile("capture", ".jpg", context.cacheDir)
    val output = ImageCapture.OutputFileOptions.Builder(file).build()
    controller.takePicture(
        output,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                onPhotoCaptured(file)
            }
            override fun onError(exception: ImageCaptureException) {
                // Swallow; user can retry. (Logged by CameraX internally.)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmFields(
    confirm: ConfirmState,
    onTrackingChange: (String) -> Unit,
    onCarrierChange: (String) -> Unit,
    onConditionChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = confirm.trackingNumber,
            onValueChange = onTrackingChange,
            label = { Text("Tracking #") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        DropdownField("Carrier", confirm.carrier, CARRIERS, onCarrierChange)
        Spacer(Modifier.height(12.dp))
        DropdownField("Condition", confirm.condition, CONDITIONS, onConditionChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onValueChange(opt); expanded = false })
            }
        }
    }
}

@Composable
private fun CloseBatchDialog(
    itemCount: Int,
    needsReviewCount: Int,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Close Batch") },
        text = {
            Text(
                buildString {
                    append("$itemCount items")
                    if (needsReviewCount > 0) append(", $needsReviewCount need review")
                    append(". Close this batch?")
                }
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !busy) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Close")
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}
