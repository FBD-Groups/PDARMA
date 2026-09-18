package com.pda.app

import com.pda.app.data.NetworkResult
import com.pda.app.data.api.ReceivingApiService
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
import com.pda.app.data.repository.ReceivingRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

private class FakeReceivingApiService(
    var createBatchResp: Response<CreateBatchResponse>? = null,
    var uploadResp: Response<UploadPhotosResponse>? = null,
    var analyzeResp: Response<ShippingAnalyzeResponse>? = null,
    var createItemResp: Response<CreateItemResponse>? = null,
    var getItemsResp: Response<List<ReceivingItemDto>>? = null,
    var voidResp: Response<VoidItemResponse>? = null,
    var closeResp: Response<CloseBatchResponse>? = null,
    var getBatchesResp: Response<List<ReceivingBatchDto>>? = null,
    var searchItemsResp: Response<ReceivingItemSearchPage>? = null,
    var searchItemsThrows: Exception? = null,
    // 原始响应体（对齐真实的 ResponseBody 返回类型，见 ReceivingApiService.matchReceivingAlert
    // 上为什么不能用 Response<ReceivingAlertDto?> 的说明）；用 jsonBody("null") 模拟未命中。
    var matchAlertResp: Response<ResponseBody>? = null,
    var matchAlertThrows: Exception? = null,
    var acknowledgeAlertResp: Response<ReceivingAlertDto>? = null
) : ReceivingApiService {
    override suspend fun createBatch(req: CreateBatchRequest) = createBatchResp!!
    override suspend fun uploadPhotos(file: MultipartBody.Part) = uploadResp!!
    override suspend fun analyze(req: AnalyzeRequest) = analyzeResp!!
    override suspend fun createItem(req: CreateItemRequest) = createItemResp!!
    override suspend fun getItems(batchId: Int) = getItemsResp!!
    override suspend fun voidItem(id: Int) = voidResp!!
    override suspend fun closeBatch(id: Int) = closeResp!!
    override suspend fun getBatches(warehouseId: Int?, scanUser: String?, scanDateFrom: String?) = getBatchesResp!!
    override suspend fun searchItems(
        trackingNumberExact: String,
        receivedDateFrom: String,
        page: Int,
        pageSize: Int
    ): Response<ReceivingItemSearchPage> {
        searchItemsThrows?.let { throw it }
        return searchItemsResp!!
    }
    override suspend fun matchReceivingAlert(trackingNumber: String): Response<ResponseBody> {
        matchAlertThrows?.let { throw it }
        return matchAlertResp!!
    }
    override suspend fun acknowledgeReceivingAlert(id: String) = acknowledgeAlertResp!!
}

private fun jsonBody(s: String) = s.toResponseBody("application/json".toMediaType())

/** 跟 NetworkModule.provideJson() 保持一致——matchAlert() 手动解码用的是同一份配置。 */
private val testJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

class ReceivingRepositoryTest {

    @Test
    fun `createBatch emits Loading then Success with mapped BatchInfo`() = runTest {
        val api = FakeReceivingApiService(
            createBatchResp = Response.success(CreateBatchResponse(42, "B-2026-001"))
        )
        val repo = ReceivingRepository(api, testJson)

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
        val repo = ReceivingRepository(api, testJson)

        val error = repo.createBatch(7).toList()[1] as NetworkResult.Error
        assertEquals("仓库无效", error.message)
        assertEquals(400, error.code)
    }

    @Test
    fun `createBatch maps 403 to permission message when no error field`() = runTest {
        val api = FakeReceivingApiService(
            createBatchResp = Response.error(403, jsonBody("{}"))
        )
        val repo = ReceivingRepository(api, testJson)

        val error = repo.createBatch(7).toList()[1] as NetworkResult.Error
        assertEquals("No permission, contact your administrator", error.message)
    }

    @Test
    fun `uploadPhoto returns first url`() = runTest {
        val api = FakeReceivingApiService(
            uploadResp = Response.success(UploadPhotosResponse(listOf("/api/dock-receiving-photos/abc.jpg")))
        )
        val repo = ReceivingRepository(api, testJson)

        val success = repo.uploadPhoto(byteArrayOf(1, 2, 3), "capture.jpg").toList()[1] as NetworkResult.Success
        assertEquals("/api/dock-receiving-photos/abc.jpg", success.data)
    }

    @Test
    fun `uploadPhoto with empty urls is an error`() = runTest {
        val api = FakeReceivingApiService(uploadResp = Response.success(UploadPhotosResponse(emptyList())))
        val repo = ReceivingRepository(api, testJson)

        val error = repo.uploadPhoto(byteArrayOf(1), "x.jpg").toList()[1] as NetworkResult.Error
        assertEquals("Photo upload failed: no URL returned", error.message)
    }

