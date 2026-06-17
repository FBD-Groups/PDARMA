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
}
