package com.pda.app.data.repository

import android.util.Log
import com.pda.app.data.NetworkResult
import com.pda.app.data.api.ReceivingApiService
import com.pda.app.data.api.model.AnalyzeRequest
import com.pda.app.data.api.model.BatchInfo
import com.pda.app.data.api.model.CreateBatchRequest
import com.pda.app.data.api.model.CreateItemRequest
import com.pda.app.data.api.model.ReceivedBatch
import com.pda.app.data.api.model.ReceivingAlertDto
import com.pda.app.data.api.model.ReceivingAlertUi
import com.pda.app.data.api.model.ReceivingItemUi
import com.pda.app.data.api.model.ShippingAnalysis
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
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
    private val api: ReceivingApiService,
    // 跟 NetworkModule 里其它 API 用的是同一个共享单例；matchAlert() 需要它手动解码
    // 一个可空 DTO，见该方法内注释和 ReceivingApiService.matchReceivingAlert 上的说明。
    private val json: Json
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
                emit(NetworkResult.Success(ShippingAnalysis(
                    trackingNumber = a.trackingNumber,
                    carrier = a.carrier,
                    service = a.service,
                    raw = a.raw,
                    customerCode = a.customerCode,
                    customerName = a.customerName
                )))
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
                // 作废行（V）从有效列表排除，避免 PDA 重进仍看到已作废件。
                val items = resp.body()!!
                    .filter { it.status != "V" }
                    .map {
                        ReceivingItemUi(
                            receivingItemId = it.receivingItemId,
                            trackingNo = it.trackingNo.orEmpty(),
                            carrier = it.carrier.orEmpty(),
                            needsReview = it.needsReview ?: false,
                            customerName = it.customerName.orEmpty()
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

    open fun voidItem(receivingItemId: Int): Flow<NetworkResult<Unit>> = flow {
        emit(NetworkResult.Loading)
        try {
            val resp = api.voidItem(receivingItemId)
            if (resp.isSuccessful) emit(NetworkResult.Success(Unit))
            else emit(errorFrom(resp, "作废失败"))
        } catch (e: Exception) {
            Log.e(TAG, "voidItem: ${e.message}", e)
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
     * 近 10 天是否已有同一运单号。查询失败视为不重复（对齐网页，避免网络问题卡死录入）。
     */
    open fun isDuplicateTracking(trackingNumber: String): Flow<NetworkResult<Boolean>> = flow {
        emit(NetworkResult.Loading)
        try {
            val from = java.time.LocalDate.now().minusDays(10).toString()
            val resp = api.searchItems(
                trackingNumberExact = trackingNumber,
                receivedDateFrom = from,
                page = 1,
                pageSize = 1
            )
            if (resp.isSuccessful && resp.body() != null) {
                emit(NetworkResult.Success(resp.body()!!.total > 0))
            } else {
                emit(NetworkResult.Success(false))
            }
        } catch (e: Exception) {
            Log.e(TAG, "isDuplicateTracking: ${e.message}", e)
            emit(NetworkResult.Success(false))
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

    /**
     * 查询运单号是否命中一条生效中的收货预警；未命中（body 为 null 或 instruction/id 为空）
     * emit `Success(null)`。查询失败也 emit `Success(null)` 而不是 `Error`——对齐 web "查询提醒
     * 失败直接放行"：这一步失败不应该阻塞入库流程，调用方（ViewModel）不需要再单独处理 Error 分支。
     */
    open fun matchAlert(trackingNumber: String): Flow<NetworkResult<ReceivingAlertUi?>> = flow {
        emit(NetworkResult.Loading)
        try {
            val resp = api.matchReceivingAlert(trackingNumber)
            // 手动解码，不依赖 Retrofit 的 Converter：见 ReceivingApiService.matchReceivingAlert
            // 上的注释——Kotlin 可空类型在这里会被类型擦除，让 Retrofit 的 kotlinx-serialization
            // 转换器按非空 DTO 解码，字面量 `null`（未命中的真实响应）会直接抛异常。这里显式用
            // `ReceivingAlertDto?`（真正的可空 serializer）解析原始 body 字符串。
            val bodyString = if (resp.isSuccessful) resp.body()?.string() else null
            val dto = bodyString?.let {
                runCatching { json.decodeFromString<ReceivingAlertDto?>(it) }.getOrNull()
            }
            // trim() 只用来判断是不是空白；展示给操作员的要保留原文，不要用裁剪过的版本
            // （万一 instruction 里前后空白本身是格式的一部分，没道理帮后端"美化"）。
            val instructionRaw = dto?.instruction
            // id 也必须非空——否则后面 acknowledgeAlert(id) 会打到 /api/receiving-alerts//acknowledge
            // 这种带空路径段的无效 URL。见 docs/pda对齐.md 第 1 节"命中判定"。
            val alert = if (dto != null && dto.id.isNotBlank() && !instructionRaw.isNullOrBlank()) {
                ReceivingAlertUi(id = dto.id, instruction = instructionRaw)
            } else null
            emit(NetworkResult.Success(alert))
        } catch (e: Exception) {
            Log.w(TAG, "matchAlert: ${e.message}")
            emit(NetworkResult.Success(null))
        }
    }.flowOn(Dispatchers.IO)

    /** 确认收货预警后调用，把规则标成 Received。 */
    open fun acknowledgeAlert(id: String): Flow<NetworkResult<Unit>> = flow {
        emit(NetworkResult.Loading)
        try {
            val resp = api.acknowledgeReceivingAlert(id)
            if (resp.isSuccessful) emit(NetworkResult.Success(Unit))
            else emit(errorFrom(resp, "Failed to acknowledge alert"))
        } catch (e: Exception) {
            Log.w(TAG, "acknowledgeAlert: ${e.message}")
            emit(NetworkResult.Error(e.message ?: NETWORK_FAIL))
        }
    }.flowOn(Dispatchers.IO)

    // ReceivingRepository 是 @Singleton，天然活到进程结束（跟 CustomerDirectory 用自己的 scope
    // 是同一个理由）。acknowledgeAlert 这个 best-effort 请求专门用这个 scope 发起，不能用调用方
    // （DockReceivingViewModel）的 viewModelScope——用户点确认之后完全可能立刻关批次或退出 Dock
    // 页面，这时 viewModelScope 会被 ViewModel.onCleared() 取消掉，如果请求挂在 viewModelScope
    // 下会被这个取消提前打断，请求实际上根本没发出去/没跑完，而不是"发了但服务端没处理完"
    // 这种更常见的失败。见 docs/pda对齐.md 第 1 节。
    private val bestEffortScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 确认收货预警后调用，不等待、不关心成功与否。ack 失败/被打断的代价只是"下次同一运单号
     * 再入库还会重新触发一次提醒"，不影响任何已经完成的入库数据，所以特意设计成不可观测、
     * 不重试的 fire-and-forget——调用方不需要、也不应该再包一层 launch。
     */
    open fun acknowledgeAlertBestEffort(id: String) {
        bestEffortScope.launch {
            acknowledgeAlert(id).collect { /* 结果不关心，Flow 内部已经把异常吞成 Error 分支 */ }
        }
    }

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
