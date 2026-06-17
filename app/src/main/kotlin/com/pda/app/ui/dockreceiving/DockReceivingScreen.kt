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
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pda.app.data.api.model.ReceivingItemUi
import com.pda.app.ui.components.PdaTopBar
import com.pda.app.ui.i18n.LocalAppStrings
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
    val strings = LocalAppStrings.current

    LaunchedEffect(uiState.message) {
        uiState.message?.let { msg ->
            val text = when (msg) {
                is DockMessage.Text -> msg.value
                DockMessage.SelectWarehouseFirst -> strings.dock_selectWarehouseFirst
                DockMessage.PhotoProcessingFailed -> strings.dock_photoProcessingFailed
                is DockMessage.BatchClosed -> strings.dock_batchClosed(msg.number)
            }
            snackbarHostState.showSnackbar(text)
            viewModel.messageShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            PdaTopBar(
                title = when (uiState.phase) {
                    Phase.Idle -> strings.dock_title
                    else -> uiState.batchNumber?.let { strings.dock_batchTitle(it) } ?: strings.dock_title
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

@Composable
private fun IdleContent(
    busy: Boolean,
    method: InputMethod,
    onMethodChange: (InputMethod) -> Unit,
    onStart: () -> Unit
) {
    val strings = LocalAppStrings.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            strings.dock_inputMethod,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            InputMethod.entries.forEach { m ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) { onMethodChange(m) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = m == method,
                        onClick = { onMethodChange(m) },
                        enabled = !busy
                    )
                    Text(m.label(), style = MaterialTheme.typography.bodyLarge)
                }
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
            else Text(strings.dock_startBatch)
        }
    }
}

/** 录入方式的本地化标签。 */
@Composable
private fun InputMethod.label(): String = when (this) {
    InputMethod.Picture -> LocalAppStrings.current.dock_inputMethodPicture
    InputMethod.BarcodeScan -> LocalAppStrings.current.dock_inputMethodBarcode
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

        // Only show confirm fields for the current capture — no accumulated item list.
        // Item count is already visible in the status bar above.
        state.confirm?.let { confirm ->
            ConfirmFields(
                confirm = confirm,
                onTrackingChange = onTrackingChange,
                onCarrierChange = onCarrierChange,
                onConditionChange = onConditionChange
            )
            Spacer(Modifier.height(8.dp))
        }

        // Push camera to the bottom regardless of whether confirm fields are showing.
        Spacer(Modifier.weight(1f))

        CameraCapture(
            modifier = Modifier.fillMaxWidth(),
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
    val strings = LocalAppStrings.current
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
            ) { Text(strings.dock_closeBatch, maxLines = 1) }
            Button(
                onClick = onConfirm,
                enabled = state.confirm?.canSave == true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                if (state.confirm?.saving == true)
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(strings.dock_confirm, maxLines = 1)
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
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        RecordingStatusBar(state)
        Spacer(Modifier.height(8.dp))

        ScanInputField(onScan = onScan)

        Spacer(Modifier.height(8.dp))
        // 最新的在最上面。
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(state.items.asReversed(), key = { it.receivingItemId }) { item ->
                ScanItemRow(item)
                HorizontalDivider()
            }
        }
    }
}

/**
 * 原生 EditText 扫码输入框：聚焦以接收扫码枪硬件输入，但 showSoftInputOnFocus=false 使其
 * **进入/单击都不弹软键盘**；**双击**才显式调出软键盘供手动输入。回车（含扫码枪 Enter）提交。
 */
@Composable
private fun ScanInputField(onScan: (String) -> Unit) {
    val context = LocalContext.current
    val imm = remember { context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }
    val hintText = LocalAppStrings.current.dock_scanHint

    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            factory = { ctx ->
                EditText(ctx).apply {
                    hint = hintText
                    isSingleLine = true
                    background = null
                    textSize = 18f
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    showSoftInputOnFocus = false
                    setOnEditorActionListener { v, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                            val t = v.text.toString().trim()
                            if (t.isNotEmpty()) {
                                onScan(t)
                                (v as EditText).setText("")
                            }
                            showSoftInputOnFocus = false
                            imm.hideSoftInputFromWindow(v.windowToken, 0)
                            true
                        } else false
                    }
                    val gesture = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (showSoftInputOnFocus) {
                                showSoftInputOnFocus = false
                                imm.hideSoftInputFromWindow(windowToken, 0)
                            } else {
                                showSoftInputOnFocus = true
                                requestFocus()
                                imm.showSoftInput(this@apply, InputMethodManager.SHOW_IMPLICIT)
                            }
                            return true
                        }
                    })
                    setOnTouchListener { _, ev -> gesture.onTouchEvent(ev); false }
                    post { requestFocus() }
                }
            }
        )
    }
}

private val ScanItemDot = Color(0xFF1D9E75)

@Composable
private fun ScanItemRow(item: ReceivingItemUi) {
    val strings = LocalAppStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(ScanItemDot))
        Spacer(Modifier.width(10.dp))
        Text(
            item.trackingNo.ifBlank { strings.dock_noTracking },
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (item.needsReview) {
            Icon(Icons.Default.Warning, contentDescription = strings.dock_needsReview, tint = MaterialTheme.colorScheme.error)
        } else if (item.carrier.isNotBlank()) {
            Text(
                item.carrier,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 扫码模式底栏：只有 Close Batch（条目扫码即自动保存，无需 Confirm）。此处始终可点。 */
@Composable
private fun ScanBottomBar(onCloseBatch: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Button(
                onClick = onCloseBatch,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text(LocalAppStrings.current.dock_closeBatch, maxLines = 1) }
        }
    }
}

/** 顶部状态条：件数 + 当前上传/识别/已保存状态。 */
@Composable
private fun RecordingStatusBar(state: DockReceivingUiState) {
    val strings = LocalAppStrings.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(strings.itemCount(state.itemCount), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        val c = state.confirm
        when {
            c?.uploading == true -> ProcessingStatus(strings.dock_uploading)
            c?.analyzing == true -> ProcessingStatus(strings.dock_analyzing)
            c?.uploadFailed == true -> Text(
                strings.dock_uploadFailed,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            state.recentlySaved -> Text(
                strings.dock_saved,
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
                LocalAppStrings.current.dock_cameraPermission,
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
    val strings = LocalAppStrings.current
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = confirm.trackingNumber,
            onValueChange = onTrackingChange,
            label = { Text(strings.dock_trackingLabel) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        // Carrier 与 Condition 并排，省一行高度。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownField(strings.dock_carrier, confirm.carrier, CARRIERS, onCarrierChange, modifier = Modifier.weight(1f))
            DropdownField(strings.dock_condition, confirm.condition, CONDITIONS, onConditionChange, modifier = Modifier.weight(1f))
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
    val strings = LocalAppStrings.current
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(strings.dock_closeBatch) },
        text = { Text(strings.dock_closeBatchPrompt(itemCount, needsReviewCount)) },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !busy) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(strings.dock_close)
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss, enabled = !busy) { Text(strings.common_cancel) } }
    )
}
