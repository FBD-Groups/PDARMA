# Receive Report 重做 + 批次下钻 — 设计

日期：2026-06-17
状态：已与用户确认，待写实现计划

## 背景与目标

当前 Receive Report 页（`ui/receivereport/`）是一个扁平的 `LazyColumn` + 文字行 + 全宽分隔线，
视觉上像未排版的原始列表。用户希望「好看点、专业点」，并要求列表行可**下钻**进入批次明细。

目标：
1. 把列表重做成专业 WMS 风格的「密集账本」布局（信息密度高、列对齐、扫读快）。
2. 列表中**过滤掉件数为 0 的批次**。
3. 新增**批次明细页**：点某行进入，展示该批次内每件的 tracking# / carrier / 是否需复核。

非目标（YAGNI）：
- 不改后端。明细页只用现有 `GET /api/receiving-items?batchId=` 返回的三个字段
  （tracking#、carrier、needsReview）。
- 不显示照片缩略图 / condition / 状态等额外字段（后端当前不返回）。
- 不做搜索、筛选、日期范围选择（窗口仍固定为最近 3 天）。

## 设计概览

```
HomeScreen ──(Receive Report tile)──▶ ReceiveReportScreen ──(tap batch row)──▶ BatchDetailScreen
                                       (密集账本列表)                            (批次内单件清单)
```

### 1. ReceiveReportScreen 重做（密集账本风格）

- **顶栏**：`TopAppBar`，导航图标用返回箭头（`Icons.AutoMirrored.Filled.ArrowBack`）代替现有的
  "Back" 文字按钮；标题 "Receive Report"。配色与 HomeScreen 顶栏一致（primary / onPrimary）。
- **日期分组头（sticky）**：用 `LazyColumn` 的 `stickyHeader`，滚动时吸顶。
  - 浅色背景条（`primaryContainer` / `onPrimaryContainer`）。
  - 左：`TODAY` / `YESTERDAY` / `Jun 15`（沿用 `ReceiveReportDay.label`）。
  - 右：`N batches · M items` 汇总（基于过滤后的批次）。
- **批次行**（紧凑、列对齐、可点击）：
  - 左侧状态圆点（Closed / Dispatched 均为绿色，使用主题成功色/teal）。
  - 批次号（`fontFamily = monospace`）。
  - 收货时间 `HH:mm`（次要色，定宽右对齐）。
  - 件数（粗体数字，定宽右对齐）。
  - 右侧 `chevron-right` 指示可下钻。
  - 整行 `clickable { onOpenBatch(batchId, batchNumber) }`，行高与点击区适配手指/手套操作。
- 行之间用 `HorizontalDivider`（细线）分隔。
- 状态保持现有的 Loading / Empty / Error(可重试)；Empty 文案沿用「No receipts in the last 3 days」。

### 2. 过滤件数为 0 的批次

- 在 `ReceivingRepository.getReceivedBatches(...)` 的映射链中增加 `it.itemCount > 0` 过滤
  （与现有 `status == Closed/Dispatched` 且 `endTime` 可解析 的过滤同处）。
- 放在 repository 层的理由：下游的天分组、汇总数（batches / items）、以及明细页入口都自动一致，
  不会出现「列表里有批次但点进去 0 件」的情况。

### 3. BatchDetailScreen（新增下钻页）

- 新 feature 包 `ui/batchdetail/`：
  - `BatchDetailScreen.kt`（Compose，stateless 子组件 + 顶层 + ViewModel 注入）
  - `BatchDetailViewModel.kt`（`@HiltViewModel`，从 `SavedStateHandle` 取 `batchId` / `batchNumber`）
  - `BatchDetailUiState.kt`（`sealed interface`：Loading / Empty / Success(items) / Error(message)）
- 数据来源：复用现有 `ReceivingRepository.getItems(batchId)` → `List<ReceivingItemUi>`
  （`receivingItemId` / `trackingNo` / `carrier` / `needsReview`），无需改 repository。
