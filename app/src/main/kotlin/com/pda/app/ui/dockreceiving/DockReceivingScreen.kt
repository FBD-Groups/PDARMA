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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pda.app.data.api.model.ReceivingItemUi
import com.pda.app.ui.components.PdaTopBar
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DockReceivingScreen(
    onBack: () -> Unit,
    viewModel: DockReceivingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val inputMethod by viewModel.inputMethod.collectAsStateWithLifecycle()
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
                when (uiState.inputMethod) {
                    InputMethod.Picture -> RecordingBottomBar(
                        state = uiState,
                        onConfirm = viewModel::saveItem,
                        onCloseBatch = viewModel::requestCloseBatch
                    )
                    InputMethod.BarcodeScan -> ScanBottomBar(
                        busy = uiState.isBusy,
                        onCloseBatch = viewModel::requestCloseBatch
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (uiState.phase) {
                Phase.Idle -> IdleContent(
                    busy = uiState.isBusy,
                    method = inputMethod,
                    onMethodChange = viewModel::setInputMethod,
                    onStart = { viewModel.startBatch(inputMethod) }
                )
                Phase.Recording -> when (uiState.inputMethod) {
                    InputMethod.Picture -> RecordingContent(
                        state = uiState,
                        onPhotoCaptured = viewModel::onPhotoCaptured,
                        onTrackingChange = viewModel::onTrackingChanged,
                        onCarrierChange = viewModel::onCarrierChanged,
                        onConditionChange = viewModel::onConditionChanged
                    )
                    InputMethod.BarcodeScan -> ScanContent(
                        state = uiState,
                        onScan = viewModel::scanItem
                    )
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdleContent(
    busy: Boolean,
    method: InputMethod,
    onMethodChange: (InputMethod) -> Unit,
    onStart: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Input Method",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            InputMethod.entries.forEachIndexed { index, m ->
                SegmentedButton(
                    selected = method == m,
                    onClick = { onMethodChange(m) },
                    enabled = !busy,
                    shape = SegmentedButtonDefaults.itemShape(index, InputMethod.entries.size)
                ) { Text(m.label) }
            }
        }

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = onStart,
            enabled = !busy,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
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
        // 状态条（件数 + 上传/识别/已保存）放在最上方。
        RecordingStatusBar(state)
        Spacer(Modifier.height(8.dp))

        // Confirm fields on top (after capture).
        state.confirm?.let { confirm ->
            ConfirmFields(
                confirm = confirm,
                onTrackingChange = onTrackingChange,
                onCarrierChange = onCarrierChange,
                onConditionChange = onConditionChange
            )
            Spacer(Modifier.height(8.dp))
        }

        // Recorded items.
        if (state.items.isNotEmpty()) {
            state.items.forEach { item -> RecordedItemRow(item) }
        }

        // 弹性留白：把相机块顶到底部，紧贴下方按钮（空状态时空白在预览上方而非下方）。
        Spacer(Modifier.weight(1f))

        CameraCapture(
            modifier = Modifier.fillMaxWidth(),
            // 确认字段出现时预览压小，保证快门仍可见；瞄准时用大预览。
            previewHeight = if (state.confirm != null) 220.dp else 320.dp,
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
    }
}

/**
 * 扫码模式录入页：无相机预览。输入框默认获取焦点以接收扫码枪输入，但**不弹软键盘**
 * （buttons 不被遮挡）；需要手动输入时**双击输入框**才弹出软键盘。扫码/回车后自动建条目。
 */
@Composable
private fun ScanContent(
    state: DockReceivingUiState,
    onScan: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // 进入时聚焦（接收扫码枪），并隐藏软键盘。
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.hide()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        RecordingStatusBar(state)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Tracking # (scan / type + Enter)") },
            placeholder = { Text("Scan or enter tracking #…") },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                // 保持焦点但默认隐藏软键盘；单击不弹出，双击才弹出。
                .onFocusChanged { if (it.isFocused) keyboard?.hide() }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { /* 吞掉单击，避免弹出软键盘 */ },
                        onDoubleTap = { keyboard?.show() }
                    )
                },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    val t = text.trim()
                    if (t.isNotEmpty()) {
                        onScan(t)
                        text = ""
                    }
                    focusRequester.requestFocus()
                    keyboard?.hide()
                }
            )
        )

        Spacer(Modifier.height(12.dp))
        Text("Recorded (${state.itemCount})", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        // 最新的在最上面。
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(state.items.asReversed(), key = { it.receivingItemId }) { item ->
                ScanItemRow(item)
                HorizontalDivider()
            }
        }
    }
}

/** 扫码列表行，格式参考 Receive Report：运单号 + 承运商/需复核。 */
@Composable
private fun ScanItemRow(item: ReceivingItemUi) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            item.trackingNo.ifBlank { "(no tracking #)" },
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (item.needsReview) {
            Icon(Icons.Default.Warning, contentDescription = "Needs review", tint = MaterialTheme.colorScheme.error)
        } else if (item.carrier.isNotBlank()) {
            Text(
                item.carrier,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 扫码模式底栏：只有 Close Batch（条目扫码即自动保存，无需 Confirm）。 */
@Composable
private fun ScanBottomBar(busy: Boolean, onCloseBatch: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedButton(
                onClick = onCloseBatch,
                enabled = !busy,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text("Close Batch", maxLines = 1) }
        }
    }
}

/** 顶部状态条：件数 + 当前上传/识别/已保存状态。 */
@Composable
private fun RecordingStatusBar(state: DockReceivingUiState) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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

@Composable
private fun ProcessingStatus(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.5.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
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
    previewHeight: Dp = 320.dp,
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
        // 预览固定高度，大小恒定（不随上方内容伸缩）；快门紧跟其下。
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    // Some devices/emulators render black preview unless using COMPATIBLE.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    this.controller = controller
                }
            },
            modifier = Modifier.fillMaxWidth().height(previewHeight)
        )
        Spacer(Modifier.height(12.dp))
        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), contentAlignment = Alignment.Center) {
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
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        // Carrier 与 Condition 并排，省一行高度。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownField("Carrier", confirm.carrier, CARRIERS, onCarrierChange, modifier = Modifier.weight(1f))
            DropdownField("Condition", confirm.condition, CONDITIONS, onConditionChange, modifier = Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(modifier = modifier, expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            textStyle = MaterialTheme.typography.bodyMedium,
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
