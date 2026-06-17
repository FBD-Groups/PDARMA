package com.pda.app.data.api

import com.pda.app.data.api.model.AnalyzeRequest
import com.pda.app.data.api.model.CloseBatchResponse
import com.pda.app.data.api.model.CreateBatchRequest
import com.pda.app.data.api.model.CreateBatchResponse
import com.pda.app.data.api.model.CreateItemRequest
import com.pda.app.data.api.model.CreateItemResponse
import com.pda.app.data.api.model.ReceivingItemDto
import com.pda.app.data.api.model.ShippingAnalyzeResponse
import com.pda.app.data.api.model.UploadPhotosResponse
import okhttp3.MultipartBody
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

    @POST("api/receiving-batches/{id}/close")
    suspend fun closeBatch(@Path("id") id: Int): Response<CloseBatchResponse>
}
