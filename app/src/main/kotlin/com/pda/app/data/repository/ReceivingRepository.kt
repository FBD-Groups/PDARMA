package com.pda.app.data.repository

import android.util.Log
import com.pda.app.data.NetworkResult
import com.pda.app.data.api.ReceivingApiService
import com.pda.app.data.api.model.AnalyzeRequest
import com.pda.app.data.api.model.BatchInfo
import com.pda.app.data.api.model.CreateBatchRequest
import com.pda.app.data.api.model.CreateItemRequest
import com.pda.app.data.api.model.ReceivedBatch
import com.pda.app.data.api.model.ReceivingItemUi
import com.pda.app.data.api.model.ShippingAnalysis
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class ReceivingRepository @Inject constructor(
    private val api: ReceivingApiService
) {
    companion object {
        private const val TAG = "PDA/ReceivingRepository"
        private const val NETWORK_FAIL = "Network error, please check your connection"

        /**
         * 后端 LocalDateTimeJsonConverter 序列化为 "yyyy-MM-dd HH:mm:ss"（空格分隔，无 'T'），
         * 这里同时兼容 ISO 的 'T' 分隔，避免后端格式变化时再次踩坑。
         */
        private val BATCH_TIME_FORMAT: DateTimeFormatter = DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd")
            .optionalStart().appendLiteral(' ').optionalEnd()
            .optionalStart().appendLiteral('T').optionalEnd()
            .appendPattern("HH:mm:ss")
            .toFormatter()
    }

    open fun createBatch(warehouseId: Int): Flow<NetworkResult<BatchInfo>> = flow {
        emit(NetworkResult.Loading)
        try {
            val resp = api.createBatch(CreateBatchRequest(warehouseId))
            if (resp.isSuccessful && resp.body() != null) {
                val b = resp.body()!!
                emit(NetworkResult.Success(BatchInfo(b.receivingBatchId, b.batchNumber)))
            } else {
                emit(errorFrom(resp, "Failed to create batch"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "createBatch: ${e.message}", e)
            emit(NetworkResult.Error(e.message ?: NETWORK_FAIL))
        }
    }.flowOn(Dispatchers.IO)

    open fun uploadPhoto(bytes: ByteArray, filename: String): Flow<NetworkResult<String>> = flow {
        emit(NetworkResult.Loading)
        try {
            val body = bytes.toRequestBody("image/jpeg".toMediaType())
            val part = MultipartBody.Part.createFormData("files", filename, body)
            val resp = api.uploadPhotos(part)
            if (resp.isSuccessful && resp.body() != null) {
                val url = resp.body()!!.urls.firstOrNull()
                if (url.isNullOrBlank()) emit(NetworkResult.Error("Photo upload failed: no URL returned"))
                else emit(NetworkResult.Success(url))
            } else {
                emit(errorFrom(resp, "Photo upload failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadPhoto: ${e.message}", e)
            emit(NetworkResult.Error(e.message ?: NETWORK_FAIL))
        }
    }.flowOn(Dispatchers.IO)

    open fun analyzeShipping(base64: String): Flow<NetworkResult<ShippingAnalysis>> = flow {
        emit(NetworkResult.Loading)
        try {
            val resp = api.analyze(AnalyzeRequest(mode = "shipping", photos = listOf(base64)))
            if (resp.isSuccessful && resp.body() != null) {
                val a = resp.body()!!
                emit(NetworkResult.Success(ShippingAnalysis(a.trackingNumber, a.carrier, a.service, a.raw)))
            } else {
                emit(errorFrom(resp, "AI analysis failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "analyzeShipping: ${e.message}", e)
            emit(NetworkResult.Error(e.message ?: NETWORK_FAIL))
        }
    }.flowOn(Dispatchers.IO)

    open fun createItem(req: CreateItemRequest): Flow<NetworkResult<Int>> = flow {
        emit(NetworkResult.Loading)
        try {
            val resp = api.createItem(req)
            if (resp.isSuccessful && resp.body() != null) {
                emit(NetworkResult.Success(resp.body()!!.receivingItemId))
            } else {
                emit(errorFrom(resp, "Failed to save item"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "createItem: ${e.message}", e)
            emit(NetworkResult.Error(e.message ?: NETWORK_FAIL))
        }
    }.flowOn(Dispatchers.IO)

    open fun getItems(batchId: Int): Flow<NetworkResult<List<ReceivingItemUi>>> = flow {
        emit(NetworkResult.Loading)
        try {
            val resp = api.getItems(batchId)
            if (resp.isSuccessful && resp.body() != null) {
                val items = resp.body()!!.map {
                    ReceivingItemUi(
                        receivingItemId = it.receivingItemId,
                        trackingNo = it.trackingNo.orEmpty(),
                        carrier = it.carrier.orEmpty(),
                        needsReview = it.needsReview ?: false
                    )
                }
                emit(NetworkResult.Success(items))
            } else {
                emit(errorFrom(resp, "Failed to load items"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getItems: ${e.message}", e)
            emit(NetworkResult.Error(e.message ?: NETWORK_FAIL))
        }
    }.flowOn(Dispatchers.IO)

    open fun closeBatch(batchId: Int): Flow<NetworkResult<Unit>> = flow {
        emit(NetworkResult.Loading)
        try {
            val resp = api.closeBatch(batchId)
            if (resp.isSuccessful) emit(NetworkResult.Success(Unit))
            else emit(errorFrom(resp, "Failed to close batch"))
        } catch (e: Exception) {
            Log.e(TAG, "closeBatch: ${e.message}", e)
            emit(NetworkResult.Error(e.message ?: NETWORK_FAIL))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 该用户在某仓库、自 [sinceDate]（yyyy-MM-dd，按 StartTime 过滤）起的批次中，
     * 已收货（状态 Closed/Dispatched、件数 > 0 且 EndTime 可解析）的列表。日期分组在 UI 层处理。
     */
    open fun getReceivedBatches(
        warehouseId: Int,
        scanUser: String,
        sinceDate: String
    ): Flow<NetworkResult<List<ReceivedBatch>>> = flow {
        emit(NetworkResult.Loading)
        try {
            val resp = api.getBatches(warehouseId, scanUser, sinceDate)
            if (resp.isSuccessful && resp.body() != null) {
                val received = resp.body()!!
                    .filter { (it.status == "Closed" || it.status == "Dispatched") && it.itemCount > 0 }
                    .mapNotNull { dto ->
                        val end = dto.endTime?.let { runCatching { LocalDateTime.parse(it, BATCH_TIME_FORMAT) }.getOrNull() }
                            ?: return@mapNotNull null
                        ReceivedBatch(
                            receivingBatchId = dto.receivingBatchId,
                            batchNumber = dto.batchNumber,
                            receivedAt = end,
                            itemCount = dto.itemCount
                        )
                    }
                emit(NetworkResult.Success(received))
            } else {
                emit(errorFrom(resp, "Failed to load report"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getReceivedBatches: ${e.message}", e)
            emit(NetworkResult.Error(e.message ?: NETWORK_FAIL))
        }
    }.flowOn(Dispatchers.IO)

    private fun errorFrom(resp: Response<*>, fallback: String): NetworkResult.Error {
        val serverError = runCatching {
            resp.errorBody()?.string()?.let { body ->
                Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
            }
        }.getOrNull()
        val message = serverError ?: when (resp.code()) {
            401 -> "Session expired, please sign in again"
            403 -> "No permission, contact your administrator"
            else -> "$fallback (${resp.code()})"
        }
        return NetworkResult.Error(message, resp.code())
    }
}