    @Test
    fun `analyzeShipping maps fields including customerName`() = runTest {
        val api = FakeReceivingApiService(
            analyzeResp = Response.success(
                ShippingAnalyzeResponse(
                    mode = "shipping",
                    trackingNumber = "1Z999",
                    carrier = "ups",
                    customerName = "Eco",
                    raw = "{}"
                )
            )
        )
        val repo = ReceivingRepository(api, testJson)

        val success = repo.analyzeShipping("base64").toList()[1] as NetworkResult.Success
        assertEquals("1Z999", success.data.trackingNumber)
        assertEquals("ups", success.data.carrier)
        assertEquals("Eco", success.data.customerName)
        assertEquals("{}", success.data.raw)
    }

    @Test
    fun `analyzeShipping maps customerCode`() = runTest {
        val api = FakeReceivingApiService(
            analyzeResp = Response.success(
                ShippingAnalyzeResponse(
                    mode = "shipping",
                    trackingNumber = "875972515283",
                    carrier = "FedEx",
                    customerCode = "UF00162",
                    customerName = null,
                    raw = "{}"
                )
            )
        )
        val repo = ReceivingRepository(api, testJson)

        val success = repo.analyzeShipping("base64").toList()[1] as NetworkResult.Success
        assertEquals("UF00162", success.data.customerCode)
        assertEquals(null, success.data.customerName)
    }

    @Test
    fun `getItems maps dtos with null-safe defaults`() = runTest {
        val api = FakeReceivingApiService(
            getItemsResp = Response.success(
                listOf(
                    ReceivingItemDto(1, "1Z999", "FedEx", customerName = "Acme", needsReview = false),
                    ReceivingItemDto(2, null, null, needsReview = true)
                )
            )
        )
        val repo = ReceivingRepository(api, testJson)

        val success = repo.getItems(42).toList()[1] as NetworkResult.Success
        assertEquals(2, success.data.size)
        assertEquals("1Z999", success.data[0].trackingNo)
        assertEquals("Acme", success.data[0].customerName)
        assertEquals("", success.data[1].trackingNo)
        assertEquals("", success.data[1].carrier)
        assertEquals("", success.data[1].customerName)
        assertTrue(success.data[1].needsReview)
    }

    @Test
    fun `getItems excludes voided status V`() = runTest {
        val api = FakeReceivingApiService(
            getItemsResp = Response.success(
                listOf(
                    ReceivingItemDto(1, "T-OPEN", "UPS", status = "O"),
                    ReceivingItemDto(2, "T-VOID", "UPS", status = "V"),
                    ReceivingItemDto(3, "T-NULL", "FedEx", status = null)
                )
            )
        )
        val repo = ReceivingRepository(api, testJson)
        val success = repo.getItems(1).toList()[1] as NetworkResult.Success
        assertEquals(listOf(1, 3), success.data.map { it.receivingItemId })
    }

    @Test
    fun `voidItem emits Loading then Success`() = runTest {
        val api = FakeReceivingApiService(
            voidResp = Response.success(VoidItemResponse(9, "V"))
        )
        val repo = ReceivingRepository(api, testJson)
        val emissions = repo.voidItem(9).toList()
        assertTrue(emissions[0] is NetworkResult.Loading)
        assertTrue(emissions[1] is NetworkResult.Success)
    }

    @Test
    fun `createItem returns new id`() = runTest {
        val api = FakeReceivingApiService(createItemResp = Response.success(CreateItemResponse(99)))
        val repo = ReceivingRepository(api, testJson)

        val req = CreateItemRequest(receivingBatchId = 42, photoPaths = listOf("/p.jpg"), customerName = "Eco")
        val success = repo.createItem(req).toList()[1] as NetworkResult.Success
        assertEquals(99, success.data)
    }

    @Test
    fun `isDuplicateTracking true when total gt 0`() = runTest {
        val api = FakeReceivingApiService(
            searchItemsResp = Response.success(ReceivingItemSearchPage(total = 1))
        )
        val repo = ReceivingRepository(api, testJson)
        val success = repo.isDuplicateTracking("1Z999").toList()[1] as NetworkResult.Success
        assertTrue(success.data)
    }

    @Test
    fun `isDuplicateTracking false when total 0`() = runTest {
        val api = FakeReceivingApiService(
            searchItemsResp = Response.success(ReceivingItemSearchPage(total = 0))
        )
        val repo = ReceivingRepository(api, testJson)
        val success = repo.isDuplicateTracking("1Z999").toList()[1] as NetworkResult.Success
        assertFalse(success.data)
    }

