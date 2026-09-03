# PDA Dock Receiving — 对齐网页识别与自动保存

- 日期：2026-09-03
- 范围：PDA Dock Receiving 拍照识别流程，对齐 RMA 网页版 PhotoTab 的核心行为：识别 tracking / carrier / customerName，识别成功后自动入库；重复运单弹确认。
- 状态：已与用户确认。

## 1. 目标与决策

### 目标
拍照识别后得到运单号、承运商、客户名，在确认页展示；识别成功后自动保存（含照片），无需再点确认。失败时停在确认页可手改或重拍。

### 已确认决策
| 项 | 选择 |
|---|---|
| 成功后行为 | 保留确认页，识别完成后自动触发保存（方案 2） |
| 客户匹配 | 不拉客户列表；只把 AI 的 `customerName` 原样写入（不做 `customerCode` → `customerId`） |
| 重复运单 | 对齐网页：近 10 天精确匹配，弹确认后用户选「仍要加入」才入库 |

### 明确不做
- 活跃客户列表匹配 / `customerId` 解析
- 修改网页端或后端 Gemini prompt
- 单条 item 多张照片
- 改变 Barcode 扫码主流程（仅在提交前加重复确认）

## 2. 对齐网页的行为对照

网页 `PhotoTab` 当前流程：上传 → AI `mode=shipping` → 填 tracking/carrier/customer → 有 tracking 则自动 Confirm。

PDA 本设计差异（有意）：
- **保留确认页**：自动保存成功后清空草稿；失败/重复取消时用户仍可编辑。
- **保留本地条码并行**：运单号优先级仍为 **条码 > AI > 手输**（网页无本地条码）。
- **客户**：网页可按 UF 编码匹配 `customerId`；PDA 本版只传 `customerName`。

## 3. API 与数据模型

### 已有 / 调整的请求字段
`POST /api/receiving-items`（对齐网页 `ReceivingItemCreateRequest`）：

```
{
  receivingBatchId: Int,
  trackingNumber: String?,
  carrier: String?,
  customerName: String?,      // 新增
  photoPaths: List<String>?,  // 替代旧 photoPath；单张时传 listOf(url)
  source: String,             // "AI" | "Barcode"
  rawJson: String?,
  needsReview: Boolean?,
  condition: String?          // 确认页仍可选手填，自动保存时带当前值
}
```

> 后端已改为 `PhotoPaths`；PDA 若仍发 `photoPath` 照片可能存不上，本改动一并修正。

### AI 响应
`POST /api/analyze` shipping 模式已返回 `customerName`（及 `customerCode`，本版忽略）。

```
ShippingAnalyzeResponse / ShippingAnalysis 增加：
  customerName: String?
```

### 重复校验
`GET /api/receiving-items/search`：
- `trackingNumberExact` = 去空格后的运单号
- `receivedDateFrom` = 今天往前 10 天（`yyyy-MM-dd`）
- `page=1`，`pageSize=1`
- `total > 0` 视为重复

查询失败：**不拦截**，当作无重复（对齐网页）。

## 4. 架构与状态

沿用现有 MVVM + Repository + `NetworkResult` + Hilt。

### `ConfirmState` 增补
- `customerName: String`
- `customerAutoFilled: Boolean`
- 重复确认：`pendingDuplicateTracking: String?`（非空时 UI 弹对话框；取消清掉；确认则继续保存）

### ViewModel 自动提交
在识别流水线结束（`analyzing == false` 且条码解码结束）后，若同时满足：
1. `trackingNumber` 非空
2. 有照片则 `photoPath`/`photoPaths` 已就绪（上传成功）
3. 未在 `saving`、无进行中的重复对话框

则进入提交：先 `search` 查重复 → 无重复直接 `createItem`；有重复则设 `pendingDuplicateTracking`，等用户确认后再 `createItem`。

**防抖 / 不误触发**：
- 用户在确认页手动改运单号后，**不**因改字再次自动保存；改完可点「确认录入」手动保存。
- 可用一次性标记（如 `autoSubmitConsumed` 或按 `photoFile` identity）保证「同一张照片只自动尝试一次」。

### 条码扫码模式
`scanItem` 提交前走同一套重复确认；成功逻辑不变（无照片、`source=Barcode`）。

## 5. UI

确认页：
- 现有：Tracking、Carrier、Condition
- 新增：Customer Name 文本框（AI 预填可改）
- 新增：重复运单 `AlertDialog`（取消 / 仍要加入）
- 「确认录入」按钮保留，用于识别失败后手改再存、或自动保存被跳过后的手动提交

列表行：可选展示 `customerName`（若 `ReceivingItemDto` 已有或可忽略未知字段）；第一版至少保证入库带上客户名，列表展示可跟随 DTO 是否返回该字段——若 GET items 已有 `customerName` 则映射进 `ReceivingItemUi` 并显示，否则本版列表可不改。

## 6. 错误处理

| 场景 | 行为 |
|---|---|
| AI 无运单号且无条码 | toast「未识别到运单号」，停确认页，不自动保存 |
| AI 失败但条码成功 | 用条码运单号自动保存；客户名可空 |
| 上传失败 | 不自动保存；toast；可重拍 |
| 重复查询失败 | 当作无重复，继续保存 |
| 自动/手动保存失败 | toast；保留草稿可重试 |
| 用户取消重复确认 | 停确认页，不入库 |

## 7. i18n

在 `AppStrings` / 中英西 增加：
- 客户名标签（如 `dock_customerName`）
- 重复确认：标题、说明（含运单号占位）、取消、仍要加入

用户可见文案保持现有多语言约定；后端错误原文仍走 `DockMessage.Text`。

## 8. 测试

- `ShippingAnalysis` / DTO 解析含 `customerName`
- `CreateItemRequest` 序列化为 `photoPaths`（且无旧 `photoPath`）
- 自动保存：识别成功 + 上传完成 → 调用 create；无运单号 → 不调用
- 同一张照片只自动提交一次；手改运单号不二次自动提交
- 重复：`total>0` → 弹确认；确认后 create；取消不 create
- 重复查询抛错 → 仍 create
- 条码成功 + AI 失败 → 仍可自动 create

## 9. 实现顺序建议

1. 模型与 Repository（`customerName`、`photoPaths`、search 重复）
2. ViewModel 自动提交 + 重复确认状态
3. Screen：客户名字段 + 对话框
4. i18n + 单测
