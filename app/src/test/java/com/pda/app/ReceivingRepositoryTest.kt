package com.pda.app

import com.pda.app.data.NetworkResult
import com.pda.app.data.api.ReceivingApiService
import com.pda.app.data.api.model.AnalyzeRequest
import com.pda.app.data.api.model.CloseBatchResponse
import com.pda.app.data.api.model.CreateBatchRequest
import com.pda.app.data.api.model.CreateBatchResponse
import com.pda.app.data.api.model.CreateItemRequest
import com.pda.app.data.api.model.CreateItemResponse
import com.pda.app.data.api.model.ReceivingBatchDto
import com.pda.app.data.api.model.ReceivingItemDto
import com.pda.app.data.api.model.ShippingAnalyzeResponse
import com.pda.app.data.api.model.UploadPhotosResponse
import com.pda.app.data.repository.ReceivingRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

private class FakeReceivingApiService(
    var createBatchResp: Response<CreateBatchResponse>? = null,
    var uploadResp: Response<UploadPhotosResponse>? = null,
    var analyzeResp: Response<ShippingAnalyzeResponse>? = null,
    var createItemResp: Response<CreateItemResponse>? = null,
    var getItemsResp: Response<List<ReceivingItemDto>>? = null,
    var closeResp: Response<CloseBatchResponse>? = null,
    var getBatchesResp: Response<List<ReceivingBatchDto>>? = null
) : ReceivingApiService {
    override suspend fun createBatch(req: CreateBatchRequest) = createBatchResp!!
    override suspend fun uploadPhotos(file: MultipartBody.Part) = uploadResp!!
    override suspend fun analyze(req: AnalyzeRequest) = analyzeResp!!
    override suspend fun createItem(req: CreateItemRequest) = createItemResp!!
    override suspend fun getItems(batchId: Int) = getItemsResp!!
    override suspend fun closeBatch(id: Int) = closeResp!!
    override suspend fun getBatches(warehouseId: Int?, scanUser: String?, scanDateFrom: String?) = getBatchesResp!!
}

private fun jsonBody(s: String) = s.toResponseBody("application/json".toMediaType())

class ReceivingRepositoryTest {

    @Test
    fun `createBatch emits Loading then Success with mapped BatchInfo`() = runTest {
        val api = FakeReceivingApiService(
            createBatchResp = Response.success(CreateBatchResponse(42, "B-2026-001"))
        )
        val repo = ReceivingRepository(api)

        val emissions = repo.createBatch(7).toList()

        assertTrue(emissions[0] is NetworkResult.Loading)
        val success = emissions[1] as NetworkResult.Success
        assertEquals(42, success.data.batchId)
        assertEquals("B-2026-001", success.data.batchNumber)
    }

    @Test
    fun `createBatch parses server error field`() = runTest {
        val api = FakeReceivingApiService(
            createBatchResp = Response.error(400, jsonBody("""{"error":"仓库无效"}"""))
        )
        val repo = ReceivingRepository(api)

        val error = repo.createBatch(7).toList()[1] as NetworkResult.Error
        assertEquals("仓库无效", error.message)
        assertEquals(400, error.code)
    }

    @Test
    fun `createBatch maps 403 to permission message when no error field`() = runTest {
        val api = FakeReceivingApiService(
            createBatchResp = Response.error(403, jsonBody("{}"))
        )
        val repo = ReceivingRepository(api)

        val error = repo.createBatch(7).toList()[1] as NetworkResult.Error
        assertEquals("No permission, contact your administrator", error.message)
    }

    @Test
    fun `uploadPhoto returns first url`() = runTest {
        val api = FakeReceivingApiService(
            uploadResp = Response.success(UploadPhotosResponse(listOf("/api/dock-receiving-photos/abc.jpg")))
        )
        val repo = ReceivingRepository(api)

        val success = repo.uploadPhoto(byteArrayOf(1, 2, 3), "capture.jpg").toList()[1] as NetworkResult.Success
        assertEquals("/api/dock-receiving-photos/abc.jpg", success.data)
    }

