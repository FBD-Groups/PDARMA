package com.pda.app.ui.dockreceiving

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pda.app.data.NetworkResult
import com.pda.app.data.api.model.CreateItemRequest
import com.pda.app.data.prefs.UserPreferences
import com.pda.app.data.repository.ReceivingRepository
import com.pda.app.data.session.CustomerDirectory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DockReceivingViewModel @Inject constructor(
    private val repo: ReceivingRepository,
    private val customerDirectory: CustomerDirectory,
    private val encoder: ImageEncoder,
    private val barcodeDecoder: BarcodeDecoder,
    private val soundPlayer: DockSoundPlayer,
    private val prefs: UserPreferences,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "PDA/DockReceivingViewModel"
    }

    private val warehouseId: Int? =
        savedStateHandle.get<String>("warehouseId")?.toIntOrNull()

    /** 活跃客户列表快照，用 UF 编码/Alias 解析真实客户名——全局缓存，见 [CustomerDirectory]。 */
    private val activeCustomers get() = customerDirectory.customers.value

    private val _uiState = MutableStateFlow(DockReceivingUiState())
    val uiState: StateFlow<DockReceivingUiState> = _uiState.asStateFlow()

    /** 持久化记住的录入方式（默认 Picture）。 */
    val inputMethod: StateFlow<InputMethod> = prefs.dockInputMethod
        .map { name -> InputMethod.entries.firstOrNull { it.name == name } ?: InputMethod.Picture }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InputMethod.Picture)

    fun setInputMethod(method: InputMethod) {
        viewModelScope.launch { prefs.setDockInputMethod(method.name) }
    }

    fun startBatch(method: InputMethod = InputMethod.Picture) {
        val wid = warehouseId
        if (wid == null) {
            _uiState.update { it.copy(message = DockMessage.SelectWarehouseFirst) }
            return
        }
        viewModelScope.launch {
            repo.createBatch(wid).collect { result ->
                when (result) {
                    is NetworkResult.Loading -> _uiState.update { it.copy(isBusy = true) }
                    is NetworkResult.Success -> {
                        customerDirectory.ensureLoaded()
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                phase = Phase.Recording,
                                inputMethod = method,
                                batchId = result.data.batchId,
                                batchNumber = result.data.batchNumber,
                                items = emptyList(),
                                // 拍照模式：进入即给一个空草稿，Tracking # 框常驻可见可输。
                                confirm = if (method == InputMethod.Picture) ConfirmState() else null
                            )
                        }
                    }
                    is NetworkResult.Error -> _uiState.update {
                        it.copy(isBusy = false, message = DockMessage.Text(result.message))
                    }
                }
            }
        }
    }

    fun onPhotoCaptured(file: File) {
        // 收货预警门禁开着期间不接受新照片——见 docs/pda对齐.md 第 1 节"门禁竞态"。
        // 主要防线是 UI 层禁用快门按钮，这里是第二道防线。
        if (_uiState.value.alertGateActive) return
        // 重拍替换上一张待处理照片，先删旧临时文件避免缓存泄漏。
        // 保留用户可能已手输的运单号/承运商，仅把照片与上传/识别状态挂上当前草稿。
        _uiState.value.confirm?.photoFile?.delete()
        _uiState.update {
            val prev = it.confirm ?: ConfirmState()
            // 新照片：清掉上一张的自动识别结果（条码/AI），保留用户手输的运单号。
            val keepTyped = if (prev.trackingAutoFilled) "" else prev.trackingNumber
            val keepCustomer = if (prev.customerAutoFilled) "" else prev.customerName
            val keepCustomerId = if (prev.customerAutoFilled) null else prev.customerId
            it.copy(
                confirm = prev.copy(
                    photoFile = file,
                    uploading = true,
                    analyzing = true,
                    photoPath = null,
                    uploadFailed = false,
                    barcodeDecoding = true,
                    barcodeTracking = null,
                    trackingFromBarcode = false,
                    trackingNumber = keepTyped,
                    trackingAutoFilled = false,
                    customerName = keepCustomer,
                    customerId = keepCustomerId,
                    customerAutoFilled = false,
                    carrier = "",
                    carrierAutoFilled = false,
                    autoSubmitConsumed = false,
                    pendingDuplicateTracking = null,
                    rawJson = null
                ),
                captureStatus = CaptureStatus.Idle
            )
        }
        // 条码解码走原始全分辨率照片；上传走原图（不裁不缩边）；AI 走 MAX_EDGE 压缩图。
        viewModelScope.launch { runBarcode(file) }
        viewModelScope.launch {
            try {
                val uploadBytes = encoder.prepareForUpload(file)
                runUpload(uploadBytes, file.name)
            } catch (e: Exception) {
                Log.e(TAG, "prepareForUpload: ${e.message}", e)
                soundPlayer.playBeep()
                _uiState.update {
                    it.copy(
                        confirm = it.confirm?.copy(uploading = false, uploadFailed = true),
                        message = DockMessage.PhotoProcessingFailed,
                        captureStatus = CaptureStatus.Failure
                    )
                }
            }
        }
        viewModelScope.launch {
            try {
                val img = encoder.compress(file)
                runAnalyze(img.base64)
            } catch (e: Exception) {
                Log.e(TAG, "compress: ${e.message}", e)
                soundPlayer.playBeep()
                _uiState.update {
                    it.copy(
                        confirm = it.confirm?.copy(analyzing = false),
                        message = DockMessage.PhotoProcessingFailed,
                        captureStatus = CaptureStatus.Failure
                    )
                }
            }
        }
    }

    /** 本地解出运单号条码：立刻写入 Tracking 栏（过滤 FWD 等）；AI 返回后以 AI 为准覆盖。 */
    private suspend fun runBarcode(file: File) {
        val tracking = barcodeDecoder.decodeTracking(file)
            ?.let { sanitizeTracking(it) }
            ?.ifBlank { null }
        _uiState.update { state ->
            val c = state.confirm ?: return@update state
            if (tracking.isNullOrBlank()) {
                state.copy(confirm = c.copy(barcodeDecoding = false))
            } else {
                state.copy(
                    confirm = c.copy(
                        barcodeDecoding = false,
                        barcodeTracking = tracking,
                        trackingNumber = tracking,
                        trackingAutoFilled = true,
                        trackingFromBarcode = true
                    )
                )
            }
        }
        maybeAutoSubmit()
    }

    private suspend fun runUpload(bytes: ByteArray, filename: String) {
        repo.uploadPhoto(bytes, filename).collect { result ->
            when (result) {
                is NetworkResult.Loading -> {}
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(confirm = it.confirm?.copy(uploading = false, photoPath = result.data, uploadFailed = false))
                    }
                    maybeAutoSubmit()
                }
                is NetworkResult.Error -> {
                    soundPlayer.playBeep()
                    _uiState.update {
                        it.copy(
                            confirm = it.confirm?.copy(uploading = false, uploadFailed = true),
                            message = DockMessage.Text(result.message),
                            captureStatus = CaptureStatus.Failure
                        )
                    }
                }
            }
        }
    }

    private suspend fun runAnalyze(base64: String) {
        repo.analyzeShipping(base64).collect { result ->
            when (result) {
                is NetworkResult.Loading -> {}
                is NetworkResult.Success -> {
                    // 正式用 activeCustomers 做 alias/code 匹配之前再确认一次客户列表已加载——
                    // startBatch() 时已经调用过一次，这里大多数情况下是 loaded=true 的快速
                    // no-op；但如果那次加载失败，或者 session 在这批次期间发生变化导致缓存被
                    // 清空，这里能兜底重试，避免用空列表把第一单错判成"未匹配"。不能只在
                    // startBatch() 调一次就假设整个批次期间缓存一直有效。见 docs/pda对齐.md 第 2 节。
                    customerDirectory.ensureLoaded()
                    var noTracking = false
                    _uiState.update { state ->
                        val c = state.confirm ?: return@update state
                        val carrier = normalizeCarrier(result.data.carrier)
                        // sanitize 已把 FedEx 96 长码收成末 12 位
                        val aiTracking = sanitizeTracking(result.data.trackingNumber)
                        val (resolvedId, resolvedName) = resolveCustomerFromAnalyze(
                            result.data.customerCode,
                            result.data.customerName,
                            activeCustomers
                        )
                        // AI 有有效运单号则覆盖条码（避免 FWD 等内部码抢先入库）；AI 无号才用条码。
                        val fromBarcode = aiTracking.isBlank() && c.barcodeTracking != null
                        val merged = aiTracking.ifBlank {
                            c.barcodeTracking
                                ?: shortenFedExTracking(c.trackingNumber.replace("\\s+".toRegex(), ""))
                        }
                        val resolvedCarrier = when {
                            carrier.isNotBlank() -> carrier
                            wasFedExLongBarcode(result.data.trackingNumber) -> "FedEx"
                            fromBarcode && wasFedExLongBarcode(c.barcodeTracking) -> "FedEx"
                            else -> c.carrier
                        }
                        noTracking = merged.isBlank()
                        state.copy(
                            confirm = c.copy(
                                analyzing = false,
                                trackingNumber = merged,
                                carrier = if (resolvedCarrier.isNotBlank()) resolvedCarrier else c.carrier,
                                customerName = when {
                                    resolvedName.isNotBlank() -> resolvedName
                                    else -> c.customerName
                                },
                                customerId = when {
                                    resolvedName.isNotBlank() -> resolvedId
                                    else -> c.customerId
                                },
                                trackingAutoFilled = fromBarcode || aiTracking.isNotBlank(),
                                carrierAutoFilled = resolvedCarrier.isNotBlank(),
                                customerAutoFilled = resolvedName.isNotBlank(),
                                trackingFromBarcode = fromBarcode,
                                rawJson = result.data.raw
                            ),
                            message = if (noTracking) DockMessage.TrackingNotRecognized else state.message,
                            captureStatus = if (noTracking) CaptureStatus.Failure else state.captureStatus
                        )
                    }
                    if (noTracking) soundPlayer.playBeep()
                    maybeAutoSubmit()
                }
                is NetworkResult.Error -> {
                    var playedBeep = false
                    _uiState.update { state ->
                        val c = state.confirm ?: return@update state
                        if (c.barcodeTracking != null) {
                            state.copy(confirm = c.copy(
                                analyzing = false,
                                trackingNumber = c.barcodeTracking,
                                trackingAutoFilled = true,
                                trackingFromBarcode = true
                            ))
                        } else {
                            playedBeep = true
                            state.copy(
                                confirm = c.copy(analyzing = false),
                                message = DockMessage.Text(result.message),
                                captureStatus = CaptureStatus.Failure
                            )
                        }
                    }
                    if (playedBeep) soundPlayer.playBeep()
                    maybeAutoSubmit()
                }
            }
        }
    }

    fun onTrackingChanged(v: String) =
        _uiState.update {
            it.copy(confirm = it.confirm?.copy(
                trackingNumber = v,
                trackingAutoFilled = false,
                autoSubmitConsumed = true
            ))
        }

    fun onCarrierChanged(v: String) =
        _uiState.update { it.copy(confirm = it.confirm?.copy(carrier = v, carrierAutoFilled = false)) }

    fun onCustomerNameChanged(v: String) =
        _uiState.update { it.copy(confirm = it.confirm?.copy(customerName = v, customerId = null, customerAutoFilled = false)) }

    fun onConditionChanged(v: String) =
        _uiState.update { it.copy(confirm = it.confirm?.copy(condition = v)) }

    fun cancelConfirm() {
        _uiState.value.confirm?.photoFile?.delete()
        _uiState.update { it.copy(confirm = ConfirmState()) }
    }

    fun dismissDuplicateSave() {
        // 放弃这次提交：连带释放门禁（submitting 从 beginSaveWithDuplicateCheck() 一路锁到这里）。
        _uiState.update {
            it.copy(confirm = it.confirm?.copy(pendingDuplicateTracking = null), submitting = false)
        }
    }

    fun confirmDuplicateSave() {
        _uiState.update { it.copy(confirm = it.confirm?.copy(pendingDuplicateTracking = null)) }
        performSave()
    }

    /** 识别流水线结束后，若具备条件则自动查重并入库（每张照片最多一次）。 */
    private fun maybeAutoSubmit() {
        // 收货预警门禁开着期间不提交下一件——见 docs/pda对齐.md 第 1 节"命中后门禁不能被绕过"。
        // 不标记 autoSubmitConsumed，门禁关闭后如果还有机会重新触发，仍然可以正常提交。
        if (_uiState.value.alertGateActive) return
        var shouldSubmit = false
        _uiState.update { state ->
            val c = state.confirm ?: return@update state
            if (!c.readyForAutoSubmit) return@update state
            shouldSubmit = true
            state.copy(confirm = c.copy(autoSubmitConsumed = true))
        }
        if (shouldSubmit) beginSaveWithDuplicateCheck()
    }

    fun saveItem() {
        if (_uiState.value.alertGateActive) return
        val c = _uiState.value.confirm ?: return
        if (!c.canSave && c.trackingNumber.isBlank() && c.photoPath == null) return
        _uiState.update { it.copy(confirm = it.confirm?.copy(autoSubmitConsumed = true)) }
        beginSaveWithDuplicateCheck()
    }

    private fun beginSaveWithDuplicateCheck() {
        val state = _uiState.value
        val c = state.confirm ?: return
        val tracking = shortenFedExTracking(c.trackingNumber.replace("\\s+".toRegex(), ""))
        if (tracking.isBlank() && c.photoPath == null) return
        // 门禁从"决定提交这一件"这一刻就锁上，一路锁到 createItem 网络请求、刷新列表、查询提醒
        // 全部结束（或命中后用户确认）——不能只在 createItem 成功之后才锁，否则查重请求和
        // createItem 请求本身挂起期间完全不受保护，操作员能在这段窗口里再拍一张/再扫一次，
        // 两次 createItem 并发成功会竞争同一个 pendingAlert/pendingGateOpen，导致提醒覆盖或
        // 丢失。见 docs/pda对齐.md 第 1 节"createItem 请求期间仍可开始下一件"。
        _uiState.update { it.copy(submitting = true) }
        if (tracking.isBlank()) {
            // 无运单号：跳过查重，直接存（needsReview）。
            performSave()
            return
        }
        viewModelScope.launch {
            repo.isDuplicateTracking(tracking).collect { result ->
                when (result) {
                    is NetworkResult.Loading -> {}
                    is NetworkResult.Success -> {
                        if (result.data) {
                            soundPlayer.playBeep()
                            _uiState.update {
                                val cur = it.confirm
                                it.copy(
                                    confirm = cur?.copy(
                                        // 弹窗时确保顶栏已写入运单号（避免只在 chip 可见）。
                                        trackingNumber = tracking.ifBlank { cur.trackingNumber },
                                        trackingAutoFilled = true,
                                        pendingDuplicateTracking = tracking
                                    )
                                )
                            }
                        } else {
                            performSave()
                        }
                    }
                    is NetworkResult.Error -> performSave() // 不应发生；repo 已吞异常
                }
            }
        }
    }

    private fun performSave() {
        val state = _uiState.value
        val c = state.confirm ?: return
        val bid = state.batchId ?: return
        val tracking = shortenFedExTracking(c.trackingNumber.replace("\\s+".toRegex(), ""))
        if (tracking.isBlank() && c.photoPath == null) return
        val customer = c.customerName.trim()
        val req = CreateItemRequest(
            receivingBatchId = bid,
            trackingNumber = tracking.ifBlank { null },
            carrier = c.carrier.ifBlank { null },
            customerId = c.customerId,
            customerName = customer.ifBlank { null },
            condition = c.condition.ifBlank { null },
            photoPaths = c.photoPath?.let { listOf(it) },
            source = if (c.trackingFromBarcode) "Barcode" else "AI",
            rawJson = c.rawJson,
            needsReview = tracking.isBlank()
        )
        _uiState.update { it.copy(confirm = it.confirm?.copy(saving = true)) }
        viewModelScope.launch {
            repo.createItem(req).collect { result ->
                when (result) {
                    is NetworkResult.Loading -> {}
                    is NetworkResult.Success -> {
                        soundPlayer.playSuccess()
                        c.photoFile?.delete()
                        // 门禁必须在任何 suspend 调用之前、同步打开——之前的实现把 checkingAlert=true
                        // 放在 gateOnReceivingAlert() 内部，而下面的 refreshItems() 排在它前面，
                        // refreshItems 网络请求挂起期间门禁形同虚设，操作员能在这段窗口里绕过去
                        // 继续拍照/提交。见 docs/pda对齐.md 第 1 节。
                        _uiState.update {
                            it.copy(
                                confirm = it.confirm?.copy(saving = false),
                                captureStatus = CaptureStatus.Success,
                                checkingAlert = true
                            )
                        }
                        refreshItems(bid)
                        gateOnReceivingAlert(tracking) {
                            // 对齐 web：入库后保留 Tracking / Customer 展示，下次拍照再清空替换。
                            _uiState.update {
                                it.copy(
                                    confirm = ConfirmState(
                                        trackingNumber = tracking.ifBlank { c.trackingNumber },
                                        carrier = c.carrier,
                                        customerName = c.customerName,
                                        customerId = c.customerId,
                                        condition = c.condition,
                                        trackingAutoFilled = c.trackingAutoFilled,
                                        carrierAutoFilled = c.carrierAutoFilled,
                                        customerAutoFilled = c.customerAutoFilled,
                                        trackingFromBarcode = c.trackingFromBarcode,
                                        autoSubmitConsumed = true
                                    )
                                )
                            }
                        }
                    }
                    is NetworkResult.Error -> {
                        soundPlayer.playBeep()
                        // 入库失败，释放门禁——操作员应该能立刻重拍/重试，不用等一个不存在的
                        // 收货预警流程。
                        _uiState.update {
                            it.copy(
                                confirm = it.confirm?.copy(saving = false),
                                message = DockMessage.Text(result.message),
                                captureStatus = CaptureStatus.Failure,
                                submitting = false
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * createItem 成功后调用——**调用方必须已经在此之前同步把 `checkingAlert` 置为 true**
     * （在任何 suspend 调用之前，包括 refreshItems()；不能指望这个函数自己来设，见调用点的
     * 注释和 docs/pda对齐.md 第 1 节"门禁开启得太晚"）。这里 `suspend` 查询
     * [ReceivingRepository.matchAlert]（同一协程里顺序执行，不能另起 launch，见"门禁竞态"）。
     * 未命中/无运单号/查询失败 → 关闭门禁并立刻执行 [onGateOpen]（重置 ConfirmState、打开
     * 下一次拍照/扫码）；命中 → 设置 pendingAlert，门禁继续开着，[onGateOpen] 存起来等 UI
     * 弹窗调用 [confirmAlertAck] 时才执行。
     */
    private suspend fun gateOnReceivingAlert(tracking: String, onGateOpen: () -> Unit) {
        if (tracking.isBlank()) {
            _uiState.update { it.copy(checkingAlert = false, submitting = false) }
            onGateOpen()
            return
        }
        var hit: PendingAlertUi? = null
        repo.matchAlert(tracking).collect { result ->
            if (result is NetworkResult.Success) {
                result.data?.let { hit = PendingAlertUi(it.id, tracking, it.instruction) }
            }
            // repo.matchAlert 设计上不会 emit Error（查询失败已经在 repo 层转成 Success(null)），
            // Loading/Error 分支都不需要额外处理。
        }
        val alert = hit
        if (alert != null) {
            soundPlayer.playBeep()
            pendingGateOpen = onGateOpen
            // submitting 的使命到这里结束——从这一刻起改由 pendingAlert 单独顶住门禁，
            // 直到 confirmAlertAck()。
            _uiState.update { it.copy(checkingAlert = false, submitting = false, pendingAlert = alert) }
        } else {
            _uiState.update { it.copy(checkingAlert = false, submitting = false) }
            onGateOpen()
        }
    }

    /** [gateOnReceivingAlert] 命中提醒时暂存的"打开下一次拍照/扫码"动作，等 [confirmAlertAck] 执行。 */
    private var pendingGateOpen: (() -> Unit)? = null

    /**
     * 弹窗里用户一键确认后调用。⚠️ 顺序不能写反，而且不能用 viewModelScope：
     * 先同步清空 pendingAlert、执行 [pendingGateOpen]（打开下一次拍照），然后才调用
     * repo.acknowledgeAlertBestEffort（内部自己 launch 到它自己的 scope，这里不需要、
     * 也不应该再包一层 viewModelScope.launch）。acknowledgeAlert 是纯尾随的异步动作，
     * 不在放行路径上——见 docs/pda对齐.md 第 1 节。
     */
    fun confirmAlertAck() {
        val alertId = _uiState.value.pendingAlert?.id
        val openGate = pendingGateOpen
        pendingGateOpen = null
        _uiState.update { it.copy(pendingAlert = null, submitting = false) }
        openGate?.invoke()
        if (alertId != null) repo.acknowledgeAlertBestEffort(alertId)
    }

    /** 扫码模式：直接用运单号建条目（无照片，source=Barcode），成功后刷新列表。 */
    fun scanItem(tracking: String) {
        // 收货预警门禁开着期间不接受新的扫码提交——见 docs/pda对齐.md 第 1 节"命中后门禁不能被绕过"。
        // 播一声提示音：扫码枪硬件不知道门禁状态，会照常触发这次调用，操作员现场往往不盯着
        // 屏幕看，只有声音才能立刻告诉他们这次扫描没有被接受——UI 层（DockReceivingScreen 的
        // ScanInputField）已经在门禁期间不清空输入框，这里再加一道听觉反馈。
        if (_uiState.value.alertGateActive) {
            soundPlayer.playBeep()
            return
        }
        val compact = tracking.replace("\\s+".toRegex(), "")
        val t = shortenFedExTracking(sanitizeTracking(compact).ifBlank { compact })
        if (t.isBlank()) return
        val bid = _uiState.value.batchId ?: return
        val carrier = if (wasFedExLongBarcode(compact)) "FedEx" else null
        // 同 beginSaveWithDuplicateCheck()：从决定提交这一刻就锁门禁，覆盖查重 + createItem 请求
        // 本身的挂起窗口，不能只在 createItem 成功之后才锁。
        _uiState.update { it.copy(submitting = true) }
        viewModelScope.launch {
            repo.isDuplicateTracking(t).collect { result ->
                when (result) {
                    is NetworkResult.Loading -> {}
                    is NetworkResult.Success -> {
                        if (result.data) {
                            soundPlayer.playBeep()
                            _uiState.update {
                                it.copy(
                                    confirm = ConfirmState(
                                        trackingNumber = t,
                                        carrier = carrier.orEmpty(),
                                        trackingFromBarcode = true,
                                        pendingDuplicateTracking = t,
                                        autoSubmitConsumed = true
                                    )
                                )
                            }
                        } else {
                            createScanItem(bid, t, carrier)
                        }
                    }
                    is NetworkResult.Error -> createScanItem(bid, t, carrier)
                }
            }
        }
    }

    private fun createScanItem(bid: Int, tracking: String, carrier: String? = null) {
        val req = CreateItemRequest(
            receivingBatchId = bid,
            trackingNumber = tracking,
            carrier = carrier,
            photoPaths = null,
            source = "Barcode",
            needsReview = false
        )
        viewModelScope.launch {
            repo.createItem(req).collect { result ->
                when (result) {
                    is NetworkResult.Loading -> {}
                    is NetworkResult.Success -> {
                        soundPlayer.playSuccess()
                        // 门禁必须在 refreshItems() 这个 suspend 调用之前同步打开，理由同 performSave()。
                        _uiState.update { it.copy(captureStatus = CaptureStatus.Success, checkingAlert = true) }
                        refreshItems(bid)
                        gateOnReceivingAlert(tracking) {
                            _uiState.update { it.copy(confirm = null) }
                        }
                    }
                    is NetworkResult.Error -> {
                        soundPlayer.playBeep()
                        _uiState.update {
                            it.copy(
                                message = DockMessage.Text(result.message),
                                captureStatus = CaptureStatus.Failure,
                                submitting = false
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun refreshItems(batchId: Int) {
        repo.getItems(batchId).collect { result ->
            if (result is NetworkResult.Success) _uiState.update { it.copy(items = result.data) }
            else if (result is NetworkResult.Error) _uiState.update { it.copy(message = DockMessage.Text(result.message)) }
        }
    }

    fun confirmCloseBatch() {
        // 收货预警门禁开着期间不允许关批次——UI 层（返回键/关批次按钮）已经禁用了触发入口，
        // 这里是第二道防线。见 docs/pda对齐.md 第 1 节。
        if (_uiState.value.alertGateActive) return
        val bid = _uiState.value.batchId ?: return
        val number = _uiState.value.batchNumber
        viewModelScope.launch {
            repo.closeBatch(bid).collect { result ->
                when (result) {
                    is NetworkResult.Loading -> _uiState.update { it.copy(isBusy = true) }
                    is NetworkResult.Success -> _uiState.update {
                        DockReceivingUiState(message = DockMessage.BatchClosed(number.orEmpty()))
                    }
                    is NetworkResult.Error -> _uiState.update {
                        it.copy(isBusy = false, message = DockMessage.Text(result.message))
                    }
                }
            }
        }
    }

    fun messageShown() = _uiState.update { it.copy(message = null) }
}
