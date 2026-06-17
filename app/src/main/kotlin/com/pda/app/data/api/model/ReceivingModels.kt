package com.pda.app.data.api.model

import kotlinx.serialization.Serializable
import java.time.LocalDateTime

// ── Requests ──────────────────────────────────────────────────────────────────

@Serializable
data class CreateBatchRequest(val warehouseId: Int)

@Serializable
data class AnalyzeRequest(val mode: String, val photos: List<String>)

/**
 * 对齐 web ReceivingItemCreateRequest。可空字段默认 null：在共享 Json（encodeDefaults
 * 默认 false）下，null 字段不会被序列化，等价于 web 的 "trim 后空则不传"。
 */
@Serializable
data class CreateItemRequest(
    val receivingBatchId: Int,
    val trackingNumber: String? = null,
    val carrier: String? = null,
    val condition: String? = null,
    val photoPath: String,
    val source: String = "AI",
    val rawJson: String? = null,
    val needsReview: Boolean? = null
)

// ── Responses ─────────────────────────────────────────────────────────────────

@Serializable
data class CreateBatchResponse(val receivingBatchId: Int, val batchNumber: String)

@Serializable
data class UploadPhotosResponse(val urls: List<String> = emptyList())

@Serializable
data class ShippingAnalyzeResponse(
    val mode: String? = null,
    val trackingNumber: String? = null,
    val carrier: String? = null,
    val service: String? = null,
    val raw: String? = null
)

@Serializable
data class CreateItemResponse(val receivingItemId: Int)

@Serializable
data class CloseBatchResponse(val receivingBatchId: Int, val status: String)

@Serializable
data class ReceivingItemDto(
    val receivingItemId: Int,
    val trackingNo: String? = null,
    val carrier: String? = null,
    val needsReview: Boolean? = null
)

/** GET /api/receiving-batches 返回的批次行（Receive Report 用）。 */
@Serializable
data class ReceivingBatchDto(
    val receivingBatchId: Int,
    val batchNumber: String,
    val warehouseId: Int? = null,
    val status: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val createdUser: String? = null,
    val itemCount: Int = 0
)

// ── Clean domain models (UI never sees raw DTOs) ────────────────────────────────

data class BatchInfo(val batchId: Int, val batchNumber: String)

data class ShippingAnalysis(
    val trackingNumber: String?,
    val carrier: String?,
    val service: String?,
    val raw: String?
)

data class ReceivingItemUi(
    val receivingItemId: Int,
    val trackingNo: String,
    val carrier: String,
    val needsReview: Boolean
)

/** 一条已收货批次（Receive Report 用）。receivedAt = 后端 EndTime（关批时间）。 */
data class ReceivedBatch(
    val receivingBatchId: Int,
    val batchNumber: String,
    val receivedAt: LocalDateTime,
    val itemCount: Int
)
