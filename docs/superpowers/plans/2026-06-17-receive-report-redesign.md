# Receive Report 重做 + 批次下钻 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Receive Report 列表重做成专业的密集账本风格、过滤 0 件批次，并让每行可下钻到批次明细页。

**Architecture:** 复用现有 `ReceivingRepository`（列表 + 单件明细两个已有方法）。列表层（`ReceiveReportScreen`）重做为 `LazyColumn` + `stickyHeader` + 紧凑可点击行；新增 `ui/batchdetail/` 三件套（Screen + ViewModel + UiState）作为下钻目标，经由 MainActivity 的字符串路由 `batch-detail/{batchId}/{batchNumber}` 进入。0 件过滤与 `receivingBatchId` 透传都收敛在 repository 层。

**Tech Stack:** Kotlin 2.0、Jetpack Compose（Material3）、Hilt、Coroutines/Flow、Navigation-Compose、JUnit4 + coroutines-test。

**Build/Test 前置：** 每次跑 Gradle 前先设 JDK：
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```
工作目录：`C:\Users\KyleHu\source\repos\PDA\PDA`

---

### Task 1: Repository 带上 batchId + 过滤 0 件批次

**Files:**
- Modify: `app/src/main/kotlin/com/pda/app/data/api/model/ReceivingModels.kt`（`ReceivedBatch` 加字段）
- Modify: `app/src/main/kotlin/com/pda/app/data/repository/ReceivingRepository.kt:142-170`（映射加 batchId + 过滤）
- Test: `app/src/test/java/com/pda/app/ReceivingRepositoryTest.kt`

- [ ] **Step 1: 改 domain model，给 `ReceivedBatch` 加 `receivingBatchId`**

在 `ReceivingModels.kt` 末尾的 `ReceivedBatch` 改成：

```kotlin
/** 一条已收货批次（Receive Report 用）。receivedAt = 后端 EndTime（关批时间）。 */
data class ReceivedBatch(
    val receivingBatchId: Int,
    val batchNumber: String,
    val receivedAt: LocalDateTime,
    val itemCount: Int
)
```

- [ ] **Step 2: 写失败测试（过滤 0 件 + 透传 batchId）**

在 `ReceivingRepositoryTest.kt` 把现有 `getReceivedBatches keeps closed-with-endTime and parses time` 用例替换为下面这条（覆盖 0 件过滤 + batchId 断言）：

```kotlin
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
```

- [ ] **Step 3: 跑测试确认失败**

Run:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "com.pda.app.ReceivingRepositoryTest"
```
Expected: 编译失败（`ReceivedBatch` 构造参数不匹配 / 缺 batchId）或断言失败。

- [ ] **Step 4: 改 repository 映射（加 batchId + 0 件过滤）**

在 `ReceivingRepository.kt` 的 `getReceivedBatches` 内，把 `.filter { ... }.mapNotNull { ... }` 段替换为：

```kotlin
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
```

同时更新方法上方 KDoc 提及「已过滤 0 件」：把注释末句改为 `…已收货（状态 Closed/Dispatched、件数 > 0 且 EndTime 可解析）的列表。`

- [ ] **Step 5: 跑测试确认通过**

Run:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest --tests "com.pda.app.ReceivingRepositoryTest" --tests "com.pda.app.ReceiveReportTest"
```
Expected: BUILD SUCCESSFUL（两个测试类全绿）。

- [ ] **Step 6: 提交**

```powershell
git add app/src/main/kotlin/com/pda/app/data/api/model/ReceivingModels.kt app/src/main/kotlin/com/pda/app/data/repository/ReceivingRepository.kt app/src/test/java/com/pda/app/ReceivingRepositoryTest.kt
git commit -m "feat: filter empty batches and carry batchId in receive report data"
```

---

### Task 2: 批次明细 UI state + ViewModel

**Files:**
- Create: `app/src/main/kotlin/com/pda/app/ui/batchdetail/BatchDetailUiState.kt`
- Create: `app/src/main/kotlin/com/pda/app/ui/batchdetail/BatchDetailViewModel.kt`

- [ ] **Step 1: 建 UiState**

`BatchDetailUiState.kt`：

```kotlin
package com.pda.app.ui.batchdetail

import com.pda.app.data.api.model.ReceivingItemUi

