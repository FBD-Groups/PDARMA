package com.pda.app.ui.dockreceiving

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pda.app.data.NetworkResult
import com.pda.app.data.api.model.CreateItemRequest
import com.pda.app.data.prefs.UserPreferences
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
    private val encoder: ImageEncoder,
    private val barcodeDecoder: BarcodeDecoder,
    private val prefs: UserPreferences,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "PDA/DockReceivingViewModel"
    }

    private val warehouseId: Int? =
        savedStateHandle.get<String>("warehouseId")?.toIntOrNull()

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
                    is NetworkResult.Success -> _uiState.update {
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
                    trackingAutoFilled = false
                ),
                recentlySaved = false
            )
        }
        // 条码解码走原始全分辨率照片（最准）；上传/AI 走压缩图。三路并行。
        viewModelScope.launch { runBarcode(file) }
        viewModelScope.launch {
            val img = try {
                encoder.compress(file)
            } catch (e: Exception) {
                Log.e(TAG, "compress: ${e.message}", e)
                _uiState.update {
                    it.copy(confirm = it.confirm?.copy(uploading = false, analyzing = false, uploadFailed = true),
                        message = DockMessage.PhotoProcessingFailed)
                }
                return@launch
            }
            launch { runUpload(img.bytes, file.name) }
            launch { runAnalyze(img.base64) }
        }
    }

    /** 本地解出运单号条码，暂存到草稿；若 AI 已返回则立即覆盖填入（条码权威）。 */
    private suspend fun runBarcode(file: File) {
        val tracking = barcodeDecoder.decodeTracking(file)
        _uiState.update { state ->
            val c = state.confirm ?: return@update state
            if (tracking.isNullOrBlank()) {
                // 未找到条码：仅清解码中标志，barcodeTracking 保持 null（overlay 显示"未找到"）。
                state.copy(confirm = c.copy(barcodeDecoding = false))
            } else {
                val applyNow = !c.analyzing  // AI 已结束 → 条码晚到，直接覆盖
                state.copy(
                    confirm = c.copy(
                        barcodeDecoding = false,
                        barcodeTracking = tracking,
                        trackingNumber = if (applyNow) tracking else c.trackingNumber,
                        trackingAutoFilled = if (applyNow) true else c.trackingAutoFilled,
                        trackingFromBarcode = if (applyNow) true else c.trackingFromBarcode
                    )
                )
            }
        }
    }

    private suspend fun runUpload(bytes: ByteArray, filename: String) {
        repo.uploadPhoto(bytes, filename).collect { result ->
            when (result) {
                is NetworkResult.Loading -> {}
                is NetworkResult.Success -> _uiState.update {
                    it.copy(confirm = it.confirm?.copy(uploading = false, photoPath = result.data, uploadFailed = false))
                }
                is NetworkResult.Error -> _uiState.update {
                    it.copy(confirm = it.confirm?.copy(uploading = false, uploadFailed = true), message = DockMessage.Text(result.message))
                }
            }
        }
    }

    private suspend fun runAnalyze(base64: String) {
        repo.analyzeShipping(base64).collect { result ->
            when (result) {
                is NetworkResult.Loading -> {}
                is NetworkResult.Success -> _uiState.update { state ->
                    val c = state.confirm ?: return@update state
                    // 优先级：条码 > AI 识别 > 已有字段（用户手输）。条码权威。
                    // AI 运单号校验失败/乱码（N/A、unreadable、提示语等）视为空。
                    val aiTracking = sanitizeTracking(result.data.trackingNumber)
                    val carrier = normalizeCarrier(result.data.carrier)
                    val fromBarcode = c.barcodeTracking != null
                    val merged = c.barcodeTracking ?: aiTracking.ifBlank { c.trackingNumber }
                    state.copy(
                        confirm = c.copy(
                            analyzing = false,
                            trackingNumber = merged,
                            carrier = if (carrier.isNotBlank()) carrier else c.carrier,
                            trackingAutoFilled = fromBarcode || aiTracking.isNotBlank(),
                            carrierAutoFilled = carrier.isNotBlank(),
                            trackingFromBarcode = fromBarcode,
                            rawJson = result.data.raw
                        ),
                        // 仍无运单号（条码没解出、AI 也看不清，且没手输）→ 提示重拍或手输。
                        message = if (merged.isBlank()) DockMessage.TrackingNotRecognized else state.message
                    )
                }
                is NetworkResult.Error -> _uiState.update { state ->
                    val c = state.confirm ?: return@update state
                    if (c.barcodeTracking != null) {
                        // AI 失败但条码成功 → 用条码兜底填入，不报错（照片没白拍）。
                        state.copy(confirm = c.copy(
                            analyzing = false,
                            trackingNumber = c.barcodeTracking,
                            trackingAutoFilled = true,
                            trackingFromBarcode = true
                        ))
                    } else {
                        state.copy(confirm = c.copy(analyzing = false), message = DockMessage.Text(result.message))
                    }
                }
            }
        }
    }

    fun onTrackingChanged(v: String) =
        _uiState.update { it.copy(confirm = it.confirm?.copy(trackingNumber = v, trackingAutoFilled = false)) }

    fun onCarrierChanged(v: String) =
        _uiState.update { it.copy(confirm = it.confirm?.copy(carrier = v, carrierAutoFilled = false)) }

    fun onConditionChanged(v: String) =
        _uiState.update { it.copy(confirm = it.confirm?.copy(condition = v)) }

    fun cancelConfirm() {
        _uiState.value.confirm?.photoFile?.delete()
        // 清空回空草稿（Tracking # 框常驻），而非整段移除。
        _uiState.update { it.copy(confirm = ConfirmState()) }
    }

    fun saveItem() {
        val state = _uiState.value
        val c = state.confirm ?: return
        val bid = state.batchId ?: return
        val tracking = c.trackingNumber.replace("\\s+".toRegex(), "")
        if (tracking.isBlank() && c.photoPath == null) return  // 无运单号也无照片，无可保存内容
        val req = CreateItemRequest(
            receivingBatchId = bid,
            trackingNumber = tracking.ifBlank { null },
            carrier = c.carrier.ifBlank { null },
            condition = c.condition.ifBlank { null },
            photoPath = c.photoPath,        // 可为 null（纯手输，无照片）
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
                        c.photoFile?.delete()
                        // 重置为空草稿：Tracking # 框继续常驻，可录下一单。
                        _uiState.update { it.copy(confirm = ConfirmState(), recentlySaved = true) }
                        refreshItems(bid)
                    }
                    is NetworkResult.Error -> _uiState.update {
                        it.copy(confirm = it.confirm?.copy(saving = false), message = DockMessage.Text(result.message))
                    }
                }
            }
        }
    }

    /** 扫码模式：直接用运单号建条目（无照片，source=Barcode），成功后刷新列表。 */
    fun scanItem(tracking: String) {
        val t = tracking.replace("\\s+".toRegex(), "")
        if (t.isBlank()) return
        val bid = _uiState.value.batchId ?: return
        val req = CreateItemRequest(
            receivingBatchId = bid,
            trackingNumber = t,
            photoPath = null,
            source = "Barcode",
            needsReview = false
        )
        viewModelScope.launch {
            repo.createItem(req).collect { result ->
                when (result) {
                    is NetworkResult.Loading -> {}
                    is NetworkResult.Success -> {
                        _uiState.update { it.copy(recentlySaved = true) }
                        refreshItems(bid)
                    }
                    is NetworkResult.Error -> _uiState.update { it.copy(message = DockMessage.Text(result.message)) }
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

    fun requestCloseBatch() = _uiState.update { it.copy(showCloseDialog = true) }
    fun dismissCloseDialog() = _uiState.update { it.copy(showCloseDialog = false) }

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
                        it.copy(isBusy = false, showCloseDialog = false, message = DockMessage.Text(result.message))
                    }
                }
            }
        }
    }

    fun messageShown() = _uiState.update { it.copy(message = null) }
}
