package com.pda.app.ui.dockreceiving

import com.pda.app.data.api.model.ReceivingItemUi
import java.io.File

enum class Phase { Idle, Recording }

/** Start Batch 时选择的录入方式。Picture = 拍照识别；BarcodeScan = 条码扫描。标签在 UI 层按语言取。 */
enum class InputMethod {
    Picture,
    BarcodeScan
}

/**
 * 一次性提示消息。翻译在 Compose 层完成（ViewModel 不可读 CompositionLocal）：
 * VM 发出标记，由屏幕用 LocalAppStrings 映射成文本。[Text] 承载后端原始错误（不翻译）。
 */
sealed interface DockMessage {
    data class Text(val value: String) : DockMessage
    data object SelectWarehouseFirst : DockMessage
    data object PhotoProcessingFailed : DockMessage
    data class BatchClosed(val number: String) : DockMessage
}

/**
 * 单条 label 的录入草稿。**进入录货即存在**（Tracking # 框常驻），照片可选：
 * 可以先手输/扫码运单号，也可以拍照让 AI 识别填入同一草稿。
 */
data class ConfirmState(
    val photoFile: File? = null,          // 拍照后填入；为 null 表示尚未拍照（纯手输）
    val uploading: Boolean = false,
    val analyzing: Boolean = false,
    val photoPath: String? = null,        // 上传成功后填入
    val uploadFailed: Boolean = false,
    val trackingNumber: String = "",
    val carrier: String = "",
    val condition: String = "",
    val rawJson: String? = null,
    val trackingAutoFilled: Boolean = false,
    val carrierAutoFilled: Boolean = false,
    val saving: Boolean = false
) {
    /**
     * 可保存：有运单号、未在上传/保存中；若拍了照则必须等上传完成（拿到 photoPath），
     * 没拍照（纯手输）则直接可存。
     */
    val canSave: Boolean
        get() = trackingNumber.isNotBlank() && !uploading && !saving &&
            (photoFile == null || photoPath != null)
}

data class DockReceivingUiState(
    val phase: Phase = Phase.Idle,
    val inputMethod: InputMethod = InputMethod.Picture,
    val batchId: Int? = null,
    val batchNumber: String? = null,
    val items: List<ReceivingItemUi> = emptyList(),
    val confirm: ConfirmState? = null,
    val isBusy: Boolean = false,          // batch-level op (start/close/refresh) in flight
    val showCloseDialog: Boolean = false,
    val message: DockMessage? = null,     // one-shot snackbar marker; cleared via messageShown()
    val recentlySaved: Boolean = false    // shows "Saved" in the bottom status bar until next capture
) {
    val itemCount: Int get() = items.size
    val needsReviewCount: Int get() = items.count { it.needsReview }
}
