package com.pda.app.ui.dockreceiving

import com.pda.app.data.api.model.ReceivingItemUi
import java.io.File

enum class Phase { Idle, Recording }

/** Start Batch 时选择的录入方式。Picture = 拍照识别（现有流程）；BarcodeScan = 条码扫描（开发中）。 */
enum class InputMethod(val label: String) {
    Picture("Picture"),
    BarcodeScan("Barcode Scan")
}

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
    /** 可保存：照片已上传、未在上传/保存中，且运单号非空（自动识别或手工输入均可）。 */
    val canSave: Boolean
        get() = photoPath != null && !uploading && !saving && trackingNumber.isNotBlank()
}

data class DockReceivingUiState(
    val phase: Phase = Phase.Idle,
    val batchId: Int? = null,
    val batchNumber: String? = null,
    val items: List<ReceivingItemUi> = emptyList(),
    val confirm: ConfirmState? = null,
    val isBusy: Boolean = false,          // batch-level op (start/close/refresh) in flight
    val showCloseDialog: Boolean = false,
    val message: String? = null,          // one-shot snackbar text; cleared via messageShown()
    val recentlySaved: Boolean = false    // shows "Saved" in the bottom status bar until next capture
) {
    val itemCount: Int get() = items.size
    val needsReviewCount: Int get() = items.count { it.needsReview }
}
