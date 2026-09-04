package com.pda.app

import androidx.lifecycle.SavedStateHandle
import com.pda.app.data.NetworkResult
import com.pda.app.data.api.model.CreateItemRequest
import com.pda.app.data.api.model.ReceivingItemUi
import com.pda.app.data.repository.ReceivingRepository
import com.pda.app.ui.batchdetail.BatchDetailUiState
import com.pda.app.ui.batchdetail.BatchDetailViewModel
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeBatchReceivingRepository : ReceivingRepository(
    object : com.pda.app.data.api.ReceivingApiService {
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
) {
    var getItemsFlow: () -> Flow<NetworkResult<List<ReceivingItemUi>>> = {
        flowOf(
            NetworkResult.Success(
                listOf(
                    ReceivingItemUi(1, "875633277506", "FedEx", false, "Acme"),
                    ReceivingItemUi(2, "1Z999AA10123456784", "UPS", false, "Beta")
                )
            )
        )
    }
    var voidFlow: () -> Flow<NetworkResult<Unit>> = { flowOf(NetworkResult.Success(Unit)) }
    var lastVoidId: Int? = null

    override fun getItems(batchId: Int) = getItemsFlow()
    override fun voidItem(receivingItemId: Int): Flow<NetworkResult<Unit>> {
        lastVoidId = receivingItemId
        return voidFlow()
    }
}

class BatchDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(repo: FakeBatchReceivingRepository = FakeBatchReceivingRepository()) =
        BatchDetailViewModel(
            repo,
            SavedStateHandle(mapOf("batchId" to "42", "batchNumber" to "B20260903-043"))
        )

    @Test
    fun `load maps items into Success`() = runTest {
        val viewModel = vm()
        advanceUntilIdle()
        val state = viewModel.uiState.value as BatchDetailUiState.Success
        assertEquals(2, state.items.size)
        assertEquals("Acme", state.items[0].customerName)
    }

    @Test
    fun `voidItem removes row on success`() = runTest {
        val repo = FakeBatchReceivingRepository()
        val viewModel = vm(repo)
        advanceUntilIdle()

        viewModel.voidItem(1)
        advanceUntilIdle()

        assertEquals(1, repo.lastVoidId)
        val state = viewModel.uiState.value as BatchDetailUiState.Success
        assertEquals(listOf(2), state.items.map { it.receivingItemId })
        assertEquals(null, state.voidingItemId)
    }

    @Test
    fun `voidItem last row yields Empty`() = runTest {
        val repo = FakeBatchReceivingRepository().apply {
            getItemsFlow = {
                flowOf(NetworkResult.Success(listOf(
                    ReceivingItemUi(9, "T1", "UPS", false, "X")
                )))
            }
        }
        val viewModel = vm(repo)
        advanceUntilIdle()

        viewModel.voidItem(9)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is BatchDetailUiState.Empty)
    }

    @Test
    fun `voidItem keeps row and sets message on error`() = runTest {
        val repo = FakeBatchReceivingRepository().apply {
            voidFlow = { flowOf(NetworkResult.Error("作废失败（409）", 409)) }
        }
        val viewModel = vm(repo)
        advanceUntilIdle()

        viewModel.voidItem(1)
        advanceUntilIdle()

        val state = viewModel.uiState.value as BatchDetailUiState.Success
        assertEquals(2, state.items.size)
        assertEquals("作废失败（409）", state.message)
        assertEquals(null, state.voidingItemId)
    }
}
