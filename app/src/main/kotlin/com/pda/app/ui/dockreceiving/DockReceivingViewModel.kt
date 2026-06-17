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
            _uiState.update { it.copy(message = "Select a warehouse first") }
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
                            items = emptyList()
                        )
                    }
                    is NetworkResult.Error -> _uiState.update {
                        it.copy(isBusy = false, message = result.message)
                    }
                }
            }
        }
    }

    fun onPhotoCaptured(file: File) {
        // Inline confirm fields (no separate page). Retaking replaces the pending capture,
        // so delete the previous temp photo first to avoid leaking cache files.
        _uiState.value.confirm?.photoFile?.delete()
        _uiState.update {
            it.copy(confirm = ConfirmState(photoFile = file), recentlySaved = false)
        }
        viewModelScope.launch {
            val img = try {
                encoder.compress(file)
            } catch (e: Exception) {
                Log.e(TAG, "compress: ${e.message}", e)
                _uiState.update {
                    it.copy(confirm = it.confirm?.copy(uploading = false, analyzing = false, uploadFailed = true),
                        message = "Photo processing failed — retake")
                }
                return@launch
            }
            launch { runUpload(img.bytes, file.name) }
            launch { runAnalyze(img.base64) }
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
                    it.copy(confirm = it.confirm?.copy(uploading = false, uploadFailed = true), message = result.message)
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
                    // 校验 AI 返回的运单号；识别失败/返回乱码（N/A、提示语等）时视为空，
                    // 不写入字段，避免 Confirm 被错误启用。
                    val tracking = sanitizeTracking(result.data.trackingNumber)
                    val carrier = normalizeCarrier(result.data.carrier)
                    state.copy(
                        confirm = c.copy(
                            analyzing = false,
                            trackingNumber = if (tracking.isNotBlank()) tracking else c.trackingNumber,
                            carrier = if (carrier.isNotBlank()) carrier else c.carrier,
                            trackingAutoFilled = tracking.isNotBlank(),
                            carrierAutoFilled = carrier.isNotBlank(),
                            rawJson = result.data.raw
                        )
                    )
                }
                is NetworkResult.Error -> _uiState.update {
                    it.copy(confirm = it.confirm?.copy(analyzing = false), message = result.message)
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
        _uiState.update { it.copy(confirm = null) }
    }

    fun saveItem() {
        val state = _uiState.value
        val c = state.confirm ?: return
        val bid = state.batchId ?: return
        val photoPath = c.photoPath ?: return
        val tracking = c.trackingNumber.replace("\\s+".toRegex(), "")
        val req = CreateItemRequest(
            receivingBatchId = bid,
            trackingNumber = tracking.ifBlank { null },
            carrier = c.carrier.ifBlank { null },
            condition = c.condition.ifBlank { null },
            photoPath = photoPath,
            source = "AI",
            rawJson = c.rawJson,
            needsReview = tracking.isBlank()
        )
        _uiState.update { it.copy(confirm = it.confirm?.copy(saving = true)) }
        viewModelScope.launch {
            repo.createItem(req).collect { result ->
                when (result) {
                    is NetworkResult.Loading -> {}
                    is NetworkResult.Success -> {
                        c.photoFile.delete()
                        // "Saved" 显示在底部信息条（不再用会遮挡预览的 snackbar）。
                        _uiState.update { it.copy(confirm = null, recentlySaved = true) }
                        refreshItems(bid)
                    }
                    is NetworkResult.Error -> _uiState.update {
                        it.copy(confirm = it.confirm?.copy(saving = false), message = result.message)
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
                    is NetworkResult.Error -> _uiState.update { it.copy(message = result.message) }
                }
            }
        }
    }

    private suspend fun refreshItems(batchId: Int) {
        repo.getItems(batchId).collect { result ->
            if (result is NetworkResult.Success) _uiState.update { it.copy(items = result.data) }
            else if (result is NetworkResult.Error) _uiState.update { it.copy(message = result.message) }
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
                        DockReceivingUiState(message = "${number ?: "Batch"} closed")
                    }
                    is NetworkResult.Error -> _uiState.update {
                        it.copy(isBusy = false, showCloseDialog = false, message = result.message)
                    }
                }
            }
        }
    }

    fun messageShown() = _uiState.update { it.copy(message = null) }
}