sealed interface BatchDetailUiState {
    data object Loading : BatchDetailUiState
    data object Empty : BatchDetailUiState
    data class Success(val items: List<ReceivingItemUi>) : BatchDetailUiState
    data class Error(val message: String) : BatchDetailUiState
}
```

- [ ] **Step 2: 建 ViewModel**

`BatchDetailViewModel.kt`（从 SavedStateHandle 取 `batchId`/`batchNumber`，模式对齐 `ReceiveReportViewModel`）：

```kotlin
package com.pda.app.ui.batchdetail

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pda.app.data.NetworkResult
import com.pda.app.data.repository.ReceivingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@HiltViewModel
class BatchDetailViewModel @Inject constructor(
    private val repo: ReceivingRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private companion object {
        const val TAG = "PDA/BatchDetailViewModel"
    }

    private val batchId: Int? = savedStateHandle.get<String>("batchId")?.toIntOrNull()
    val batchNumber: String = savedStateHandle.get<String>("batchNumber").orEmpty()

    private val _uiState = MutableStateFlow<BatchDetailUiState>(BatchDetailUiState.Loading)
    val uiState: StateFlow<BatchDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        val id = batchId
        if (id == null) {
            _uiState.value = BatchDetailUiState.Error("无效的批次")
            return
        }
        repo.getItems(id)
            .onEach { result ->
                when (result) {
                    is NetworkResult.Loading -> _uiState.value = BatchDetailUiState.Loading
                    is NetworkResult.Success ->
                        _uiState.value = if (result.data.isEmpty()) BatchDetailUiState.Empty
                        else BatchDetailUiState.Success(result.data)
                    is NetworkResult.Error -> {
                        Log.w(TAG, "load failed: ${result.message}")
                        _uiState.value = BatchDetailUiState.Error(result.message)
                    }
                }
            }
            .launchIn(viewModelScope)
    }
}
```

- [ ] **Step 3: 编译确认**

Run:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat compileDebugKotlin
```
Expected: BUILD SUCCESSFUL（暂未被引用，但能通过编译）。

- [ ] **Step 4: 提交**

```powershell
git add app/src/main/kotlin/com/pda/app/ui/batchdetail/BatchDetailUiState.kt app/src/main/kotlin/com/pda/app/ui/batchdetail/BatchDetailViewModel.kt
git commit -m "feat: add BatchDetail UI state and ViewModel"
```

---

### Task 3: 批次明细 Screen（密集账本风格）

**Files:**
- Create: `app/src/main/kotlin/com/pda/app/ui/batchdetail/BatchDetailScreen.kt`

- [ ] **Step 1: 建 Screen**

`BatchDetailScreen.kt`：

```kotlin
package com.pda.app.ui.batchdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pda.app.data.api.model.ReceivingItemUi

private val ReviewBg = Color(0xFFFAEEDA)
private val ReviewFg = Color(0xFF854F0B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchDetailScreen(
    onBack: () -> Unit,
    viewModel: BatchDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.batchNumber, fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                is BatchDetailUiState.Loading ->
                    Text("Loading…", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                is BatchDetailUiState.Empty ->
                    Text("该批次暂无明细", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                is BatchDetailUiState.Error ->
                    Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::load) { Text("重试") }
                    }
                is BatchDetailUiState.Success ->
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item(key = "summary") {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "${state.items.size} items",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            HorizontalDivider()
                        }
                        items(state.items, key = { it.receivingItemId }) { item ->
                            ItemRow(item)
                            HorizontalDivider()
                        }
                    }
            }
        }
    }
}

@Composable
private fun ItemRow(item: ReceivingItemUi) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.trackingNo.ifBlank { "（无单号）" },
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (item.carrier.isNotBlank()) {
                Text(item.carrier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (item.needsReview) {
            Surface(color = ReviewBg, shape = MaterialTheme.shapes.small) {
                Text(
                    "需复核",
                    color = ReviewFg,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}
```

- [ ] **Step 2: 编译确认**

