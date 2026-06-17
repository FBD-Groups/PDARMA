package com.pda.app

import androidx.lifecycle.SavedStateHandle
import com.pda.app.data.prefs.UserPreferences
import com.pda.app.data.NetworkResult
import com.pda.app.data.api.model.BatchInfo
import com.pda.app.data.api.model.CreateItemRequest
import com.pda.app.data.api.model.ReceivingItemUi
import com.pda.app.data.api.model.ShippingAnalysis
import com.pda.app.data.repository.ReceivingRepository
import com.pda.app.ui.dockreceiving.CompressedImage
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
    var lastCreateItemReq: CreateItemRequest? = null

    override fun createBatch(warehouseId: Int) = createBatchFlow()
    override fun uploadPhoto(bytes: ByteArray, filename: String) = uploadFlow()
    override fun analyzeShipping(base64: String) = analyzeFlow()
    override fun createItem(req: CreateItemRequest): Flow<NetworkResult<Int>> {
        lastCreateItemReq = req
        return createItemFlow()
    }
    override fun getItems(batchId: Int) = getItemsFlow()
    override fun closeBatch(batchId: Int) = closeFlow()

    private companion object {
        val ThrowingApi = object : com.pda.app.data.api.ReceivingApiService {
            override suspend fun createBatch(req: com.pda.app.data.api.model.CreateBatchRequest) = error("unused")
            override suspend fun uploadPhotos(file: okhttp3.MultipartBody.Part) = error("unused")
            override suspend fun analyze(req: com.pda.app.data.api.model.AnalyzeRequest) = error("unused")
            override suspend fun createItem(req: CreateItemRequest) = error("unused")
            override suspend fun getItems(batchId: Int) = error("unused")
            override suspend fun closeBatch(id: Int) = error("unused")
            override suspend fun getBatches(warehouseId: Int?, scanUser: String?, scanDateFrom: String?) = error("unused")
        }
    }
}

private class FakeImageEncoder : ImageEncoder {
    override suspend fun compress(file: File) = CompressedImage(byteArrayOf(1, 2, 3), "BASE64")
}

private class FakeUserPreferences(private var inputMethod: String? = null) : UserPreferences {
    override val lastUsername = flowOf<String?>(null)
    override val lastPassword = flowOf<String?>(null)
    override val rememberUsername = flowOf(true)
    override val selectedWarehouseId = flowOf<Int?>(null)
    override val dockInputMethod = flowOf(inputMethod)
    override suspend fun saveLoginCredentials(username: String, password: String, remember: Boolean) {}
    override suspend fun setSelectedWarehouseId(id: Int) {}
    override suspend fun setDockInputMethod(name: String) { inputMethod = name }
}

