package com.pda.app.ui.dockreceiving

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pda.app.data.NetworkResult
import com.pda.app.data.api.model.ActiveCustomer
import com.pda.app.data.api.model.CreateItemRequest
import com.pda.app.data.prefs.UserPreferences
import com.pda.app.data.repository.CustomerRepository
import com.pda.app.data.repository.ReceivingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DockReceivingViewModel @Inject constructor(
    private val repo: ReceivingRepository,
    private val customerRepo: CustomerRepository,
    private val encoder: ImageEncoder,
    private val barcodeDecoder: BarcodeDecoder,
    private val soundPlayer: DockSoundPlayer,
    private val prefs: UserPreferences,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "PDA/DockReceivingViewModel"
    }

    private val warehouseId: Int? =
        savedStateHandle.get<String>("warehouseId")?.toIntOrNull()

    /** 对齐 web：进页拉活跃客户，用 UF 编码解析真实客户名。 */
    private var activeCustomers: List<ActiveCustomer> = emptyList()

    private val _uiState = MutableStateFlow(DockReceivingUiState())
    val uiState: StateFlow<DockReceivingUiState> = _uiState.asStateFlow()

    /** 持久化记住的录入方式（默认 Picture）。 */
    val inputMethod: StateFlow<InputMethod> = prefs.dockInputMethod
        .map { name -> InputMethod.entries.firstOrNull { it.name == name } ?: InputMethod.Picture }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InputMethod.Picture)

    fun setInputMethod(method: InputMethod) {
        viewModelScope.launch { prefs.setDockInputMethod(method.name) }
    }

    fun startBatch(method: InputMethod = InputMethod.Picture) {
        val wid = warehouseId
        if (wid == null) {
            _uiState.update { it.copy(message = DockMessage.SelectWarehouseFirst) }
            return
        }
        viewModelScope.launch {
            repo.createBatch(wid).collect { result ->
                when (result) {
                    is NetworkResult.Loading -> _uiState.update { it.copy(isBusy = true) }
                    is NetworkResult.Success -> {
                        refreshActiveCustomers()
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                phase = Phase.Recording,
                                inputMethod = method,
                                batchId = result.data.batchId,
                                batchNumber = result.data.batchNumber,
                                items = emptyList(),
                                // 拍照模式：进入即给一个空草稿，Tracking # 框常驻可见可输。
                                confirm = if (method == InputMethod.Picture) ConfirmState() else null
                            )
                        }
                    }
                    is NetworkResult.Error -> _uiState.update {
                        it.copy(isBusy = false, message = DockMessage.Text(result.message))
                    }
                }
            }
        }
    }

    fun onPhotoCaptured(file: File) {
        // 重拍替换上一张待处理照片，先删旧临时文件避免缓存泄漏。
        // 保留用户可能已手输的运单号/承运商，仅把照片与上传/识别状态挂上当前草稿。
        _uiState.value.confirm?.photoFile?.delete()
        _uiState.update {
            val prev = it.confirm ?: ConfirmState()
            // 新照片：清掉上一张的自动识别结果（条码/AI），保留用户手输的运单号。
            val keepTyped = if (prev.trackingAutoFilled) "" else prev.trackingNumber
            val keepCustomer = if (prev.customerAutoFilled) "" else prev.customerName
            val keepCustomerId = if (prev.customerAutoFilled) null else prev.customerId
            it.copy(
                confirm = prev.copy(
                    photoFile = file,
                    uploading = true,
                    analyzing = true,
                    photoPath = null,
                    uploadFailed = false,
                    barcodeDecoding = true,
                    barcodeTracking = null,
                    trackingFromBarcode = false,
                    trackingNumber = keepTyped,
                    trackingAutoFilled = false,
                    customerName = keepCustomer,
                    customerId = keepCustomerId,
                    customerAutoFilled = false,
                    carrier = "",
                    carrierAutoFilled = false,
                    autoSubmitConsumed = false,
                    pendingDuplicateTracking = null,
                    rawJson = null
                ),
                captureStatus = CaptureStatus.Idle
            )
        }
        // 条码解码走原始全分辨率照片；上传走原图（不裁不缩边）；AI 走 MAX_EDGE 压缩图。
        viewModelScope.launch { runBarcode(file) }
        viewModelScope.launch {
            try {
                val uploadBytes = encoder.prepareForUpload(file)
                runUpload(uploadBytes, file.name)
            } catch (e: Exception) {
                Log.e(TAG, "prepareForUpload: ${e.message}", e)
                soundPlayer.playBeep()
                _uiState.update {
                    it.copy(
                        confirm = it.confirm?.copy(uploading = false, uploadFailed = true),
                        message = DockMessage.PhotoProcessingFailed,
                        captureStatus = CaptureStatus.Failure
                    )
                }
            }
        }
        viewModelScope.launch {
            try {
                val img = encoder.compress(file)
                runAnalyze(img.base64)
            } catch (e: Exception) {
                Log.e(TAG, "compress: ${e.message}", e)
                soundPlayer.playBeep()
                _uiState.update {
                    it.copy(
                        confirm = it.confirm?.copy(analyzing = false),
                        message = DockMessage.PhotoProcessingFailed,
                        captureStatus = CaptureStatus.Failure
                    )
                }
            }
        }
    }

    /** 本地解出运单号条码：立刻写入 Tracking 栏（不等 AI）；AI 返回后仍以条码为准合并。 */
    private suspend fun runBarcode(file: File) {
        val tracking = barcodeDecoder.decodeTracking(file)
        _uiState.update { state ->
            val c = state.confirm ?: return@update state
            if (tracking.isNullOrBlank()) {
                state.copy(confirm = c.copy(barcodeDecoding = false))
            } else {
                state.copy(
                    confirm = c.copy(
                        barcodeDecoding = false,
                        barcodeTracking = tracking,
                        trackingNumber = tracking,
                        trackingAutoFilled = true,
                        trackingFromBarcode = true
                    )
                )
            }
        }
        maybeAutoSubmit()
    }

    private suspend fun runUpload(bytes: ByteArray, filename: String) {
        repo.uploadPhoto(bytes, filename).collect { result ->
            when (result) {
                is NetworkResult.Loading -> {}
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(confirm = it.confirm?.copy(uploading = false, photoPath = result.data, uploadFailed = false))
                    }
                    maybeAutoSubmit()
                }
                is NetworkResult.Error -> {
                    soundPlayer.playBeep()
                    _uiState.update {
                        it.copy(
                            confirm = it.confirm?.copy(uploading = false, uploadFailed = true),
                            message = DockMessage.Text(result.message),
                            captureStatus = CaptureStatus.Failure
                        )
                    }
                }
            }
        }
    }

    private suspend fun runAnalyze(base64: String) {
        repo.analyzeShipping(base64).collect { result ->
            when (result) {
                is NetworkResult.Loading -> {}
                is NetworkResult.Success -> {
                    var noTracking = false
                    _uiState.update { state ->
                        val c = state.confirm ?: return@update state
                        val carrier = normalizeCarrier(result.data.carrier)
                        // sanitize 已把 FedEx 96 长码收成末 12 位
                        val aiTracking = sanitizeTracking(result.data.trackingNumber)
                        val (resolvedId, resolvedName) = resolveCustomerFromAnalyze(
                            result.data.customerCode,
                            result.data.customerName,
                            activeCustomers
                        )
                        val fromBarcode = c.barcodeTracking != null
                        // barcodeTracking 已经过 sanitize（含 FedEx 短码）
                        val merged = c.barcodeTracking
                            ?: aiTracking.ifBlank { shortenFedExTracking(c.trackingNumber.replace("\\s+".toRegex(), "")) }
                        val resolvedCarrier = when {
                            carrier.isNotBlank() -> carrier
                            wasFedExLongBarcode(result.data.trackingNumber) -> "FedEx"
                            else -> c.carrier
                        }
                        noTracking = merged.isBlank()
                        state.copy(
                            confirm = c.copy(
                                analyzing = false,
                                trackingNumber = merged,
                                carrier = if (resolvedCarrier.isNotBlank()) resolvedCarrier else c.carrier,
                                customerName = when {
                                    resolvedName.isNotBlank() -> resolvedName
                                    else -> c.customerName
                                },
                                customerId = when {
                                    resolvedName.isNotBlank() -> resolvedId
                                    else -> c.customerId
                                },
                                trackingAutoFilled = fromBarcode || aiTracking.isNotBlank(),
                                carrierAutoFilled = resolvedCarrier.isNotBlank(),
                                customerAutoFilled = resolvedName.isNotBlank(),
                                trackingFromBarcode = fromBarcode,
                                rawJson = result.data.raw
                            ),
                            message = if (noTracking) DockMessage.TrackingNotRecognized else state.message,
                            captureStatus = if (noTracking) CaptureStatus.Failure else state.captureStatus
                        )
                    }
                    if (noTracking) soundPlayer.playBeep()
                    maybeAutoSubmit()
                }
                is NetworkResult.Error -> {
                    var playedBeep = false
                    _uiState.update { state ->
                        val c = state.confirm ?: return@update state
                        if (c.barcodeTracking != null) {
                            state.copy(confirm = c.copy(
                                analyzing = false,
                                trackingNumber = c.barcodeTracking,
                                trackingAutoFilled = true,
                                trackingFromBarcode = true
                            ))
                        } else {
                            playedBeep = true
                            state.copy(
                                confirm = c.copy(analyzing = false),
                                message = DockMessage.Text(result.message),
                                captureStatus = CaptureStatus.Failure
                            )
                        }
                    }
                    if (playedBeep) soundPlayer.playBeep()
                    maybeAutoSubmit()
                }
            }
        }
    }

    fun onTrackingChanged(v: String) =
        _uiState.update {
            it.copy(confirm = it.confirm?.copy(
                trackingNumber = v,
                trackingAutoFilled = false,
                autoSubmitConsumed = true
            ))
        }

    fun onCarrierChanged(v: String) =
        _uiState.update { it.copy(confirm = it.confirm?.copy(carrier = v, carrierAutoFilled = false)) }

    fun onCustomerNameChanged(v: String) =
        _uiState.update { it.copy(confirm = it.confirm?.copy(customerName = v, customerId = null, customerAutoFilled = false)) }

    fun onConditionChanged(v: String) =
        _uiState.update { it.copy(confirm = it.confirm?.copy(condition = v)) }

    fun cancelConfirm() {
        _uiState.value.confirm?.photoFile?.delete()
        _uiState.update { it.copy(confirm = ConfirmState()) }
    }

    fun dismissDuplicateSave() {
        _uiState.update { it.copy(confirm = it.confirm?.copy(pendingDuplicateTracking = null)) }
    }

    fun confirmDuplicateSave() {
        _uiState.update { it.copy(confirm = it.confirm?.copy(pendingDuplicateTracking = null)) }
        performSave()
    }

    /** 识别流水线结束后，若具备条件则自动查重并入库（每张照片最多一次）。 */
    private fun maybeAutoSubmit() {
        var shouldSubmit = false
        _uiState.update { state ->
            val c = state.confirm ?: return@update state
            if (!c.readyForAutoSubmit) return@update state
            shouldSubmit = true
            state.copy(confirm = c.copy(autoSubmitConsumed = true))
        }
        if (shouldSubmit) beginSaveWithDuplicateCheck()
    }

    fun saveItem() {
        val c = _uiState.value.confirm ?: return
        if (!c.canSave && c.trackingNumber.isBlank() && c.photoPath == null) return
        _uiState.update { it.copy(confirm = it.confirm?.copy(autoSubmitConsumed = true)) }
        beginSaveWithDuplicateCheck()
    }

    private fun beginSaveWithDuplicateCheck() {
        val state = _uiState.value
        val c = state.confirm ?: return
        val tracking = shortenFedExTracking(c.trackingNumber.replace("\\s+".toRegex(), ""))
        if (tracking.isBlank() && c.photoPath == null) return
        if (tracking.isBlank()) {
            // 无运单号：跳过查重，直接存（needsReview）。
            performSave()
            return
        }
        viewModelScope.launch {
            repo.isDuplicateTracking(tracking).collect { result ->
                when (result) {
                    is NetworkResult.Loading -> {}
                    is NetworkResult.Success -> {
                        if (result.data) {
                            soundPlayer.playBeep()
                            _uiState.update {
                                val cur = it.confirm
                                it.copy(
                                    confirm = cur?.copy(
                                        // 弹窗时确保顶栏已写入运单号（避免只在 chip 可见）。
                                        trackingNumber = tracking.ifBlank { cur.trackingNumber },
                                        trackingAutoFilled = true,
                                        pendingDuplicateTracking = tracking
                                    )
                                )
                            }
                        } else {
                            performSave()
                        }
                    }
                    is NetworkResult.Error -> performSave() // 不应发生；repo 已吞异常
                }
            }
        }
    }

    private fun performSave() {
        val state = _uiState.value
        val c = state.confirm ?: return
        val bid = state.batchId ?: return
        val tracking = shortenFedExTracking(c.trackingNumber.replace("\\s+".toRegex(), ""))
        if (tracking.isBlank() && c.photoPath == null) return
        val customer = c.customerName.trim()
        val req = CreateItemRequest(
            receivingBatchId = bid,
            trackingNumber = tracking.ifBlank { null },
            carrier = c.carrier.ifBlank { null },
            customerId = c.customerId,
            customerName = customer.ifBlank { null },
            condition = c.condition.ifBlank { null },
            photoPaths = c.photoPath?.let { listOf(it) },
            source = if (c.trackingFromBarcode) "Barcode" else "AI",
            rawJson = c.rawJson,
            needsReview = tracking.isBlank()
        )
        _uiState.update { it.copy(confirm = it.confirm?.copy(saving = true)) }
        viewModelScope.launch {
            repo.createItem(req).collect { result ->
                when (result) {
                    is NetworkResult.Loading -> {}
                    is NetworkResult.Success -> {
                        soundPlayer.playSuccess()
                        c.photoFile?.delete()
                        // 对齐 web：入库后保留 Tracking / Customer 展示，下次拍照再清空替换。
                        _uiState.update {
                            it.copy(
                                confirm = ConfirmState(
                                    trackingNumber = tracking.ifBlank { c.trackingNumber },
                                    carrier = c.carrier,
                                    customerName = c.customerName,
                                    customerId = c.customerId,
                                    condition = c.condition,
                                    trackingAutoFilled = c.trackingAutoFilled,
                                    carrierAutoFilled = c.carrierAutoFilled,
                                    customerAutoFilled = c.customerAutoFilled,
                                    trackingFromBarcode = c.trackingFromBarcode,
                                    autoSubmitConsumed = true
                                ),
                                captureStatus = CaptureStatus.Success
                            )
                        }
                        refreshItems(bid)
                    }
                    is NetworkResult.Error -> {
                        soundPlayer.playBeep()
                        _uiState.update {
                            it.copy(
                                confirm = it.confirm?.copy(saving = false),
                                message = DockMessage.Text(result.message),
                                captureStatus = CaptureStatus.Failure
                            )
                        }
                    }
                }
            }
        }
    }

    /** 扫码模式：直接用运单号建条目（无照片，source=Barcode），成功后刷新列表。 */
    fun scanItem(tracking: String) {
        val compact = tracking.replace("\\s+".toRegex(), "")
        val t = shortenFedExTracking(sanitizeTracking(compact).ifBlank { compact })
        if (t.isBlank()) return
        val bid = _uiState.value.batchId ?: return
        val carrier = if (wasFedExLongBarcode(compact)) "FedEx" else null
        viewModelScope.launch {
            repo.isDuplicateTracking(t).collect { result ->
                when (result) {
                    is NetworkResult.Loading -> {}
                    is NetworkResult.Success -> {
                        if (result.data) {
                            soundPlayer.playBeep()
                            _uiState.update {
                                it.copy(
                                    confirm = ConfirmState(
                                        trackingNumber = t,
                                        carrier = carrier.orEmpty(),
                                        trackingFromBarcode = true,
                                        pendingDuplicateTracking = t,
                                        autoSubmitConsumed = true
                                    )
                                )
                            }
                        } else {
                            createScanItem(bid, t, carrier)
                        }
                    }
                    is NetworkResult.Error -> createScanItem(bid, t, carrier)
                }
            }
        }
    }

    private fun createScanItem(bid: Int, tracking: String, carrier: String? = null) {
        val req = CreateItemRequest(
            receivingBatchId = bid,
            trackingNumber = tracking,
            carrier = carrier,
            photoPaths = null,
            source = "Barcode",
            needsReview = false
        )
        viewModelScope.launch {
            repo.createItem(req).collect { result ->
                when (result) {
                    is NetworkResult.Loading -> {}
                    is NetworkResult.Success -> {
                        soundPlayer.playSuccess()
                        _uiState.update { it.copy(captureStatus = CaptureStatus.Success, confirm = null) }
                        refreshItems(bid)
                    }
                    is NetworkResult.Error -> {
                        soundPlayer.playBeep()
                        _uiState.update {
                            it.copy(
                                message = DockMessage.Text(result.message),
                                captureStatus = CaptureStatus.Failure
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun refreshItems(batchId: Int) {
        repo.getItems(batchId).collect { result ->
            if (result is NetworkResult.Success) _uiState.update { it.copy(items = result.data) }
            else if (result is NetworkResult.Error) _uiState.update { it.copy(message = DockMessage.Text(result.message)) }
        }
    }

    /** 对齐 web：开批时拉活跃客户；失败降级为空列表（仍可手填客户名）。 */
    private suspend fun refreshActiveCustomers() {
        customerRepo.getActiveCustomers().collect { result ->
            when (result) {
                is NetworkResult.Loading -> {}
                is NetworkResult.Success -> activeCustomers = result.data
                is NetworkResult.Error -> {
                    Log.w(TAG, "refreshActiveCustomers: ${result.message}")
                    activeCustomers = emptyList()
                }
            }
        }
    }

    fun confirmCloseBatch() {
        val bid = _uiState.value.batchId ?: return
        val number = _uiState.value.batchNumber
        viewModelScope.launch {
            repo.closeBatch(bid).collect { result ->
                when (result) {
                    is NetworkResult.Loading -> _uiState.update { it.copy(isBusy = true) }
                    is NetworkResult.Success -> _uiState.update {
                        DockReceivingUiState(message = DockMessage.BatchClosed(number.orEmpty()))
                    }
                    is NetworkResult.Error -> _uiState.update {
                        it.copy(isBusy = false, message = DockMessage.Text(result.message))
                    }
                }
            }
        }
    }

    fun messageShown() = _uiState.update { it.copy(message = null) }
}
