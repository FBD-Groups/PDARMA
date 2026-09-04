package com.pda.app.ui.batchdetail

import com.pda.app.data.api.model.ReceivingItemUi

sealed interface BatchDetailUiState {
    data object Loading : BatchDetailUiState
    data object Empty : BatchDetailUiState
    data class Success(
        val items: List<ReceivingItemUi>,
        /** 正在作废的行 id；非 null 时禁用该行按钮。 */
        val voidingItemId: Int? = null,
        val message: String? = null
    ) : BatchDetailUiState
    data class Error(val message: String) : BatchDetailUiState
}