- 布局（沿用密集账本风格）：
  - **顶栏**：返回箭头 + 标题（批次号，monospace）。
  - **汇总区**：批次号 + 收货时间 + 件数（收货时间从导航参数或不传——见下「权衡」）。
  - **单件清单**：每件一行 `trackingNo`（monospace，主色）+ `carrier`（次要色）；
    `needsReview == true` 的行右侧加一个**文字小标签**「需复核」（橙色背景 + 橙色深色文字的 pill）。
  - 行间 `HorizontalDivider`。
- 状态：Loading / Empty（「该批次暂无明细」）/ Error(可重试)。

### 4. 数据模型与导航

- `ReceivedBatch`（domain model）增加 `receivingBatchId: Int`；`ReceivingBatchDto` 已含该字段，
  在 `getReceivedBatches` 映射时一并带上。
- `ReceiveReportScreen` 增加回调 `onOpenBatch: (batchId: Int, batchNumber: String) -> Unit`。
- MainActivity 新增路由：
  ```
  batch-detail/{batchId}/{batchNumber}
  ```
  - `batchId`：`NavType.StringType`（沿用现有 receive-report 取 warehouseId 的字符串模式，
    ViewModel 内 `toIntOrNull()`）。
  - `batchNumber`：`NavType.StringType`，用于标题/汇总区直接展示，避免明细页再查一次批次。
  - 注意 batchNumber 用于 URL，需 `Uri.encode` / decode（批次号为 `B20260617-017` 这类安全字符，
    实现时仍按需编码以防万一）。
- `ReceiveReportScreen` 的 `onOpenBatch` 在 MainActivity 里 `navController.navigate("batch-detail/$id/$number")`。

### 5. 测试

- `ReceivingRepositoryTest`：
  - 现有「keeps closed-with-endTime and parses time」用例已改为后端真实格式（空格分隔）。
  - 新增断言：itemCount == 0 的 Closed 批次被过滤掉，结果不包含它。
  - 断言映射后的 `ReceivedBatch.receivingBatchId` 正确。
- `ReceiveReportTest`（`buildReceiveReport` 纯函数）：分组/排序逻辑不变；如汇总计数随过滤变化需补用例。
- 明细页 ViewModel 如含可单测的纯逻辑（一般较薄）可酌情补测；UI 不做 instrumentation 测试。

## 权衡与决策记录

- **0-item 过滤位置**：选 repository 层（用户确认），保证下游一致性，代价是 repository 承担了一点业务过滤。
- **needsReview 标记**：选文字小标签「需复核」（橙色 pill），而非图标——用户确认，文字更明确。
- **sticky 日期头**：要（用户确认），用 `LazyColumn.stickyHeader`。
- **明细页收货时间来源**：优先走导航参数（避免重复请求）；若参数过多导致路由臃肿，则汇总区只显示
  批次号 + 件数，时间略去。实现计划阶段二选一，倾向「批次号 + 件数」最小集，时间非必需。
- **语言**：结构性标题沿用现有英文（"Receive Report" 等，与当前页面一致）；用户可见的状态/标记文案
  按用户明确选择用中文「需复核」。后续可统一，但本次不扩大范围。

## 影响文件清单（预估）

改：
- `app/src/main/kotlin/com/pda/app/data/repository/ReceivingRepository.kt`（0-item 过滤 + 带 batchId）
- `app/src/main/kotlin/com/pda/app/data/api/model/ReceivingModels.kt`（`ReceivedBatch` 加 `receivingBatchId`）
- `app/src/main/kotlin/com/pda/app/ui/receivereport/ReceiveReportScreen.kt`（密集账本重做 + 下钻回调）
- `app/src/main/kotlin/com/pda/app/MainActivity.kt`（新路由 + 串联回调）
- `app/src/test/java/com/pda/app/ReceivingRepositoryTest.kt`（新断言）

新增：
- `app/src/main/kotlin/com/pda/app/ui/batchdetail/BatchDetailScreen.kt`
- `app/src/main/kotlin/com/pda/app/ui/batchdetail/BatchDetailViewModel.kt`
- `app/src/main/kotlin/com/pda/app/ui/batchdetail/BatchDetailUiState.kt`