    @Test
    fun `uploadPhoto with empty urls is an error`() = runTest {
        val api = FakeReceivingApiService(uploadResp = Response.success(UploadPhotosResponse(emptyList())))
        val repo = ReceivingRepository(api)

        val error = repo.uploadPhoto(byteArrayOf(1), "x.jpg").toList()[1] as NetworkResult.Error
        assertEquals("Photo upload failed: no URL returned", error.message)
    }

    @Test
    fun `analyzeShipping maps fields`() = runTest {
        val api = FakeReceivingApiService(
            analyzeResp = Response.success(
                ShippingAnalyzeResponse(mode = "shipping", trackingNumber = "1Z999", carrier = "ups", raw = "{}")
            )
        )
        val repo = ReceivingRepository(api)

        val success = repo.analyzeShipping("base64").toList()[1] as NetworkResult.Success
        assertEquals("1Z999", success.data.trackingNumber)
        assertEquals("ups", success.data.carrier)
        assertEquals("{}", success.data.raw)
    }

    @Test
    fun `getItems maps dtos with null-safe defaults`() = runTest {
        val api = FakeReceivingApiService(
            getItemsResp = Response.success(
                listOf(
                    ReceivingItemDto(1, "1Z999", "FedEx", false),
                    ReceivingItemDto(2, null, null, true)
                )
            )
        )
        val repo = ReceivingRepository(api)

        val success = repo.getItems(42).toList()[1] as NetworkResult.Success
        assertEquals(2, success.data.size)
        assertEquals("1Z999", success.data[0].trackingNo)
        assertEquals("", success.data[1].trackingNo)
        assertEquals("", success.data[1].carrier)
        assertTrue(success.data[1].needsReview)
    }

    @Test
    fun `createItem returns new id`() = runTest {
        val api = FakeReceivingApiService(createItemResp = Response.success(CreateItemResponse(99)))
        val repo = ReceivingRepository(api)

        val req = CreateItemRequest(receivingBatchId = 42, photoPath = "/p.jpg")
        val success = repo.createItem(req).toList()[1] as NetworkResult.Success
        assertEquals(99, success.data)
    }

    @Test
    fun `closeBatch emits Success Unit`() = runTest {
        val api = FakeReceivingApiService(closeResp = Response.success(CloseBatchResponse(42, "Closed")))
        val repo = ReceivingRepository(api)

        val emissions = repo.closeBatch(42).toList()
        assertTrue(emissions[1] is NetworkResult.Success)
    }

    @Test
    fun `getReceivedBatches keeps non-empty closed batches and maps id`() = runTest {
        val api = FakeReceivingApiService(
            getBatchesResp = Response.success(
                listOf(
                    // 后端实际格式为空格分隔 "yyyy-MM-dd HH:mm:ss"（无 'T'）
                    ReceivingBatchDto(11, "B-1", status = "Closed", endTime = "2026-06-17 10:30:00", itemCount = 5),
                    ReceivingBatchDto(12, "B-2", status = "Open", endTime = null, itemCount = 0),
                    ReceivingBatchDto(13, "B-3", status = "Dispatched", endTime = "2026-06-16 09:00:00", itemCount = 3),
                    ReceivingBatchDto(14, "B-4", status = "Closed", endTime = "2026-06-17 11:00:00", itemCount = 0)
                )
            )
        )
        val repo = ReceivingRepository(api)

        val success = repo.getReceivedBatches(7, "alice", "2026-06-14").toList()[1] as NetworkResult.Success
        // B-2 被状态过滤；B-4 被 0 件过滤；只剩 B-1、B-3
        assertEquals(listOf("B-1", "B-3"), success.data.map { it.batchNumber })
        assertEquals(11, success.data[0].receivingBatchId)
        assertEquals(5, success.data[0].itemCount)
        assertEquals(10, success.data[0].receivedAt.hour)
    }
}
