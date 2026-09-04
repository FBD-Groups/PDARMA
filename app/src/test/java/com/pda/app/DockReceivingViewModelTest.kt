package com.pda.app

import androidx.lifecycle.SavedStateHandle
import com.pda.app.data.prefs.UserPreferences
import com.pda.app.data.NetworkResult
import com.pda.app.data.api.model.BatchInfo
import com.pda.app.data.api.model.CreateItemRequest
import com.pda.app.data.api.model.ReceivingItemUi
import com.pda.app.data.api.model.ShippingAnalysis
import com.pda.app.data.repository.ReceivingRepository
import com.pda.app.ui.dockreceiving.BarcodeDecoder
import com.pda.app.ui.dockreceiving.CaptureStatus
import com.pda.app.ui.dockreceiving.CompressedImage
import com.pda.app.ui.dockreceiving.DockMessage
import com.pda.app.ui.dockreceiving.DockReceivingViewModel
import com.pda.app.ui.dockreceiving.ImageEncoder
import com.pda.app.ui.dockreceiving.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

private class FakeReceivingRepository(
    api: com.pda.app.data.api.ReceivingApiService = ThrowingApi
) : ReceivingRepository(api) {
    var createBatchFlow: () -> Flow<NetworkResult<BatchInfo>> = { flowOf(NetworkResult.Loading) }
    var uploadFlow: () -> Flow<NetworkResult<String>> = { flowOf(NetworkResult.Loading) }
    var analyzeFlow: () -> Flow<NetworkResult<ShippingAnalysis>> = { flowOf(NetworkResult.Loading) }
    var createItemFlow: () -> Flow<NetworkResult<Int>> = { flowOf(NetworkResult.Success(1)) }
    var getItemsFlow: () -> Flow<NetworkResult<List<ReceivingItemUi>>> = { flowOf(NetworkResult.Success(emptyList())) }
    var closeFlow: () -> Flow<NetworkResult<Unit>> = { flowOf(NetworkResult.Success(Unit)) }
    var duplicateFlow: () -> Flow<NetworkResult<Boolean>> = { flowOf(NetworkResult.Success(false)) }
    var lastCreateItemReq: CreateItemRequest? = null
    var createItemCallCount: Int = 0

    override fun createBatch(warehouseId: Int) = createBatchFlow()
    override fun uploadPhoto(bytes: ByteArray, filename: String) = uploadFlow()
    override fun analyzeShipping(base64: String) = analyzeFlow()
    override fun createItem(req: CreateItemRequest): Flow<NetworkResult<Int>> {
        createItemCallCount++
        lastCreateItemReq = req
        return createItemFlow()
    }
    override fun getItems(batchId: Int) = getItemsFlow()
    override fun closeBatch(batchId: Int) = closeFlow()
    override fun voidItem(receivingItemId: Int) = flowOf(NetworkResult.Success(Unit))
    override fun isDuplicateTracking(trackingNumber: String) = duplicateFlow()

    private companion object {
        val ThrowingApi = object : com.pda.app.data.api.ReceivingApiService {
            override suspend fun createBatch(req: com.pda.app.data.api.model.CreateBatchRequest) = error("unused")
            override suspend fun uploadPhotos(file: okhttp3.MultipartBody.Part) = error("unused")
            override suspend fun analyze(req: com.pda.app.data.api.model.AnalyzeRequest) = error("unused")
            override suspend fun createItem(req: CreateItemRequest) = error("unused")
            override suspend fun getItems(batchId: Int) = error("unused")
            override suspend fun voidItem(id: Int) = error("unused")
            override suspend fun closeBatch(id: Int) = error("unused")
            override suspend fun getBatches(warehouseId: Int?, scanUser: String?, scanDateFrom: String?) = error("unused")
            override suspend fun searchItems(
                trackingNumberExact: String,
                receivedDateFrom: String,
                page: Int,
                pageSize: Int
            ) = error("unused")
        }
    }
}

private class FakeCustomerRepository(
    private val customers: List<com.pda.app.data.api.model.ActiveCustomer> = emptyList()
) : com.pda.app.data.repository.CustomerRepository(
    object : com.pda.app.data.api.CustomerApiService {
        override suspend fun getCustomers() = error("unused")
    }
) {
    override fun getActiveCustomers() = flowOf(NetworkResult.Success(customers))
}

