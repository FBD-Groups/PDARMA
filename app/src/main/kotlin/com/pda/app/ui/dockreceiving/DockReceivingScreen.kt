package com.pda.app.ui.dockreceiving

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlin.math.sqrt

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
                DockMessage.TrackingNotRecognized -> strings.dock_trackingNotRecognized
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
                onBack = onBack,
                trailing = if (uiState.phase == Phase.Recording) {
                    {
                        Text(
                            strings.itemCount(uiState.itemCount),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            maxLines = 1
                        )
                    }
                } else null
            )
        },
        bottomBar = {
            if (uiState.phase == Phase.Recording) {
                when (uiState.inputMethod) {
                    InputMethod.Picture -> RecordingBottomBar(
                        state = uiState,
                        onConfirm = viewModel::saveItem,
                        onCloseBatch = viewModel::confirmCloseBatch
                    )
                    InputMethod.BarcodeScan -> ScanBottomBar(
                        onCloseBatch = viewModel::confirmCloseBatch
                    )
                }
            }
        }
    ) { padding ->
        // 录货中按返回键直接关闭批次（与 Close Batch 按钮行为一致）。
        if (uiState.phase == Phase.Recording) {
            BackHandler(enabled = !uiState.isBusy) { viewModel.confirmCloseBatch() }
        }

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

            // 处理状态：多行半透明悬浮层，覆盖在预览上方、不占布局。
            // 只要有任意一项仍在进行就保持可见；全部结束后自动消失。
            val c = uiState.confirm
            if (c != null && (c.barcodeDecoding || c.uploading || c.analyzing || c.uploadFailed)) {
                ProcessingOverlay(confirm = c)
            }
        }
    }
}

/**
 * 多行半透明悬浮层（不占布局）：
 *  - 条码行：始终显示，扫描中/OK/未找到
 *  - 上传行：上传中或失败时显示
 *  - 识别行：AI 识别中时显示
 * 全部结束后由调用方隐藏整个 overlay。
 */
@Composable
private fun BoxScope.ProcessingOverlay(confirm: ConfirmState) {
    val strings = LocalAppStrings.current
    Surface(
        modifier = Modifier.align(Alignment.Center).padding(24.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.60f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            // 条码行：一直显示，反映最新状态
            OverlayStatusRow(
                spinning = confirm.barcodeDecoding,
                isError = !confirm.barcodeDecoding && confirm.barcodeTracking == null,
                label = when {
                    confirm.barcodeDecoding -> strings.dock_barcodeScanning
                    confirm.barcodeTracking != null -> strings.dock_barcodeOk
                    else -> strings.dock_barcodeNotFound
                }
            )
            // 上传行：上传中或失败时显示
            if (confirm.uploading || confirm.uploadFailed) {
                OverlayStatusRow(
                    spinning = confirm.uploading,
                    isError = confirm.uploadFailed,
                    label = if (confirm.uploading) strings.dock_uploading else strings.dock_uploadFailed
                )
            }
            // 识别行：AI 分析中时显示
            if (confirm.analyzing) {
                OverlayStatusRow(spinning = true, isError = false, label = strings.dock_analyzing)
            }
        }
    }
}

/**
 * 识别出运单号后浮在相机预览顶部的 chip：条码来源显示绿点，AI 来源显示主题色点。
 * 小屏幕下运单号输入框可能被遮住，此 chip 确保结果始终可见。
 */
@Composable
private fun TrackingChipOverlay(tracking: String, fromBarcode: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color.Black.copy(alpha = 0.65f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (fromBarcode) Color(0xFF66BB6A) else MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                tracking,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun OverlayStatusRow(spinning: Boolean, isError: Boolean, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        if (spinning) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
        } else {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isError) Color(0xFFFF5252) else Color(0xFF66BB6A))
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (isError) Color(0xFFFF5252) else Color.White
        )
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
    // 单一结构（两态一致，相机绝不跳动）：
    //  · 状态栏固定在顶
    //  · 中间区域占满剩余空间，承载确认字段（瞄准态为空），可滚动；它把相机顶到底
    //  · 相机固定高度，永远贴在最底部、紧挨下方按钮
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
        ) {
            state.confirm?.let { confirm ->
                ConfirmFields(
                    confirm = confirm,
                    onTrackingChange = onTrackingChange,
                    onCarrierChange = onCarrierChange,
                    onConditionChange = onConditionChange
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        // 相机区域套 Box：识别出运单号后在预览顶部浮动显示，小屏不再被字段遮住。
        Box(modifier = Modifier.fillMaxWidth()) {
            CameraCapture(
                modifier = Modifier.fillMaxWidth(),
                previewHeight = 240.dp,
                onPhotoCaptured = onPhotoCaptured
            )
            // 有已自动识别的运单号时（条码或 AI），浮在预览顶部
            val displayTracking = state.confirm?.let { c ->
                (c.barcodeTracking ?: c.trackingNumber.takeIf { c.trackingAutoFilled })
                    ?.takeIf { it.isNotBlank() }
            }
            if (displayTracking != null) {
                TrackingChipOverlay(
                    tracking = displayTracking,
                    fromBarcode = state.confirm?.trackingFromBarcode == true
                        || state.confirm?.barcodeTracking != null,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                )
            }
        }
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
        // 设备端稳后快门变主色（就绪），晃动时为灰色——提示用户拿稳再拍，减少糊片。
        val steady = rememberCameraSteady()
        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), contentAlignment = Alignment.Center) {
            ShutterButton(
                ready = steady,
                onClick = { capturePhoto(context, controller, cameraExecutor, onPhotoCaptured) }
            )
        }
    }
}

/**
 * 用陀螺仪判断设备是否端稳：角速度低于阈值并持续约 250ms 视为稳定。
 * 无陀螺仪的设备直接视为稳定（不拦路）。
 */
@Composable
private fun rememberCameraSteady(): Boolean {
    val context = LocalContext.current
    var steady by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (gyro == null) {
            steady = true
            onDispose {}
        } else {
            var steadySinceNanos = 0L
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    val mag = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                    if (mag < STEADY_THRESHOLD) {
                        if (steadySinceNanos == 0L) steadySinceNanos = e.timestamp
                        if (!steady && e.timestamp - steadySinceNanos > STEADY_HOLD_NANOS) steady = true
                    } else {
                        steadySinceNanos = 0L
                        if (steady) steady = false
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sm.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_UI)
            onDispose { sm.unregisterListener(listener) }
        }
    }
    return steady
}

private const val STEADY_THRESHOLD = 0.12f          // rad/s，手持端稳的角速度上限
private const val STEADY_HOLD_NANOS = 250_000_000L  // 持续稳定 250ms 才算就绪

/** 相机快门大圆点：就绪（端稳）时主色，晃动时灰色。 */
@Composable
private fun ShutterButton(ready: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(
        targetValue = if (ready) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        label = "shutterColor"
    )
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .border(width = 4.dp, color = color, shape = CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(color)
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
