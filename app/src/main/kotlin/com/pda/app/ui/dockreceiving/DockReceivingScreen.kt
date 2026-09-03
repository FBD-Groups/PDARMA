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
import android.view.KeyEvent
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
import androidx.compose.foundation.text.BasicTextField
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
            when (msg) {
                is DockMessage.BatchClosed -> {
                    // 关批成功后离开拍照页（顶栏返回 / 系统返回 / Close 共用）。
                    viewModel.messageShown()
                    onBack()
                }
                else -> {
                    val text = when (msg) {
                        is DockMessage.Text -> msg.value
                        DockMessage.SelectWarehouseFirst -> strings.dock_selectWarehouseFirst
                        DockMessage.PhotoProcessingFailed -> strings.dock_photoProcessingFailed
                        DockMessage.TrackingNotRecognized -> strings.dock_trackingNotRecognized
                        is DockMessage.BatchClosed -> strings.dock_batchClosed(msg.number) // unreachable
                    }
                    snackbarHostState.showSnackbar(text)
                    viewModel.messageShown()
                }
            }
        }
    }

    fun requestExit() {
        if (uiState.isBusy) return
        if (uiState.phase == Phase.Recording) {
            viewModel.confirmCloseBatch()
        } else {
            onBack()
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
                onBack = { requestExit() },
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
        // 录货中系统返回 = 关批次后离开（与顶栏返回一致）。
        BackHandler(enabled = !uiState.isBusy) { requestExit() }

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
                        onCustomerNameChange = viewModel::onCustomerNameChanged
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

            val dup = uiState.confirm?.pendingDuplicateTracking
            if (dup != null) {
                AlertDialog(
                    onDismissRequest = viewModel::dismissDuplicateSave,
                    title = { Text(strings.dock_duplicateTitle) },
                    text = { Text(strings.dock_duplicateBody(dup)) },
                    confirmButton = {
                        TextButton(onClick = viewModel::confirmDuplicateSave) {
                            Text(strings.dock_duplicateConfirm)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = viewModel::dismissDuplicateSave) {
                            Text(strings.common_cancel)
                        }
                    }
                )
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
    onCustomerNameChange: (String) -> Unit
) {
    // 顶部仅 Tracking + Customer（矮栏、无上下额外边距）；相机吃满剩余高度，快门叠在预览底边。
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        state.confirm?.let { confirm ->
            ConfirmFields(
                confirm = confirm,
                onTrackingChange = onTrackingChange,
                onCustomerNameChange = onCustomerNameChange
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            CameraCapture(
                modifier = Modifier.fillMaxSize(),
                onPhotoCaptured = onPhotoCaptured
            )
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
                    // 软键盘 Done 按钮（手动输入时）
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
                    // DataWedge / 扫码枪发 KEYCODE_ENTER 硬件事件，不走 IME 路径，需单独捕获。
                    setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                            val t = text.toString().trim()
                            if (t.isNotEmpty()) {
                                onScan(t)
                                setText("")
                            }
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
        } else {
            Column(horizontalAlignment = Alignment.End) {
                if (item.carrier.isNotBlank()) {
                    Text(
                        item.carrier,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
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
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
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

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    this.controller = controller
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        val steady = rememberCameraSteady()
        ShutterButton(
            ready = steady,
            onClick = { capturePhoto(context, controller, cameraExecutor, onPhotoCaptured) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
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

/** 半透明快门：描边环 + 透明底，叠在预览底部。就绪为主色，晃动为淡白。 */
@Composable
private fun ShutterButton(
    ready: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ring by animateColorAsState(
        targetValue = if (ready) MaterialTheme.colorScheme.primary.copy(alpha = 0.95f)
        else Color.White.copy(alpha = 0.55f),
        label = "shutterRing"
    )
    Box(
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .border(width = 4.dp, color = ring, shape = CircleShape)
            .background(Color.Black.copy(alpha = 0.15f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(ring.copy(alpha = if (ready) 0.35f else 0.2f))
        )
    }
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

@Composable
private fun ConfirmFields(
    confirm: ConfirmState,
    onTrackingChange: (String) -> Unit,
    onCustomerNameChange: (String) -> Unit
) {
    val strings = LocalAppStrings.current
    // 矮栏用 BasicTextField：OutlinedTextField 固定 36dp 会把正文裁没，看起来像没填入。
    Column(modifier = Modifier.fillMaxWidth()) {
        CompactField(
            value = confirm.trackingNumber,
            onValueChange = onTrackingChange,
            placeholder = strings.dock_trackingLabel
        )
        CompactField(
            value = confirm.customerName,
            onValueChange = onCustomerNameChange,
            placeholder = strings.dock_customerName
        )
    }
}

@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val border = MaterialTheme.colorScheme.outline
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                inner()
            }
        }
    )
}