private fun vm(repo: ReceivingRepository, warehouseId: String? = "7"): DockReceivingViewModel =
    DockReceivingViewModel(
        repo,
        FakeImageEncoder(),
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
        assertEquals("创建批次失败（500）", s.message)
    }

    @Test
    fun `onPhotoCaptured runs upload and analyze, autofills fields`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            uploadFlow = { flowOf(NetworkResult.Loading, NetworkResult.Success("/p/abc.jpg")) }
            analyzeFlow = {
                flowOf(
                    NetworkResult.Loading,
                    NetworkResult.Success(ShippingAnalysis("1Z999AA10123456784", "fedex", null, "{}"))
                )
            }
        }
        val vm = vm(repo)
        vm.startBatch(); advanceUntilIdle()

        vm.onPhotoCaptured(File("capture.jpg"))
        advanceUntilIdle()

        val c = vm.uiState.value.confirm!!
        // 单屏内联：拍照后仍处于 Recording，confirm 字段内联显示在预览下方。
        assertEquals(Phase.Recording, vm.uiState.value.phase)
        assertEquals("/p/abc.jpg", c.photoPath)
        assertFalse(c.uploading)
        assertFalse(c.analyzing)
        assertEquals("1Z999AA10123456784", c.trackingNumber)
        assertEquals("FedEx", c.carrier)
        assertTrue(c.trackingAutoFilled)
        assertTrue(c.carrierAutoFilled)
        assertEquals("{}", c.rawJson)
        assertTrue(c.canSave) // 自动识别出运单号 → 可保存
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
        assertFalse(c.canSave) // 解析失败、无运单号 → 不可保存
        assertEquals(Phase.Recording, vm.uiState.value.phase)
    }

    @Test
    fun `no tracking after analyze blocks save until entered manually`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            uploadFlow = { flowOf(NetworkResult.Success("/p/abc.jpg")) }
            // 上传成功、解析成功但未识别出运单号
            analyzeFlow = { flowOf(NetworkResult.Success(ShippingAnalysis(null, "UPS", null, "{}"))) }
        }
        val vm = vm(repo)
        vm.startBatch(); advanceUntilIdle()
        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()

        // 照片已上传、解析结束，但没有运单号 → 仍不可保存
        assertFalse(vm.uiState.value.confirm!!.canSave)

        // 手工输入运单号后 → 可保存
        vm.onTrackingChanged("1Z999AA10123456784")
        assertTrue(vm.uiState.value.confirm!!.canSave)
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
        assertEquals("/p/abc.jpg", repo.lastCreateItemReq!!.photoPath)
        val s = vm.uiState.value
        assertEquals(Phase.Recording, s.phase)
        assertNull(s.confirm)
        assertEquals(1, s.itemCount)
        assertEquals(1, s.needsReviewCount)
    }

    @Test
    fun `saveItem sends needsReview false when tracking present`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            uploadFlow = { flowOf(NetworkResult.Success("/p/abc.jpg")) }
            analyzeFlow = { flowOf(NetworkResult.Success(ShippingAnalysis("1Z999AA10123456784", "UPS", null, "{}"))) }
            getItemsFlow = { flowOf(NetworkResult.Success(listOf(ReceivingItemUi(7, "1Z999AA10123456784", "UPS", false)))) }
        }
        val vm = vm(repo)
        vm.startBatch(); advanceUntilIdle()
        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()

        vm.saveItem(); advanceUntilIdle()

        assertEquals(false, repo.lastCreateItemReq!!.needsReview)
        assertEquals("1Z999AA10123456784", repo.lastCreateItemReq!!.trackingNumber)
    }

    @Test
    fun `cancelConfirm returns to Recording without saving`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            uploadFlow = { flowOf(NetworkResult.Success("/p/abc.jpg")) }
            analyzeFlow = { flowOf(NetworkResult.Success(ShippingAnalysis("1Z999AA10123456784", null, null, null))) }
        }
        val vm = vm(repo)
        vm.startBatch(); advanceUntilIdle()
        vm.onPhotoCaptured(File("capture.jpg")); advanceUntilIdle()

        vm.cancelConfirm()

        assertEquals(Phase.Recording, vm.uiState.value.phase)
        assertNull(vm.uiState.value.confirm)
    }

    @Test
    fun `confirmCloseBatch resets to Idle on success`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Success(BatchInfo(42, "B-001"))) }
            closeFlow = { flowOf(NetworkResult.Loading, NetworkResult.Success(Unit)) }
        }
        val vm = vm(repo)
        vm.startBatch(); advanceUntilIdle()

        vm.requestCloseBatch()
        assertTrue(vm.uiState.value.showCloseDialog)

        vm.confirmCloseBatch(); advanceUntilIdle()

        val s = vm.uiState.value
        assertEquals(Phase.Idle, s.phase)
        assertNull(s.batchId)
        assertTrue(s.items.isEmpty())
        assertFalse(s.showCloseDialog)
        assertEquals("B-001 closed", s.message)
    }

    @Test
    fun `messageShown clears message`() = runTest {
        val repo = FakeReceivingRepository().apply {
            createBatchFlow = { flowOf(NetworkResult.Error("x", null)) }
        }
        val vm = vm(repo)
        vm.startBatch(); advanceUntilIdle()
        assertEquals("x", vm.uiState.value.message)

        vm.messageShown()
        assertNull(vm.uiState.value.message)
    }
}