private class FakeImageEncoder : ImageEncoder {
    override suspend fun prepareForUpload(file: File) = byteArrayOf(9, 9, 9)
    override suspend fun compress(file: File) = CompressedImage(byteArrayOf(1, 2, 3), "BASE64")
}

private class FakeBarcodeDecoder(private val result: String? = null) : BarcodeDecoder {
    override suspend fun decodeTracking(file: File) = result
}

private class FakeUserPreferences(private var inputMethod: String? = null) : UserPreferences {
    override val lastUsername = flowOf<String?>(null)
    override val lastPassword = flowOf<String?>(null)
    override val rememberUsername = flowOf(true)
    override val selectedWarehouseId = flowOf<Int?>(null)
    override val dockInputMethod = flowOf(inputMethod)
    override val appLanguage = flowOf<String?>(null)
    override suspend fun saveLoginCredentials(username: String, password: String, remember: Boolean) {}
    override suspend fun setSelectedWarehouseId(id: Int) {}
    override suspend fun setDockInputMethod(name: String) { inputMethod = name }
    override suspend fun setAppLanguage(name: String) {}
}

private fun vm(
    repo: ReceivingRepository,
    warehouseId: String? = "7",
    barcode: String? = null,
    customers: List<com.pda.app.data.api.model.ActiveCustomer> = emptyList()
): DockReceivingViewModel =
    DockReceivingViewModel(
        repo,
        FakeCustomerRepository(customers),
        FakeImageEncoder(),
        FakeBarcodeDecoder(barcode),
        object : com.pda.app.ui.dockreceiving.DockSoundPlayer {
            override fun playSuccess() {}
            override fun playBeep() {}
        },
        FakeUserPreferences(),
        SavedStateHandle(mapOf("warehouseId" to warehouseId))
    )

class DockReceivingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `startBatch success moves to Recording with batch info`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Loading, NetworkResult.Success(BatchInfo(42, "B-001"))) }
        }
        val vm = vm(repo)

        vm.startBatch()
        advanceUntilIdle()

        val s = vm.uiState.value
        assertEquals(Phase.Recording, s.phase)
        assertEquals(42, s.batchId)
        assertEquals("B-001", s.batchNumber)
        assertTrue(s.items.isEmpty())
    }

    @Test
    fun `startBatch failure stays Idle and surfaces message`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Loading, NetworkResult.Error("创建批次失败（500）", 500)) }
        }
        val vm = vm(repo)

        vm.startBatch()
        advanceUntilIdle()

        val s = vm.uiState.value
        assertEquals(Phase.Idle, s.phase)
        assertEquals(DockMessage.Text("创建批次失败（500）"), s.message)
    }

    @Test
    fun `onPhotoCaptured auto-saves with tracking carrier customerName and photoPaths`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            uploadFlow = { flowOf(NetworkResult.Loading, NetworkResult.Success("/p/abc.jpg")) }
            analyzeFlow = {
                flowOf(
                    NetworkResult.Loading,
                    NetworkResult.Success(
                        ShippingAnalysis("1Z999AA10123456784", "fedex", null, "{}", customerName = "Eco")
                    )
                )
            }
            getItemsFlow = {
                flowOf(NetworkResult.Success(listOf(
                    ReceivingItemUi(1, "1Z999AA10123456784", "FedEx", false, "Eco")
                )))
            }
        }
        val vm = vm(repo)
        vm.startBatch(); advanceUntilIdle()

        vm.onPhotoCaptured(File("capture.jpg"))
        advanceUntilIdle()

        assertEquals(1, repo.createItemCallCount)
        val req = repo.lastCreateItemReq!!
        assertEquals("1Z999AA10123456784", req.trackingNumber)
        assertEquals("FedEx", req.carrier)
        assertEquals("Eco", req.customerName)
        assertEquals(listOf("/p/abc.jpg"), req.photoPaths)
        assertEquals("AI", req.source)
        assertEquals(false, req.needsReview)
        assertEquals(CaptureStatus.Success, vm.uiState.value.captureStatus)
        assertEquals("1Z999AA10123456784", vm.uiState.value.confirm!!.trackingNumber)
        assertEquals("Eco", vm.uiState.value.confirm!!.customerName)
        assertNull(vm.uiState.value.confirm!!.photoFile)
        assertEquals(1, vm.uiState.value.itemCount)
    }

    @Test
    fun `onPhotoCaptured resolves unmatched UF code as customerName`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            uploadFlow = { flowOf(NetworkResult.Loading, NetworkResult.Success("/p/abc.jpg")) }
            analyzeFlow = {
                flowOf(
                    NetworkResult.Loading,
                    NetworkResult.Success(
                        ShippingAnalysis(
                            "875972515283",
                            "FedEx",
                            null,
                            "{}",
                            customerCode = "UF00162-RMA",
                            customerName = null
                        )
                    )
                )
            }
            getItemsFlow = {
                flowOf(NetworkResult.Success(listOf(
                    ReceivingItemUi(1, "875972515283", "FedEx", false, "UF00162")
                )))
            }
        }
        val vm = vm(repo) // empty customer list → unmatched
        vm.startBatch(); advanceUntilIdle()

        vm.onPhotoCaptured(File("capture.jpg"))
        advanceUntilIdle()

        assertEquals("UF00162", repo.lastCreateItemReq!!.customerName)
        assertNull(repo.lastCreateItemReq!!.customerId)
    }

    @Test
    fun `onPhotoCaptured matches UF code to customer list name`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            uploadFlow = { flowOf(NetworkResult.Loading, NetworkResult.Success("/p/abc.jpg")) }
            analyzeFlow = {
                flowOf(
                    NetworkResult.Loading,
                    NetworkResult.Success(
                        ShippingAnalysis(
                            "875972515283",
                            "FedEx",
                            null,
                            "{}",
                            customerCode = "UF00162",
                            customerName = null
                        )
                    )
                )
            }
            getItemsFlow = {
                flowOf(NetworkResult.Success(listOf(
                    ReceivingItemUi(1, "875972515283", "FedEx", false, "RMA Technology")
                )))
            }
        }
        val vm = vm(
            repo,
            customers = listOf(com.pda.app.data.api.model.ActiveCustomer(99, "UF00162", "RMA Technology"))
        )
        vm.startBatch(); advanceUntilIdle()

        vm.onPhotoCaptured(File("capture.jpg"))
        advanceUntilIdle()

        assertEquals("RMA Technology", repo.lastCreateItemReq!!.customerName)
        assertEquals(99L, repo.lastCreateItemReq!!.customerId)
    }

    @Test
    fun `analyze failure leaves fields empty but keeps confirm open`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            uploadFlow = { flowOf(NetworkResult.Success("/p/abc.jpg")) }
            analyzeFlow = { flowOf(NetworkResult.Loading, NetworkResult.Error("AI 识别失败", null)) }
        }
        val vm = vm(repo)
        vm.startBatch(); advanceUntilIdle()

        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()

        val c = vm.uiState.value.confirm!!
        assertEquals("/p/abc.jpg", c.photoPath)
        assertEquals("", c.trackingNumber)
        assertFalse(c.analyzing)
        assertFalse(c.canSave)
        assertEquals(0, repo.createItemCallCount)
        assertEquals(Phase.Recording, vm.uiState.value.phase)
    }

    @Test
    fun `no tracking after analyze blocks auto-save until entered manually`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            uploadFlow = { flowOf(NetworkResult.Success("/p/abc.jpg")) }
            analyzeFlow = { flowOf(NetworkResult.Success(ShippingAnalysis(null, "UPS", null, "{}", customerName = null))) }
            getItemsFlow = { flowOf(NetworkResult.Success(emptyList())) }
        }
        val vm = vm(repo)
        vm.startBatch(); advanceUntilIdle()
        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()

        assertEquals(0, repo.createItemCallCount)
        assertFalse(vm.uiState.value.confirm!!.canSave)

        vm.onTrackingChanged("1Z999AA10123456784")
        assertTrue(vm.uiState.value.confirm!!.canSave)
        // 手改运单号不自动保存
        advanceUntilIdle()
        assertEquals(0, repo.createItemCallCount)

        vm.saveItem(); advanceUntilIdle()
        assertEquals(1, repo.createItemCallCount)
        assertEquals("1Z999AA10123456784", repo.lastCreateItemReq!!.trackingNumber)
    }

    @Test
    fun `upload failure marks uploadFailed and blocks save`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            uploadFlow = { flowOf(NetworkResult.Loading, NetworkResult.Error("图片上传失败", null)) }
            analyzeFlow = { flowOf(NetworkResult.Success(ShippingAnalysis("1Z999AA10123456784", null, null, null))) }
        }
        val vm = vm(repo)
        vm.startBatch(); advanceUntilIdle()

        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()

        val c = vm.uiState.value.confirm!!
        assertTrue(c.uploadFailed)
        assertNull(c.photoPath)
        assertFalse(c.canSave)
        assertEquals(0, repo.createItemCallCount)
    }

    @Test
    fun `saveItem sends needsReview true when tracking blank, refreshes list`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            uploadFlow = { flowOf(NetworkResult.Success("/p/abc.jpg")) }
            analyzeFlow = { flowOf(NetworkResult.Success(ShippingAnalysis(null, null, null, null))) }
            createItemFlow = { flowOf(NetworkResult.Success(7)) }
            getItemsFlow = { flowOf(NetworkResult.Success(listOf(
                ReceivingItemUi(7, "", "", true)
            ))) }
        }
        val vm = vm(repo)
        vm.startBatch(); advanceUntilIdle()
        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()

        vm.saveItem(); advanceUntilIdle()

        assertEquals(true, repo.lastCreateItemReq!!.needsReview)
        assertEquals(42, repo.lastCreateItemReq!!.receivingBatchId)
        assertEquals(listOf("/p/abc.jpg"), repo.lastCreateItemReq!!.photoPaths)
        val s = vm.uiState.value
        assertEquals(Phase.Recording, s.phase)
        assertNotNull(s.confirm)
        assertEquals("", s.confirm!!.trackingNumber)
        assertNull(s.confirm!!.photoFile)
        assertEquals(1, s.itemCount)
        assertEquals(1, s.needsReviewCount)
    }

    @Test
    fun `auto-save sends needsReview false when tracking present`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            uploadFlow = { flowOf(NetworkResult.Success("/p/abc.jpg")) }
            analyzeFlow = { flowOf(NetworkResult.Success(ShippingAnalysis("1Z999AA10123456784", "UPS", null, "{}"))) }
            getItemsFlow = { flowOf(NetworkResult.Success(listOf(ReceivingItemUi(7, "1Z999AA10123456784", "UPS", false)))) }
        }
        val vm = vm(repo)
        vm.startBatch(); advanceUntilIdle()
        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()

        assertEquals(false, repo.lastCreateItemReq!!.needsReview)
        assertEquals("1Z999AA10123456784", repo.lastCreateItemReq!!.trackingNumber)
    }

    @Test
    fun `AI tracking overrides barcode and tags source AI`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            uploadFlow = { flowOf(NetworkResult.Success("/p/abc.jpg")) }
            analyzeFlow = {
                flowOf(
                    NetworkResult.Success(
                        ShippingAnalysis("792672039657", "FedEx", null, "{}")
                    )
                )
            }
            getItemsFlow = { flowOf(NetworkResult.Success(emptyList())) }
        }
        // 条码先解出内部 FWD（会被 sanitize 丢掉）；即使 Fake 返回合法 UPS，AI 也应覆盖。
        val vm = vm(repo, barcode = "1Z999AA10123456784")
        vm.startBatch(); advanceUntilIdle()
        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()

        assertEquals(1, repo.createItemCallCount)
        assertEquals("792672039657", repo.lastCreateItemReq!!.trackingNumber)
        assertEquals("AI", repo.lastCreateItemReq!!.source)
    }

    @Test
    fun `falls back to AI tracking when no barcode, source AI`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            uploadFlow = { flowOf(NetworkResult.Success("/p/abc.jpg")) }
            analyzeFlow = { flowOf(NetworkResult.Success(ShippingAnalysis("1Z999AA10123456784", "UPS", null, "{}"))) }
            getItemsFlow = { flowOf(NetworkResult.Success(emptyList())) }
        }
        val vm = vm(repo, barcode = null)
        vm.startBatch(); advanceUntilIdle()
        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()

        assertEquals("1Z999AA10123456784", repo.lastCreateItemReq!!.trackingNumber)
        assertEquals("AI", repo.lastCreateItemReq!!.source)
    }

    @Test
    fun `barcode fills tracking even when AI analysis fails and auto-saves`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            uploadFlow = { flowOf(NetworkResult.Success("/p/abc.jpg")) }
            analyzeFlow = { flowOf(NetworkResult.Error("AI 暂不可用", 502)) }
            getItemsFlow = { flowOf(NetworkResult.Success(emptyList())) }
        }
        val vm = vm(repo, barcode = "1Z999AA10123456784")
        vm.startBatch(); advanceUntilIdle()
        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()

        assertEquals(1, repo.createItemCallCount)
        assertEquals("1Z999AA10123456784", repo.lastCreateItemReq!!.trackingNumber)
        assertEquals("Barcode", repo.lastCreateItemReq!!.source)
    }

    @Test
    fun `auto-saves after AI without requiring customerName`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            uploadFlow = { flowOf(NetworkResult.Success("/p/abc.jpg")) }
            analyzeFlow = {
                flowOf(
                    NetworkResult.Success(
                        ShippingAnalysis("1Z999AA10123456784", "UPS", null, "{}", customerName = null)
                    )
                )
            }
            getItemsFlow = {
                flowOf(NetworkResult.Success(listOf(
                    ReceivingItemUi(1, "1Z999AA10123456784", "UPS", false)
                )))
            }
        }
        val vm = vm(repo, barcode = "1Z999AA10123456784")
        vm.startBatch(); advanceUntilIdle()
        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()

        assertEquals(1, repo.createItemCallCount)
        assertEquals("1Z999AA10123456784", repo.lastCreateItemReq!!.trackingNumber)
        assertNull(repo.lastCreateItemReq!!.customerName)
        assertEquals(listOf("/p/abc.jpg"), repo.lastCreateItemReq!!.photoPaths)
        assertEquals("AI", repo.lastCreateItemReq!!.source)
    }

    @Test
    fun `duplicate tracking shows pending dialog and confirms save`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            uploadFlow = { flowOf(NetworkResult.Success("/p/abc.jpg")) }
            analyzeFlow = { flowOf(NetworkResult.Success(ShippingAnalysis("1Z999AA10123456784", "UPS", null, "{}"))) }
            duplicateFlow = { flowOf(NetworkResult.Success(true)) }
            getItemsFlow = { flowOf(NetworkResult.Success(emptyList())) }
        }
        val vm = vm(repo)
        vm.startBatch(); advanceUntilIdle()
        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()

        assertEquals(0, repo.createItemCallCount)
        assertEquals("1Z999AA10123456784", vm.uiState.value.confirm!!.pendingDuplicateTracking)

        vm.confirmDuplicateSave(); advanceUntilIdle()
        assertEquals(1, repo.createItemCallCount)
        assertNull(vm.uiState.value.confirm!!.pendingDuplicateTracking)
    }

    @Test
    fun `dismiss duplicate does not save`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            uploadFlow = { flowOf(NetworkResult.Success("/p/abc.jpg")) }
            analyzeFlow = { flowOf(NetworkResult.Success(ShippingAnalysis("1Z999AA10123456784", "UPS", null, "{}"))) }
            duplicateFlow = { flowOf(NetworkResult.Success(true)) }
        }
        val vm = vm(repo)
        vm.startBatch(); advanceUntilIdle()
        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()

        vm.dismissDuplicateSave(); advanceUntilIdle()
        assertEquals(0, repo.createItemCallCount)
        assertNull(vm.uiState.value.confirm!!.pendingDuplicateTracking)
    }

    @Test
    fun `cancelConfirm returns to Recording without saving`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            uploadFlow = { flowOf(NetworkResult.Success("/p/abc.jpg")) }
            // 无运单号，避免自动保存干扰 cancel 测试
            analyzeFlow = { flowOf(NetworkResult.Success(ShippingAnalysis(null, null, null, null))) }
        }
        val vm = vm(repo)
        vm.startBatch(); advanceUntilIdle()
        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()

        vm.cancelConfirm()

        assertEquals(Phase.Recording, vm.uiState.value.phase)
        assertNotNull(vm.uiState.value.confirm)
        assertEquals("", vm.uiState.value.confirm!!.trackingNumber)
        assertEquals(0, repo.createItemCallCount)
    }

    @Test
    fun `confirmCloseBatch resets to Idle on success`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            closeFlow = { flowOf(NetworkResult.Loading, NetworkResult.Success(Unit)) }
        }
        val vm = vm(repo)
        vm.startBatch(); advanceUntilIdle()

        vm.confirmCloseBatch(); advanceUntilIdle()

        val s = vm.uiState.value
        assertEquals(Phase.Idle, s.phase)
        assertNull(s.batchId)
        assertTrue(s.items.isEmpty())
        assertEquals(DockMessage.BatchClosed("B-001"), s.message)
    }

    @Test
    fun `messageShown clears message`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Error("x", null)) }
        }
        val vm = vm(repo)
        vm.startBatch(); advanceUntilIdle()
        assertEquals(DockMessage.Text("x"), vm.uiState.value.message)

        vm.messageShown()
        assertNull(vm.uiState.value.message)
    }
}
