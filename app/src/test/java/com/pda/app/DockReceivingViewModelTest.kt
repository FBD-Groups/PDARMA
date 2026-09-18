package com.pda.app

import androidx.lifecycle.SavedStateHandle
import com.pda.app.data.prefs.UserPreferences
import com.pda.app.data.NetworkResult
import com.pda.app.data.api.model.BatchInfo
import com.pda.app.data.api.model.CreateItemRequest
import com.pda.app.data.api.model.ReceivingAlertUi
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
) : ReceivingRepository(api, kotlinx.serialization.json.Json { ignoreUnknownKeys = true }) {
    var createBatchFlow: () -> Flow<NetworkResult<BatchInfo>> = { flowOf(NetworkResult.Loading) }
    var uploadFlow: () -> Flow<NetworkResult<String>> = { flowOf(NetworkResult.Loading) }
    var analyzeFlow: () -> Flow<NetworkResult<ShippingAnalysis>> = { flowOf(NetworkResult.Loading) }
    var createItemFlow: () -> Flow<NetworkResult<Int>> = { flowOf(NetworkResult.Success(1)) }
    var getItemsFlow: () -> Flow<NetworkResult<List<ReceivingItemUi>>> = { flowOf(NetworkResult.Success(emptyList())) }
    var closeFlow: () -> Flow<NetworkResult<Unit>> = { flowOf(NetworkResult.Success(Unit)) }
    var duplicateFlow: () -> Flow<NetworkResult<Boolean>> = { flowOf(NetworkResult.Success(false)) }
    var matchAlertFlow: () -> Flow<NetworkResult<ReceivingAlertUi?>> = { flowOf(NetworkResult.Success(null)) }
    var lastCreateItemReq: CreateItemRequest? = null
    var createItemCallCount: Int = 0
    var acknowledgeAlertBestEffortCallCount: Int = 0
    var lastAcknowledgedAlertId: String? = null

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
    override fun matchAlert(trackingNumber: String) = matchAlertFlow()
    override fun acknowledgeAlert(id: String) = flowOf(NetworkResult.Success(Unit))
    override fun acknowledgeAlertBestEffort(id: String) {
        acknowledgeAlertBestEffortCallCount++
        lastAcknowledgedAlertId = id
    }

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
            override suspend fun matchReceivingAlert(trackingNumber: String) = error("unused")
            override suspend fun acknowledgeReceivingAlert(id: String) = error("unused")
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

private class FakeDockSoundPlayer : com.pda.app.ui.dockreceiving.DockSoundPlayer {
    var successCount = 0
        private set
    var beepCount = 0
        private set
    override fun playSuccess() { successCount++ }
    override fun playBeep() { beepCount++ }
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
    customers: List<com.pda.app.data.api.model.ActiveCustomer> = emptyList(),
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    // 用跟测试相同的（虚拟时间）dispatcher，advanceUntilIdle() 才能正确等到 ensureLoaded() 完成，
    // 而不是悬在真实的 Default 线程池上——见 CustomerDirectory 的 dispatcher 参数注释。
    // 大多数测试用默认的即可；需要在测试中途操纵 session/客户数据源时（比如验证缓存重新加载）
    // 才需要自己构造一个传进来。
    customerDirectory: com.pda.app.data.session.CustomerDirectory = com.pda.app.data.session.CustomerDirectory(
        FakeCustomerRepository(customers),
        com.pda.app.data.session.SessionManager(),
        dispatcher
    ),
    soundPlayer: FakeDockSoundPlayer = FakeDockSoundPlayer()
): DockReceivingViewModel =
    DockReceivingViewModel(
        repo,
        customerDirectory,
        FakeImageEncoder(),
        FakeBarcodeDecoder(barcode),
        soundPlayer,
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
        val vm = vm(repo, dispatcher = dispatcher)

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
        val vm = vm(repo, dispatcher = dispatcher)

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
        val vm = vm(repo, dispatcher = dispatcher)
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
        val vm = vm(repo, dispatcher = dispatcher) // empty customer list → unmatched
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
            customers = listOf(com.pda.app.data.api.model.ActiveCustomer(99, "UF00162", "RMA Technology")),
            dispatcher = dispatcher
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
        val vm = vm(repo, dispatcher = dispatcher)
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
        val vm = vm(repo, dispatcher = dispatcher)
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
        val vm = vm(repo, dispatcher = dispatcher)
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
        val vm = vm(repo, dispatcher = dispatcher)
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
        val vm = vm(repo, dispatcher = dispatcher)
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
        val vm = vm(repo, barcode = "1Z999AA10123456784", dispatcher = dispatcher)
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
        val vm = vm(repo, barcode = null, dispatcher = dispatcher)
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
        val vm = vm(repo, barcode = "1Z999AA10123456784", dispatcher = dispatcher)
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
        val vm = vm(repo, barcode = "1Z999AA10123456784", dispatcher = dispatcher)
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
        val vm = vm(repo, dispatcher = dispatcher)
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
        val vm = vm(repo, dispatcher = dispatcher)
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
        val vm = vm(repo, dispatcher = dispatcher)
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
        val vm = vm(repo, dispatcher = dispatcher)
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
        val vm = vm(repo, dispatcher = dispatcher)
        vm.startBatch(); advanceUntilIdle()
        assertEquals(DockMessage.Text("x"), vm.uiState.value.message)

        vm.messageShown()
        assertNull(vm.uiState.value.message)
    }

    // ── Receiving Alert 门禁（docs/pda对齐.md 第 1 节）────────────────────────────────

    private fun alertRepo(matchAlertFlow: () -> Flow<NetworkResult<ReceivingAlertUi?>> = { flowOf(NetworkResult.Success(null)) }) =
        FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            uploadFlow = { flowOf(NetworkResult.Success("/p/abc.jpg")) }
            analyzeFlow = { flowOf(NetworkResult.Success(ShippingAnalysis("1Z999AA10123456784", "UPS", null, "{}"))) }
            getItemsFlow = { flowOf(NetworkResult.Success(emptyList())) }
            this.matchAlertFlow = matchAlertFlow
        }

    @Test
    fun `matchAlert hit sets pendingAlert and keeps the gate active`() = runTest {
        val repo = alertRepo { flowOf(NetworkResult.Success(ReceivingAlertUi("alert-1", "Please send to DJI"))) }
        val vm = vm(repo, dispatcher = dispatcher)
        vm.startBatch(); advanceUntilIdle()
        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()

        val s = vm.uiState.value
        assertEquals(1, repo.createItemCallCount)
        assertEquals("alert-1", s.pendingAlert?.id)
        assertEquals("1Z999AA10123456784", s.pendingAlert?.trackingNo)
        assertEquals("Please send to DJI", s.pendingAlert?.instruction)
        assertTrue(s.alertGateActive)
        assertFalse(s.checkingAlert) // 已经从"查询中"转成"命中待确认"
    }

    @Test
    fun `matchAlert miss opens the gate immediately, no dialog`() = runTest {
        val repo = alertRepo() // 默认 Success(null) — 未命中
        val vm = vm(repo, dispatcher = dispatcher)
        vm.startBatch(); advanceUntilIdle()
        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()

        val s = vm.uiState.value
        assertEquals(1, repo.createItemCallCount)
        assertNull(s.pendingAlert)
        assertFalse(s.alertGateActive)
        assertEquals(CaptureStatus.Success, s.captureStatus)
    }

    @Test
    fun `alert gate is already active during refreshItems, before matchAlert even starts (P0 regression)`() = runTest {
        // 回归测试：门禁必须在 createItem 成功后立刻同步打开，不能等到 gateOnReceivingAlert()
        // 内部才设 checkingAlert=true——之前的实现里 refreshItems() 排在开门禁前面，
        // refreshItems 挂起期间门禁形同虚设。这里用一个可控的 getItemsFlow 卡住 refreshItems，
        // 在它返回之前验证门禁已经生效、新的拍照会被挡住。
        val itemsDeferred = CompletableDeferred<NetworkResult<List<ReceivingItemUi>>>()
        val repo = alertRepo().apply {
            getItemsFlow = { flow { emit(NetworkResult.Loading); emit(itemsDeferred.await()) } }
        }
        val vm = vm(repo, dispatcher = dispatcher)
        vm.startBatch(); advanceUntilIdle()

        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()
        // refreshItems() 还挂起着——matchAlert 根本还没被调用到——门禁应该已经是开着的。
        assertEquals(1, repo.createItemCallCount)
        assertTrue(vm.uiState.value.checkingAlert)
        assertTrue(vm.uiState.value.alertGateActive)

        vm.onPhotoCaptured(File("capture2.jpg")); advanceUntilIdle()
        assertEquals(1, repo.createItemCallCount)

        itemsDeferred.complete(NetworkResult.Success(emptyList()))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.alertGateActive)
    }

    @Test
    fun `alert gate blocks new capture while matchAlert is still in flight`() = runTest {
        val alertDeferred = CompletableDeferred<NetworkResult<ReceivingAlertUi?>>()
        val repo = alertRepo { flow { emit(NetworkResult.Loading); emit(alertDeferred.await()) } }
        val vm = vm(repo, dispatcher = dispatcher)
        vm.startBatch(); advanceUntilIdle()

        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()
        assertEquals(1, repo.createItemCallCount)
        assertTrue(vm.uiState.value.checkingAlert)
        assertTrue(vm.uiState.value.alertGateActive)

        // matchAlert 还没返回期间再拍一张：应该是空操作，不重置草稿也不再入库。
        vm.onPhotoCaptured(File("capture2.jpg")); advanceUntilIdle()
        assertEquals(1, repo.createItemCallCount)

        alertDeferred.complete(NetworkResult.Success(null))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.alertGateActive)
    }

    @Test
    fun `pending alert blocks new capture until confirmAlertAck`() = runTest {
        val repo = alertRepo { flowOf(NetworkResult.Success(ReceivingAlertUi("alert-1", "Please send to DJI"))) }
        val vm = vm(repo, dispatcher = dispatcher)
        vm.startBatch(); advanceUntilIdle()
        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()
        assertEquals(1, repo.createItemCallCount)
        assertNotNull(vm.uiState.value.pendingAlert)

        // 命中后、用户确认前，再拍一张应该被挡住。
        vm.onPhotoCaptured(File("capture2.jpg")); advanceUntilIdle()
        assertEquals(1, repo.createItemCallCount)

        vm.confirmAlertAck()
        assertNull(vm.uiState.value.pendingAlert)
        assertFalse(vm.uiState.value.alertGateActive)

        // 门禁打开之后，新照片正常走流程再次自动入库。
        vm.onPhotoCaptured(File("capture3.jpg")); advanceUntilIdle()
        assertEquals(2, repo.createItemCallCount)
    }

    @Test
    fun `confirmAlertAck reopens the gate synchronously and calls acknowledgeAlertBestEffort without a viewModelScope launch`() = runTest {
        val repo = alertRepo { flowOf(NetworkResult.Success(ReceivingAlertUi("alert-1", "Please send to DJI"))) }
        val vm = vm(repo, dispatcher = dispatcher)
        vm.startBatch(); advanceUntilIdle()
        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()
        assertNotNull(vm.uiState.value.pendingAlert)

        vm.confirmAlertAck()
        // 故意不调用 advanceUntilIdle()：如果 acknowledgeAlertBestEffort 被包进了
        // viewModelScope.launch{...}，这里应该还没被调用到——它必须是一次同步调用
        // （内部自己 launch 到 Repository 自己的 scope），门禁和 pendingAlert 也必须
        // 同步清空，不依赖任何协程调度就能立刻放行。
        assertNull(vm.uiState.value.pendingAlert)
        assertFalse(vm.uiState.value.alertGateActive)
        assertEquals(1, repo.acknowledgeAlertBestEffortCallCount)
        assertEquals("alert-1", repo.lastAcknowledgedAlertId)
    }

    @Test
    fun `scan mode also gates on receiving alert`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            getItemsFlow = { flowOf(NetworkResult.Success(emptyList())) }
            matchAlertFlow = { flowOf(NetworkResult.Success(ReceivingAlertUi("alert-1", "Please send to DJI"))) }
        }
        val vm = vm(repo, dispatcher = dispatcher)
        vm.startBatch(); advanceUntilIdle()

        vm.scanItem("1Z999AA10123456784"); advanceUntilIdle()
        assertEquals(1, repo.createItemCallCount)
        assertNotNull(vm.uiState.value.pendingAlert)

        // 命中待确认期间再扫一次应该被挡住。
        vm.scanItem("1Z999AA10123456785"); advanceUntilIdle()
        assertEquals(1, repo.createItemCallCount)

        vm.confirmAlertAck()
        vm.scanItem("1Z999AA10123456785"); advanceUntilIdle()
        assertEquals(2, repo.createItemCallCount)
    }

    @Test
    fun `scanItem rejected by the alert gate plays a beep (medium regression)`() = runTest {
        // 扫码枪硬件不知道门禁状态，会照常把按键事件送进来；UI 层的 ScanInputField 也不会
        // 清空输入框（见 DockReceivingScreen 里的注释），但操作员现场往往不盯着屏幕看，
        // 只有这一声提示音能立刻告诉他们这次扫描没有被接受。见 docs/pda对齐.md 第 1 节。
        val repo = alertRepo { flowOf(NetworkResult.Success(ReceivingAlertUi("alert-1", "Please send to DJI"))) }
        val soundPlayer = FakeDockSoundPlayer()
        val vm = vm(repo, dispatcher = dispatcher, soundPlayer = soundPlayer)
        vm.startBatch(); advanceUntilIdle()
        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()
        assertNotNull(vm.uiState.value.pendingAlert)

        val beepsBefore = soundPlayer.beepCount
        vm.scanItem("1Z999AA10123456785")
        assertEquals(beepsBefore + 1, soundPlayer.beepCount)
    }

    @Test
    fun `submitting lock covers the createItem request itself, before it even succeeds (P0 regression)`() = runTest {
        // 之前的门禁只在 createItem 成功之后才生效（checkingAlert 只在成功回调里被设置）；
        // createItem 请求本身（以及它前面的查重请求）挂起期间完全不受保护，第二次拍照能
        // 直接绕过去再发起一次 createItem。两次都成功的话，会竞争同一个
        // pendingAlert/pendingGateOpen，导致提醒覆盖或丢失。见 docs/pda对齐.md 第 1 节。
        val createItemDeferred = CompletableDeferred<NetworkResult<Int>>()
        val repo = alertRepo().apply {
            createItemFlow = { flow { emit(NetworkResult.Loading); emit(createItemDeferred.await()) } }
        }
        val vm = vm(repo, dispatcher = dispatcher)
        vm.startBatch(); advanceUntilIdle()

        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()
        // createItem 还没返回：门禁应该已经锁上了，不用等它成功。
        assertEquals(1, repo.createItemCallCount)
        assertTrue(vm.uiState.value.submitting)
        assertTrue(vm.uiState.value.alertGateActive)

        // 这期间再拍一张应该是空操作，不应该发起第二次 createItem。
        vm.onPhotoCaptured(File("capture2.jpg")); advanceUntilIdle()
        assertEquals(1, repo.createItemCallCount)

        createItemDeferred.complete(NetworkResult.Success(1))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.alertGateActive)
    }

    @Test
    fun `submitting lock also covers the duplicate-check request before performSave is even reached`() = runTest {
        val duplicateDeferred = CompletableDeferred<NetworkResult<Boolean>>()
        val repo = alertRepo().apply {
            duplicateFlow = { flow { emit(NetworkResult.Loading); emit(duplicateDeferred.await()) } }
        }
        val vm = vm(repo, dispatcher = dispatcher)
        vm.startBatch(); advanceUntilIdle()

        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()
        // 查重请求还没返回，createItem 都还没被调用——门禁也应该已经锁上了。
        assertEquals(0, repo.createItemCallCount)
        assertTrue(vm.uiState.value.submitting)
        assertTrue(vm.uiState.value.alertGateActive)

        vm.onPhotoCaptured(File("capture2.jpg")); advanceUntilIdle()
        assertEquals(0, repo.createItemCallCount)

        duplicateDeferred.complete(NetworkResult.Success(false))
        advanceUntilIdle()
        assertEquals(1, repo.createItemCallCount)
    }

    @Test
    fun `dismissing the duplicate dialog releases the submitting lock`() = runTest {
        val repo = alertRepo().apply {
            duplicateFlow = { flowOf(NetworkResult.Success(true)) }
        }
        val vm = vm(repo, dispatcher = dispatcher)
        vm.startBatch(); advanceUntilIdle()
        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()
        assertTrue(vm.uiState.value.submitting)

        vm.dismissDuplicateSave()
        assertFalse(vm.uiState.value.submitting)
        assertFalse(vm.uiState.value.alertGateActive)
    }

    @Test
    fun `confirmCloseBatch and requestExit are blocked while the alert gate is active (P1 regression)`() = runTest {
        val repo = alertRepo { flowOf(NetworkResult.Success(ReceivingAlertUi("alert-1", "Please send to DJI"))) }
        val vm = vm(repo, dispatcher = dispatcher)
        vm.startBatch(); advanceUntilIdle()
        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()
        assertNotNull(vm.uiState.value.pendingAlert)

        // 门禁开着期间关批次应该是空操作——批次还在，没有触发 closeBatch。
        vm.confirmCloseBatch(); advanceUntilIdle()
        assertEquals(Phase.Recording, vm.uiState.value.phase)
        assertNotNull(vm.uiState.value.batchId)

        // 用户确认提醒之后，关批次才能正常生效。
        vm.confirmAlertAck()
        vm.confirmCloseBatch(); advanceUntilIdle()
        assertEquals(Phase.Idle, vm.uiState.value.phase)
    }

    @Test
    fun `runAnalyze re-verifies the customer cache before matching, picking up a mid-batch reload (P1 regression)`() = runTest {
        // startBatch() 只在开批那一刻调用一次 ensureLoaded()；如果那次加载时列表是空的
        // （或者期间 session 变化导致缓存被清空），后续拍照如果只读旧快照，第一单的
        // alias/code 匹配会一直落空。runAnalyze 必须在真正用 activeCustomers 之前
        // 自己再确认一次已加载（大多数情况下是 loaded=true 的零开销 no-op，只有在
        // 缓存确实失效时才会真正重新拉一次）。见 docs/pda对齐.md 第 2 节。
        val customerRepo = object : com.pda.app.data.repository.CustomerRepository(
            object : com.pda.app.data.api.CustomerApiService {
                override suspend fun getCustomers() = error("unused")
            }
        ) {
            var customers: List<com.pda.app.data.api.model.ActiveCustomer> = emptyList()
            var callCount = 0
            override fun getActiveCustomers(): Flow<NetworkResult<List<com.pda.app.data.api.model.ActiveCustomer>>> {
                callCount++
                return flowOf(NetworkResult.Success(customers))
            }
        }
        val sessionManager = com.pda.app.data.session.SessionManager()
        val customerDirectory =
            com.pda.app.data.session.CustomerDirectory(customerRepo, sessionManager, dispatcher)

        val repo = alertRepo().apply {
            analyzeFlow = {
                flowOf(
                    NetworkResult.Success(
                        ShippingAnalysis(
                            "1Z999AA10123456784", "UPS", null, "{}",
                            customerCode = "UF00162-RMA", customerName = null
                        )
                    )
                )
            }
        }
        val vm = vm(repo, dispatcher = dispatcher, customerDirectory = customerDirectory)
        vm.startBatch(); advanceUntilIdle()
        assertEquals(1, customerRepo.callCount)

        // 批次开着期间客户主数据"补上了"，同时 session 发生变化导致缓存失效——
        // 模拟"第一次加载时机太早/缓存中途失效"的场景。
        customerRepo.customers = listOf(com.pda.app.data.api.model.ActiveCustomer(99, "UF00162", "RMA Technology"))
        sessionManager.start("t2", com.pda.app.data.api.model.UserInfoDto("carol", "carol", "carol@x.com", "Carol"))
        advanceUntilIdle()

        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()

        assertEquals(2, customerRepo.callCount) // runAnalyze 自己又确认了一次加载
        assertEquals("RMA Technology", repo.lastCreateItemReq!!.customerName)
        assertEquals(99L, repo.lastCreateItemReq!!.customerId)
    }
}
