package com.pda.app.ui.batchdetail

import com.pda.app.data.api.model.ReceivingItemUi

sealed interface BatchDetailUiState {
    data object Loading : BatchDetailUiState
    data object Empty : BatchDetailUiState
    data class Success(val items: List<ReceivingItemUi>) : BatchDetailUiState
    data class Error(val message: String) : BatchDetailUiState
}