    @Test
    fun `isDuplicateTracking treats search failure as not duplicate`() = runTest {
        val api = FakeReceivingApiService(
            searchItemsThrows = RuntimeException("network down")
        )
        val repo = ReceivingRepository(api, testJson)
        val success = repo.isDuplicateTracking("1Z999").toList()[1] as NetworkResult.Success
        assertFalse(success.data)
    }

    @Test
    fun `closeBatch emits Success Unit`() = runTest {
        val api = FakeReceivingApiService(closeResp = Response.success(CloseBatchResponse(42, "Closed")))
        val repo = ReceivingRepository(api, testJson)

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
        val repo = ReceivingRepository(api, testJson)

        val success = repo.getReceivedBatches(7, "alice", "2026-06-14").toList()[1] as NetworkResult.Success
        // B-2 被状态过滤；B-4 被 0 件过滤；只剩 B-1、B-3
        assertEquals(listOf("B-1", "B-3"), success.data.map { it.batchNumber })
        assertEquals(11, success.data[0].receivingBatchId)
        assertEquals(5, success.data[0].itemCount)
        assertEquals(10, success.data[0].receivedAt.hour)
    }

    // ── matchAlert / acknowledgeAlert ────────────────────────────────────────────

    @Test
    fun `matchAlert maps a hit to id and instruction`() = runTest {
        val api = FakeReceivingApiService(
            matchAlertResp = Response.success(
                jsonBody("""{"id":"abc-123","trackingNumber":"1Z999","instruction":"Please send to DJI","status":"O"}""")
            )
        )
        val repo = ReceivingRepository(api, testJson)

        val success = repo.matchAlert("1Z999").toList()[1] as NetworkResult.Success
        assertEquals("abc-123", success.data!!.id)
        assertEquals("Please send to DJI", success.data!!.instruction)
    }

    @Test
    fun `matchAlert treats a literal null body as no match`() = runTest {
        // HTTP 200 with a literal `null` body — the real "not matched" shape from the backend.
        // 走真实的 Retrofit ResponseBody + 手动 Json.decodeFromString<ReceivingAlertDto?> 路径
        // （不是直接构造一个 Kotlin null 对象），这才是文档里强调要验证的那个具体场景——
        // 见 MatchReceivingAlertSerializationTest 用 MockWebServer 对同一行为的独立验证。
        val api = FakeReceivingApiService(matchAlertResp = Response.success(jsonBody("null")))
        val repo = ReceivingRepository(api, testJson)

        val success = repo.matchAlert("1Z999").toList()[1] as NetworkResult.Success
        assertEquals(null, success.data)
    }

    @Test
    fun `matchAlert treats blank id as no match`() = runTest {
        val api = FakeReceivingApiService(
            matchAlertResp = Response.success(
                jsonBody("""{"id":"","trackingNumber":"1Z999","instruction":"Please send to DJI","status":"O"}""")
            )
        )
        val repo = ReceivingRepository(api, testJson)

        val success = repo.matchAlert("1Z999").toList()[1] as NetworkResult.Success
        assertEquals(null, success.data)
    }

    @Test
    fun `matchAlert treats blank instruction as no match`() = runTest {
        val api = FakeReceivingApiService(
            matchAlertResp = Response.success(
                jsonBody("""{"id":"abc-123","trackingNumber":"1Z999","instruction":"  ","status":"O"}""")
            )
        )
        val repo = ReceivingRepository(api, testJson)

        val success = repo.matchAlert("1Z999").toList()[1] as NetworkResult.Success
        assertEquals(null, success.data)
    }

    @Test
    fun `matchAlert failure emits Success null, not Error — never blocks the caller`() = runTest {
        val api = FakeReceivingApiService(matchAlertThrows = RuntimeException("network down"))
        val repo = ReceivingRepository(api, testJson)

        val result = repo.matchAlert("1Z999").toList()[1]
        assertTrue(result is NetworkResult.Success)
        assertEquals(null, (result as NetworkResult.Success).data)
    }

    @Test
    fun `matchAlert http error emits Success null`() = runTest {
        val api = FakeReceivingApiService(matchAlertResp = Response.error(500, jsonBody("{}")))
        val repo = ReceivingRepository(api, testJson)

        val result = repo.matchAlert("1Z999").toList()[1]
        assertTrue(result is NetworkResult.Success)
        assertEquals(null, (result as NetworkResult.Success).data)
    }

    @Test
    fun `acknowledgeAlert emits Success Unit`() = runTest {
        val api = FakeReceivingApiService(
            acknowledgeAlertResp = Response.success(ReceivingAlertDto(id = "abc-123", status = "R"))
        )
        val repo = ReceivingRepository(api, testJson)

        val emissions = repo.acknowledgeAlert("abc-123").toList()
        assertTrue(emissions[0] is NetworkResult.Loading)
        assertTrue(emissions[1] is NetworkResult.Success)
    }
}
