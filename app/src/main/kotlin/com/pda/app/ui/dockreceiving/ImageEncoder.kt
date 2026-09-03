package com.pda.app.ui.dockreceiving

import java.io.File

/** 压缩产物：上传用 bytes，AI 解析用 base64（无 data URL 前缀）。 */
data class CompressedImage(val bytes: ByteArray, val base64: String) {
    // ByteArray needs custom equals/hashCode for value semantics (used in tests/state).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CompressedImage) return false
        return bytes.contentEquals(other.bytes) && base64 == other.base64
    }
    override fun hashCode(): Int = 31 * bytes.contentHashCode() + base64.hashCode()
}

interface ImageEncoder {
    /**
     * 存档用：尽量保留拍照原图（不按 MAX_EDGE 裁边/缩边），对齐网页 `captureVideoFrameToFile`。
     */
    suspend fun prepareForUpload(file: File): ByteArray

    /** AI 解析用：降采样 → 缩放至最长边 MAX_EDGE → JPEG → bytes + base64。 */
    suspend fun compress(file: File): CompressedImage
}
