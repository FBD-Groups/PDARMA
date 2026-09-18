package com.pda.app.data.api

import com.pda.app.data.api.model.AnalyzeRequest
import com.pda.app.data.api.model.CloseBatchResponse
import com.pda.app.data.api.model.CreateBatchRequest
import com.pda.app.data.api.model.CreateBatchResponse
import com.pda.app.data.api.model.CreateItemRequest
import com.pda.app.data.api.model.CreateItemResponse
import com.pda.app.data.api.model.ReceivingAlertDto
import com.pda.app.data.api.model.ReceivingBatchDto
import com.pda.app.data.api.model.ReceivingItemDto
import com.pda.app.data.api.model.ReceivingItemSearchPage
import com.pda.app.data.api.model.ShippingAnalyzeResponse
import com.pda.app.data.api.model.UploadPhotosResponse
import com.pda.app.data.api.model.VoidItemResponse
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface ReceivingApiService {

    @POST("api/receiving-batches")
    suspend fun createBatch(@Body req: CreateBatchRequest): Response<CreateBatchResponse>

    @Multipart
    @POST("api/dock-receiving-photos")
    suspend fun uploadPhotos(@Part file: MultipartBody.Part): Response<UploadPhotosResponse>

    @POST("api/analyze")
    suspend fun analyze(@Body req: AnalyzeRequest): Response<ShippingAnalyzeResponse>

    @POST("api/receiving-items")
    suspend fun createItem(@Body req: CreateItemRequest): Response<CreateItemResponse>

    @GET("api/receiving-items")
    suspend fun getItems(@Query("batchId") batchId: Int): Response<List<ReceivingItemDto>>

    /** 软作废 Open 行 → status V；不可恢复。 */
    @POST("api/receiving-items/{id}/void")
    suspend fun voidItem(@Path("id") id: Int): Response<VoidItemResponse>

    @POST("api/receiving-batches/{id}/close")
    suspend fun closeBatch(@Path("id") id: Int): Response<CloseBatchResponse>

    @GET("api/receiving-batches")
    suspend fun getBatches(
        @Query("warehouseId") warehouseId: Int?,
        @Query("scanUser") scanUser: String?,
        @Query("scanDateFrom") scanDateFrom: String?
    ): Response<List<ReceivingBatchDto>>

    /** 近 N 天精确运单号搜索（判重）；只用 total。 */
    @GET("api/receiving-items/search")
    suspend fun searchItems(
        @Query("trackingNumberExact") trackingNumberExact: String,
        @Query("receivedDateFrom") receivedDateFrom: String,
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 1
    ): Response<ReceivingItemSearchPage>

    /**
     * 未命中返回 HTTP 200、body 为字面量 `null`。**故意返回 `ResponseBody` 而不是
     * `Response<ReceivingAlertDto?>`**——Kotlin 的可空类型在编译后会被类型擦除，
     * Retrofit 的 kotlinx-serialization 转换器拿到的 `Type` 反射不出 `?`，实际总是按非空
     * `ReceivingAlertDto` 的 serializer 解码，遇到字面量 `null` 会直接抛
     * `JsonDecodingException`（已经用 MockWebServer 实测验证过，不是理论风险）。
     * `ResponseBody` 是 Retrofit 原生识别的"不经过 Converter、直接给原始响应体"类型，
     * 交给 [com.pda.app.data.repository.ReceivingRepository.matchAlert] 自己拿注入的
     * `Json` 实例、用 `Json.decodeFromString<ReceivingAlertDto?>(...)`（这里的 `?` 是
     * Kotlin 源码层面真实调用的可空 serializer，不经过类型擦除）手动解析，才能正确处理
     * 字面量 `null`。见 docs/pda对齐.md 第 1 节。
     */
    @GET("api/receiving-alerts/match")
    suspend fun matchReceivingAlert(
        @Query("trackingNumber") trackingNumber: String
    ): Response<ResponseBody>

    /** 确认收货预警后调用，把规则标成 Received；幂等。 */
    @POST("api/receiving-alerts/{id}/acknowledge")
    suspend fun acknowledgeReceivingAlert(@Path("id") id: String): Response<ReceivingAlertDto>
}
