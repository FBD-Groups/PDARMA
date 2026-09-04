package com.pda.app.ui.batchdetail

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pda.app.data.NetworkResult
import com.pda.app.data.repository.ReceivingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class BatchDetailViewModel @Inject constructor(
    private val repo: ReceivingRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private companion object {
        const val TAG = "PDA/BatchDetailViewModel"
    }

    private val batchId: Int? = savedStateHandle.get<String>("batchId")?.toIntOrNull()
    val batchNumber: String = savedStateHandle.get<String>("batchNumber").orEmpty()

    private val _uiState = MutableStateFlow<BatchDetailUiState>(BatchDetailUiState.Loading)
    val uiState: StateFlow<BatchDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        val id = batchId
        if (id == null) {
            _uiState.value = BatchDetailUiState.Error("无效的批次")
            return
        }
        repo.getItems(id)
            .onEach { result ->
                when (result) {
                    is NetworkResult.Loading -> _uiState.value = BatchDetailUiState.Loading
                    is NetworkResult.Success ->
                        _uiState.value = if (result.data.isEmpty()) BatchDetailUiState.Empty
                        else BatchDetailUiState.Success(result.data)
                    is NetworkResult.Error -> {
                        Log.w(TAG, "load failed: ${result.message}")
                        _uiState.value = BatchDetailUiState.Error(result.message)
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    fun messageShown() {
        _uiState.update { state ->
            when (state) {
                is BatchDetailUiState.Success -> state.copy(message = null)
                else -> state
            }
        }
    }

    /** 确认弹窗后调用：软作废成功则从列表移除。 */
    fun voidItem(receivingItemId: Int) {
        val current = _uiState.value
        if (current !is BatchDetailUiState.Success) return
        if (current.voidingItemId != null) return

        repo.voidItem(receivingItemId)
            .onEach { result ->
                when (result) {
                    is NetworkResult.Loading -> {
                        _uiState.update { state ->
                            if (state is BatchDetailUiState.Success) {
                                state.copy(voidingItemId = receivingItemId, message = null)
                            } else state
                        }
                    }
                    is NetworkResult.Success -> {
                        _uiState.update { state ->
                            if (state !is BatchDetailUiState.Success) return@update state
                            val remaining = state.items.filter { it.receivingItemId != receivingItemId }
                            if (remaining.isEmpty()) BatchDetailUiState.Empty
                            else state.copy(items = remaining, voidingItemId = null, message = null)
                        }
                    }
                    is NetworkResult.Error -> {
                        Log.w(TAG, "void failed: ${result.message}")
                        _uiState.update { state ->
                            if (state is BatchDetailUiState.Success) {
                                state.copy(voidingItemId = null, message = result.message)
                            } else state
                        }
                    }
                }
            }
            .launchIn(viewModelScope)
    }
}