Run:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat compileDebugKotlin
```
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```powershell
git add app/src/main/kotlin/com/pda/app/ui/batchdetail/BatchDetailScreen.kt
git commit -m "feat: add BatchDetail screen with item list and review tag"
```

---

### Task 4: ReceiveReportScreen 重做（密集账本 + sticky 头 + 下钻回调）

**Files:**
- Modify: `app/src/main/kotlin/com/pda/app/ui/receivereport/ReceiveReportScreen.kt`（整文件替换）

- [ ] **Step 1: 整文件替换 `ReceiveReportScreen.kt`**

```kotlin
package com.pda.app.ui.receivereport

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pda.app.data.api.model.ReceivedBatch
import java.time.format.DateTimeFormatter

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val StatusDot = Color(0xFF1D9E75)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReceiveReportScreen(
    onBack: () -> Unit,
    onOpenBatch: (batchId: Int, batchNumber: String) -> Unit,
    viewModel: ReceiveReportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receive Report") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = uiState) {
                is ReceiveReportUiState.Loading ->
                    Text("Loading…", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                is ReceiveReportUiState.Empty ->
                    Text("No receipts in the last 3 days", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                is ReceiveReportUiState.Error ->
                    Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::load) { Text("重试") }
                    }
                is ReceiveReportUiState.Success ->
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        state.days.forEach { day ->
                            stickyHeader(key = "header-${day.date}") {
                                val total = day.batches.sumOf { it.itemCount }
                                DayHeader(label = day.label, batchCount = day.batches.size, itemTotal = total)
                            }
                            items(day.batches, key = { it.receivingBatchId }) { batch ->
                                BatchRow(batch, onClick = { onOpenBatch(batch.receivingBatchId, batch.batchNumber) })
                                HorizontalDivider()
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun DayHeader(label: String, batchCount: Int, itemTotal: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f)
        )
        Text(
            "$batchCount batches · $itemTotal items",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun BatchRow(batch: ReceivedBatch, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(StatusDot))
        Spacer8()
        Text(
            batch.batchNumber,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            batch.receivedAt.format(TIME_FORMAT),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer8()
        Text(
            batch.itemCount.toString(),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(24.dp)
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Spacer8() {
    Box(modifier = Modifier.width(10.dp))
}
```

> 说明：`stickyHeader` 来自 `androidx.compose.foundation.lazy`，需要 `@OptIn(ExperimentalFoundationApi::class)`（已加在函数注解）。`itemTotal` 已是整型计数，无浮点。

- [ ] **Step 2: 编译确认（会因 MainActivity 缺 `onOpenBatch` 实参而报错，预期内，下一 Task 修）**

Run:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat compileDebugKotlin
```
Expected: 报错指向 `MainActivity.kt` 调用 `ReceiveReportScreen(...)` 缺少 `onOpenBatch` 参数。若是其他报错（如 import 缺失），先修本文件。

- [ ] **Step 3:（先不提交，连同 Task 4 导航一起提交）**

---

### Task 5: MainActivity 导航串联

**Files:**
- Modify: `app/src/main/kotlin/com/pda/app/MainActivity.kt`

- [ ] **Step 1: 加 import**

在 import 区加：

```kotlin
import android.net.Uri
import com.pda.app.ui.batchdetail.BatchDetailScreen
```

- [ ] **Step 2: 给 receive-report 的 `ReceiveReportScreen` 传 `onOpenBatch`**

把现有：

```kotlin
                        composable(
                            route = "receive-report/{warehouseId}",
                            arguments = listOf(navArgument("warehouseId") { type = NavType.StringType })
                        ) {
                            ReceiveReportScreen(onBack = { navController.popBackStack() })
                        }
```

替换为：

```kotlin
                        composable(
                            route = "receive-report/{warehouseId}",
                            arguments = listOf(navArgument("warehouseId") { type = NavType.StringType })
                        ) {
                            ReceiveReportScreen(
                                onBack = { navController.popBackStack() },
                                onOpenBatch = { batchId, batchNumber ->
                                    navController.navigate("batch-detail/$batchId/${Uri.encode(batchNumber)}")
                                }
                            )
                        }
                        composable(
                            route = "batch-detail/{batchId}/{batchNumber}",
                            arguments = listOf(
                                navArgument("batchId") { type = NavType.StringType },
                                navArgument("batchNumber") { type = NavType.StringType }
                            )
                        ) {
                            BatchDetailScreen(onBack = { navController.popBackStack() })
                        }
```

- [ ] **Step 3: 全量编译**

Run:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat compileDebugKotlin
```
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 提交（Task 4 + 5 一起）**

```powershell
git add app/src/main/kotlin/com/pda/app/ui/receivereport/ReceiveReportScreen.kt app/src/main/kotlin/com/pda/app/MainActivity.kt
git commit -m "feat: redesign Receive Report as dense ledger with batch drill-down"
```

---

### Task 6: 全量验证

- [ ] **Step 1: 跑全部单测**

Run:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat testDebugUnitTest
```
Expected: BUILD SUCCESSFUL，无失败用例。

- [ ] **Step 2: 组装 Debug APK 确保整体可构建**

Run:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleDebug
```
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3:（可选）装到模拟器人工验证**

Run:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat installDebug
```
然后在 Home → Receive Report 查看密集账本列表与 sticky 日期头，点一行进入明细页，确认「需复核」标签与返回。

---

## 备注

- 用户可见文案：结构性标题沿用现有英文（"Receive Report"、"No receipts in the last 3 days"、"Loading…"），
  新增交互/标记文案用中文（"返回"、"重试"、"需复核"、"该批次暂无明细"、"无效的批次"、"（无单号）"），
  与 CLAUDE.md「用户可见消息用中文」一致。
- 明细页汇总区按最小集只显示件数（"N items"），不显示收货时间（spec 权衡已决定时间非必需）。
- 颜色：状态圆点绿 `#1D9E75`、需复核标签橙底 `#FAEEDA` / 橙字 `#854F0B`，均取自设计系统色阶，
  默认浅色主题下对比度足够。
```
