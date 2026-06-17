package com.pda.app.ui.dockreceiving

import com.pda.app.data.api.model.ReceivingItemUi
import java.io.File

enum class Phase { Idle, Recording, Confirming }

/** 单条 label 确认页的状态。 */
data class ConfirmState(
    val photoFile: File,
    val uploading: Boolean = true,
    val analyzing: Boolean = true,
    val photoPath: String? = null,        // 上传成功后填入；为 null 时不可保存
    val uploadFailed: Boolean = false,
    val trackingNumber: String = "",
    val carrier: String = "",
    val condition: String = "",
    val rawJson: String? = null,
    val trackingAutoFilled: Boolean = false,
    val carrierAutoFilled: Boolean = false,
    val saving: Boolean = false
) {
    val canSave: Boolean get() = photoPath != null && !uploading && !saving
}

data class DockReceivingUiState(
    val phase: Phase = Phase.Idle,
    val batchId: Int? = null,
    val batchNumber: String? = null,
    val items: List<ReceivingItemUi> = emptyList(),
    val confirm: ConfirmState? = null,
    val isBusy: Boolean = false,          // batch-level op (start/close/refresh) in flight
    val showCloseDialog: Boolean = false,
    val message: String? = null           // one-shot snackbar text; cleared via messageShown()
) {
    val itemCount: Int get() = items.size
    val needsReviewCount: Int get() = items.count { it.needsReview }
}
