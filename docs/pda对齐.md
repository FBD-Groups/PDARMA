# PDA 对齐 Web 端最新逻辑 — 需求文档

记录 PDA（`com.pda.app`）跟 RMA web 前端（`rma-frontend`）最近几次迭代之间的三个已知缺口，以及要补的东西。范围仅限 Dock Receiving 拍照/条码录入流程，不涉及 PDA 目前还没有的模块（Receiving 办公室页面、Inspection 等）。

三件事优先级/依赖顺序不同，**不是一个 PR**：第 1 项（收货预警）和第 2 项（Alias 匹配）后端接口都已上线，可以直接排期实现；第 2 项接入前只需要确认一下目标环境（尤其是 PDA 现在指向的 `https://fbd.shipswithus.com/fbd-rma-api/`）已经部署了 RMA 那次 alias 改动、`GET /api/customers` 确实返回 `alias` 字段（见 2.2 节）。第 3 项（客户列表全局缓存）是 PDA 自己的架构改进，跟 web 无关，可以独立先做。

> 2026-09-18 更新：本文档最初写第 2 项时 RMA 仓库里 Alias 功能还是本地未提交的工作进度，判断"后端还没上线、PDA 只能先做模型和单测"。RMA 那边已经提交合并（`6d2f65c 增加ai识别alias`，`git status` 现在是 clean），第 2 节已按最新状态重写，不再是阻塞项。

---

## 1. 收货预警（Receiving Alert）强制确认

### 1.1 现状：PDA 完全没有这个功能

PDA 现在建 item 成功（`ReceivingRepository.createItem` → `POST /api/receiving-items`）之后什么都不做，直接回到录入状态等下一件。`grep alert` 在 `app/src/main/kotlin` 下无匹配——这条链路在 PDA 里一行代码都不存在。

### 1.2 Web 参考实现

功能背景：仓库预先为某个运单号/序列号/RMA 号配一条"收货预警"规则（`ReceivingAlerts` 表，`Status`: `O`=生效中 / `V`=作废 / `R`=已收货 / `C`=预留），带一段处置指令文本（`Instruction`，例如 "Please send to DJI"）。操作员扫描/拍照/手输入库时，如果运单号命中一条生效中的规则，**必须**看到并确认这条指令，因为这是对实物包裹的处置要求，不是普通提示。

Dock Receiving 三个录入方式（Scan/Manual/Photo）现在都接了同一个 hook：[`useReceivingAlertAck.tsx`](../../RMA/rma-frontend/client/src/components/dock-receiving/useReceivingAlertAck.tsx)。关键设计点：

- **触发时机是"入库成功之后"，不是入库之前**：扫枪场景是瞬间打完整串+自动回车，提交那一刻之前的任何行内提示条都来不及被人看到，所以必须在 `createItem` 成功回调里才发起提醒查询。
- **不拦入库，只拦"继续下一件"**：查询提醒失败（网络错误等）直接放行，不能因为查不到提醒就把已经入库的操作卡住。
- **查询只传运单号**：`GET /api/receiving-alerts/match?trackingNumber=xxx`（[ReceivingEndpoints.cs:259](../../RMA/rma-system/backend/RmaSystem.Api/Endpoints/ReceivingEndpoints.cs:259)，`RequirePage("receiving-alert", "receiving", "dock-receiving")` —— **PDA 的 `dock-receiving` pageKey 已经在白名单里，不需要额外申请权限**）。Serial/RMA 是可选过滤条件，Dock 阶段没有这两个字段，不传即可，后端对未传字段不做限制。
- **命中判定**：返回体非 `null` 且 `instruction`、`id` 都非空才算命中；否则视为未命中，静默放行。
- **弹窗是硬门禁**：弹窗内容展示 `instruction` 原文，操作员点一次确认按钮即可放行；系统返回键、Esc、点遮罩都不能关闭。**PDA 这里不照搬 Web 的“输入 `ok`”交互**：仓库手持设备主要靠触屏/物理按键操作，额外打字成本过高；硬门禁由“弹窗只有确认按钮一条出路”保证。
- **确认后才标记已收货，且这一步不卡在放行路径上**：`POST /api/receiving-alerts/{id}/acknowledge`（同样在 `dock-receiving` 权限白名单里）——**必须先弹窗、用户确认后再调用**，不能反过来（如果入库时就直接标 Received，随后的 match 查询会查不到这条规则，弹窗永远不会出现）。但"用户确认后再调用"不等于"调用完了才放行"：web 是用户一确认就立刻关弹窗、放行调用方，`acknowledge` 请求本身是紧跟着异步发出去的尾随动作，成功与否都不影响操作员已经能继续下一件——失败也不重试、下次同一运单号再入库还会再触发一次匹配（自然补偿）。这一点在 1.3 节 `confirmAlertAck()` 的实现细节里容易写反，务必对照清楚。

### 1.3 PDA 要做的改动

**API 层**（在现有 [`ReceivingApiService.kt`](../app/src/main/kotlin/com/pda/app/data/api/ReceivingApiService.kt:25) 接口里追加两个方法，DTO 放进 `ReceivingModels.kt`，不要新建 service 文件）：
```kotlin
data class ReceivingAlertDto(
    val id: String,          // Guid，服务端 camelCase 序列化为字符串
    val trackingNumber: String,
    val instruction: String,
    val status: String
    // 其余字段（serialNumber/rmaNumber/email/...)按需精简，PDA 只用得上 id + instruction
)

@GET("api/receiving-alerts/match")
suspend fun matchReceivingAlert(@Query("trackingNumber") trackingNumber: String): Response<ReceivingAlertDto?>

@POST("api/receiving-alerts/{id}/acknowledge")
suspend fun acknowledgeReceivingAlert(@Path("id") id: String): Response<ReceivingAlertDto>
```
> 注意 `match` 命中的返回体，未命中返回 JSON `null`（HTTP 200，body 是字面量 `null`），要确认 Retrofit + kotlinx-serialization 这层能正确解析成 `null` 而不是抛异常（当前 `Json { coerceInputValues = true }` 应该没问题，但要写个单测覆盖）。

**Repository 层**（`ReceivingRepository.kt`）：加 `matchAlert(trackingNumber)` / `acknowledgeAlert(id)` 两个 `Flow<NetworkResult<...>>` 方法，走既有的 try/catch + emit 模式；查询/确认失败都在 ViewModel 层吞掉，不当错误展示给用户（对齐 web "查询失败直接放行"的取舍）。

再加一个非 suspend 的 `acknowledgeAlertBestEffort(id: String)` 包装（见 1.3 节 ViewModel 部分为什么不能用 `viewModelScope`）：

```kotlin
// ReceivingRepository 是 @Singleton，天然活到进程结束（跟第 3 节 CustomerDirectory 用自己的
// scope 是同一个理由）。acknowledgeAlert 这个 best-effort 请求专门用这个 scope 发起，
// 不能用调用方（DockReceivingViewModel）的 viewModelScope——用户点确认之后完全可能立刻关批次
// 或退出 Dock 页面，这时 viewModelScope 会被 ViewModel.onCleared() 取消掉，如果请求挂在
// viewModelScope 下，会被这个取消提前打断，请求实际上根本没发出去/没跑完，
// 而不是"发了但服务端没处理完"这种更常见的失败。
private val bestEffortScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * 确认收货预警后调用，不等待、不关心成功与否。ack 失败/被打断的代价只是"下次同一运单号
 * 再入库还会重新触发一次提醒"（跟查询失败的兜底是一回事），不影响任何已经完成的入库数据，
 * 所以特意设计成不可观测、不重试的 fire-and-forget——调用方不需要、也不应该 launch 它。
 *
 * 唯一没覆盖的场景是整个 App 进程被杀掉（不只是这个页面/ViewModel），这种更极端的情况下
 * 请求确实会跟着丢失，属于可接受的行为，不需要上 WorkManager 这类能跨进程重启的方案兜底。
 */
fun acknowledgeAlertBestEffort(id: String) {
    bestEffortScope.launch {
        acknowledgeAlert(id).collect { /* 结果不关心，Flow 内部已经把异常吞成 Error 分支 */ }
    }
}
```

**ViewModel 层（关键：不能是 fire-and-forget，要串行阻塞）**：

⚠️ 这里最容易踩的坑——查过现有代码，`createItem` 成功后的处理（[DockReceivingViewModel.kt:404](../app/src/main/kotlin/com/pda/app/ui/dockreceiving/DockReceivingViewModel.kt:404) 拍照/手输路径、line 490 扫码路径）**立刻**用一个全新的 `ConfirmState`（或 `confirm = null`）替换草稿，相当于马上把"可以录下一件"的门打开；而拍照快门按钮的 `ready` 开关（[DockReceivingScreen.kt:662](../app/src/main/kotlin/com/pda/app/ui/dockreceiving/DockReceivingScreen.kt:662)）现在只看镜头是否稳定，**不看任何 saving/analyzing 状态**——也就是说操作员现在随时能连拍，处理管线本身就是为"连续快速拍"设计的。如果照最初想法在 `createItem` 成功之后另起一个 `viewModelScope.launch { matchAlert(...) }`，这个新协程跟"重置 ConfirmState、开放下一次拍照"完全不同步：`matchAlert` 的网络往返（哪怕只有几百毫秒）足够操作员已经拍完下一张、甚至下一张也自动提交完了，提醒弹窗才姗姗来迟——这就是 web 用 `await ackIfAlert(...)` 卡在同一条 await 链里、`finally` 里的 `setSubmitting(false)` 要等到弹窗流程走完才执行的原因（[PhotoTab.tsx:236-262](../../RMA/rma-frontend/client/src/components/dock-receiving/PhotoTab.tsx:236) 那段 try/finally，`submitting` 全程包住 `await alertAck.ackIfAlert(tn)`）。

正确的顺序（在**同一个** `viewModelScope.launch` 里顺序 `suspend` 执行，不要另起 `launch`）：

1. `createItem` 成功 → 立刻做没有副作用风险的事：`soundPlayer.playSuccess()`、`c.photoFile?.delete()`、`refreshItems(bid)`、`captureStatus = Success`。
2. **不要在这一步重建"可录下一件"的 `ConfirmState`**——新增一个顶层门禁状态（建议放在 `DockReceivingUiState`，不是 `ConfirmState`，因为 `ConfirmState` 马上要被整体替换）：`pendingAlert: PendingAlertUi?`（命中时非空）+ 一个 `checkingAlert: Boolean`（查询进行中，用于门禁判断，不一定需要单独的 loading UI）。门禁开着（`checkingAlert || pendingAlert != null`）期间：拍照快门按钮 `enabled` 要带上这个条件（`ready = steady && !uiState.alertGateActive`）、扫码模式的输入/自动提交也要挡住（`maybeAutoSubmit()`、`scanItem()` 入口都要检查这个门禁）。
3. 同一协程里 `suspend` 调 `repo.matchAlert(trackingNo)`（不是另起 launch）：
   - 未命中 / 查询失败 → 门禁关闭，**这时才**执行原来"立刻"要做的那次 `ConfirmState` 重置（对齐 web "查询失败直接放行"）。
   - 命中 → 设置 `pendingAlert`，门禁继续开着，等 UI 层弹窗调用 `confirmAlertAck()`。
4. `confirmAlertAck()`（弹窗里用户点确认按钮后调用）：⚠️ **顺序不能写反，而且不能用 `viewModelScope`**——先同步清空 `pendingAlert`、关闭门禁、执行"重置 ConfirmState、打开下一次拍照"这一步，**然后才**调用 `repo.acknowledgeAlertBestEffort(id)`（见上面 Repository 层，这是个普通同步方法，内部自己 `launch` 到它自己的 `bestEffortScope`，`confirmAlertAck()` 这边不需要、也不应该再包一层 `viewModelScope.launch`）。**不能用 `viewModelScope.launch { acknowledgeAlert(id) }` 这种写法**：用户点确认之后完全可能马上关批次或退出 Dock 页面，`viewModelScope` 会随 ViewModel 一起被取消，这个请求可能压根没发出去就被打断——这跟"请求发了但失败/超时"是两种不同严重程度的情况，`acknowledgeAlertBestEffort` 挂在 Repository 自己的进程级 scope 下就不受这个页面生命周期影响。对照 web 的 [`useReceivingAlertAck.tsx` 的 `settle()`](../../RMA/rma-frontend/client/src/components/dock-receiving/useReceivingAlertAck.tsx)：
   ```ts
   const settle = useCallback(async () => {
     const alertId = pending?.alertId;
     // 先放行 UI，再异步标 Received——标失败不能把操作员卡在弹窗里（包裹已入库）。
     resolveRef.current?.();
     resolveRef.current = null;
     setPending(null);
     setTyped("");
     if (!alertId) return;
     try {
       await receivingApi.acknowledgeAlert(alertId);
     } catch { /* ... */ }
   }, [pending?.alertId]);
   ```
   注意 `setPending(null)`（关弹窗）跟 `resolveRef.current?.()`（放行调用方）都在 `await receivingApi.acknowledgeAlert(alertId)` **之前**执行——`acknowledgeAlert` 是纯尾随的异步动作，不在放行路径上。**这一点很容易写反**：如果照第 3 步"未命中"分支那种"先 await 再放行"的写法照搬过来，变成"先 `await acknowledgeAlert(id)` 再清空 `pendingAlert`/开门禁"，会导致 `acknowledgeAlert` 的网络请求（走跟其它请求一样的 `OkHttpClient`，`readTimeout` 是 30 秒，见 [NetworkModule.kt:40](../app/src/main/kotlin/com/pda/app/di/NetworkModule.kt:40)）变成操作员输完 "ok" 之后的一道新阻塞——用户已经确认过了，不应该还要再等一次网络往返，更不用说最坏情况下等满 30 秒超时。跟第 3 步"门禁只在 matchAlert 完成前才有意义"不是一回事：matchAlert 前的门禁是必须的（不知道有没有提醒），但用户已经确认之后，acknowledgeAlert 是否成功不影响操作员继续下一件。

   > 浏览器里的 `fetch`/axios 请求不会因为组件卸载就自动取消（除非代码显式接 `AbortController`，这里没接）——`settle()` 里那次 `acknowledgeAlert` 哪怕用户马上跳到别的页面，请求本身还是会照常跑完。Android 的 `viewModelScope` 是反过来的：`ViewModel.onCleared()` 会真正取消挂在它下面的协程。这是两个平台默认行为的差异，不是 web 代码"恰好没考虑这个问题"——**照搬 web 的写法到 PDA 时，`viewModelScope.launch { acknowledgeAlert(id) }` 表面上一致，实际行为并不一致**，这也是上面要求改用 `repo.acknowledgeAlertBestEffort(id)`（进程级 scope）的原因。

这套改法比"加一个 fire-and-forget 查询"改动量大一些，但这是唯一能真正保证"操作员不会在提醒弹出之前就开始下一件、确认之后不会被 acknowledge 请求二次卡住、退出页面也不会打断这个尾随请求"的做法——门禁状态直接决定 UI 能不能进入下一次拍照，而不是寄希望于协程调度顺序、网络时序或页面停留时长。

**UI 层**：新增一个 Compose `AlertDialog`（类似现有的 duplicate-tracking 确认弹窗，如果 PDA 已经有类似组件可以抄一份改字段），展示运单号和 `instruction` 原文，只提供一个确认按钮，点击即放行。`onDismissRequest` 传空（不允许点外部关闭），禁用系统返回键关闭（`BackHandler { }` 拦截或 dialog 的 `properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)`）。

**i18n**：`AppStrings.kt` + 三语文件加：`receivingAlert.title`、`receivingAlert.description`（含运单号占位符）、`receivingAlert.confirm`，照抄 `AppStringsParityTest` 已有的三语对齐检查模式。

### 1.4 测试点
- `matchAlert` 返回 `null` → 不弹窗，正常流转到下一件。
- `matchAlert` 返回命中数据 → 弹窗出现，展示运单号和处置指令；点击确认按钮后立即关闭并放行。系统返回键、Esc、点遮罩均不能绕过确认按钮关闭弹窗。
- 确认后调用 `acknowledgeAlert(id)`；`acknowledgeAlert` 失败不应该让弹窗重新打开或报错打断流程。
- **`confirmAlertAck()` 不能等 `acknowledgeAlert` 才放行（P1，必须覆盖）**：把 `acknowledgeAlert` 的请求人为延迟几秒（模拟慢网络/接近 30 秒超时），验证用户点确认之后**立刻**（不等这个延迟）弹窗关闭、门禁打开、能开始下一次拍照——`acknowledgeAlert` 应该是背景里继续跑完的独立协程，不阻塞 `confirmAlertAck()` 本身的返回。
- `matchAlert` 请求本身失败（网络错误）→ 静默放行，不弹窗、不报错提示。
- 入库失败（`createItem` 本身失败）→ 完全不触发 `matchAlert`（web 是 catch 分支里不调用 `alertAck`，PDA 也应该保持一致）。
- **门禁竞态（P1，必须覆盖）**：`matchAlert` 请求人为延迟（测试里用一个可控的 `MutableSharedFlow`/延迟 `Flow` 模拟慢网络），在它返回之前：拍照快门按钮应该是禁用状态、`scanItem()`/`maybeAutoSubmit()` 调用应该是空操作（不应该真的再打一次 `createItem`）；`matchAlert` 返回（无论命中与否）之后，门禁才能打开。
- **命中后门禁不能被绕过**：`pendingAlert` 非空期间，即使外部又触发了一次 `onPhotoCaptured`/`scanItem` 调用，也不能提交新的 `createItem`；必须等 `confirmAlertAck()` 执行完（用户确认）才恢复正常流转。
- **"确认后立即退出页面"（P2，必须覆盖，明确可接受行为）**：`confirmAlertAck()` 之后立刻模拟 `DockReceivingViewModel.onCleared()`（`viewModelScope` 被取消，比如测试里直接 `viewModel.viewModelScope.cancel()` 或走 `ViewModelStore` 清除流程）；验证 `acknowledgeAlertBestEffort` 发出的请求**仍然继续跑完**（不能因为 ViewModel/viewModelScope 没了就被取消掉）——如果实现时图省事把它写成 `viewModelScope.launch { acknowledgeAlert(id) }`，这条测试应该失败。**明确的可接受边界**：这条测试只覆盖"页面/ViewModel 被清除"这一级别，不覆盖"整个 App 进程被系统杀掉"——后者请求确实会跟着丢失，属于设计上就接受的行为（下次同一运单号再入库还会重新触发提醒），不需要额外测试或用 WorkManager 之类方案兜底。

---

## 2. AI 识别客户时走 Alias 匹配

### 2.1 现状：PDA 只认 `customerCode`

[`DockReceivingConstants.kt:23`](../app/src/main/kotlin/com/pda/app/ui/dockreceiving/DockReceivingConstants.kt) 的 `resolveCustomerFromAnalyze`：命中 `customerCode`（UF 编码）用数据库客户；命中不了或者 AI 根本没识别出编码，直接把 AI 给的 `customerName` 原文存下来，`customerId` 留空——**没有任何按名字/别名匹配客户表的逻辑**。`ActiveCustomer` DTO（[CustomerModels.kt:15](../app/src/main/kotlin/com/pda/app/data/api/model/CustomerModels.kt:15)）也没有 alias 字段，`/api/customers` 拉回来的数据根本不带这个信息。

### 2.2 现状更新：RMA 后端已经实现并合并

> 本节内容已按 2026-09-18 的最新状态重写——之前的版本判断"RMA 还没提交合并、PDA 只能先做模型和单测"，那是基于当时 `RMA` 仓库还有一堆本地未提交改动的快照。现在 `git -C ../RMA status` 是 clean，改动已经在 `6d2f65c 增加ai识别alias` 里合并：

- 后端：`Customer.cs` 加了 `Alias` 属性，EF 迁移 `20260918174708_AddAliasToCustomers` 已落地；`CustomerService.cs`/`CustomerEndpoints.cs` 里 `GetAllAsync`（对应 `GET /api/customers`）返回体已经带 `alias` 字段；新增了 `POST /api/customers/check-alias` 做别名冲突预检（客户管理界面用，PDA 不需要接这个）。
- 前端：`returnClient.ts` 已经有 `matchCustomerByCodeOrAlias` + `mapActiveCustomers` 的 alias 解析，`PhotoTab.tsx`/`ReceivingEntryDrawer.tsx` 三个接入点都已经切过去，`returnClient.test.ts` 也补了完整用例（本文档 2.4 节的测试点就是照抄这份）。

**所以第 2 项现在不是阻塞项，可以按 2.3/2.4 节直接实现并接入真实请求**，唯一要做的前置确认是：**PDA 当前指向的部署环境是否已经跑上这次改动**。PDA 的 `BuildConfig.RMA_BASE_URL`（debug/release 都是）指向 `https://fbd.shipswithus.com/fbd-rma-api/`——这是代码仓库之外的部署状态，仓库合并不代表这个环境已经部署了最新代码，接入前建议先手动 `GET /api/customers` 拿一条真实客户数据确认返回体里有 `alias` 字段（哪怕值是 `null`），而不是假设"仓库有就等于线上有"。

### 2.3 目标行为（照抄 RMA `shippinglabel识别.md` 第 2 节的匹配规则）

```
customerCode 命中？
  ├─ 是 → 用命中客户（不变）
  └─ 否 → 按 Alias 匹配？
           ├─ 命中一个客户 → 用该客户
           ├─ 命中多个客户 → 判未匹配（不自动选，避免选错比不选更糟）
           └─ 都没命中 → 存 AI 识别的原始文本，customerId 留 null（现有兜底逻辑不变）
```

匹配规则细节（跟 [`returnClient.ts`](../../RMA/rma-frontend/client/src/utils/returnClient.ts) 的 `matchCustomerByCodeOrAlias` 保持一致，避免两端漂移）：
- 别名短于 3 个字符不参与匹配（后端保存时就会拒绝，理论上不会出现，但前端/PDA 侧防御性地也过滤一遍）。
- **3-4 个字符**的别名按"整词边界"匹配（前后不能紧贴字母/数字），例如别名 `eco` 命中独立出现的 `"ECO"`，但不命中 `"ECOLOGY"`/`"DECOR"`。
- **≥5 个字符**的别名按裸 `contains` 匹配。
- 别名必须跟 OCR 原文片段完全一致（含空格、连字符），不做归一化——`e-conology` ≠ `econology`。
- 只匹配 `IsActive` 客户。

### 2.4 PDA 要做的改动

**数据模型**：
```kotlin
// CustomerModels.kt
data class CustomerDto(
    val id: Long,
    val customerCode: String = "",
    val customerName: String = "",
    val isActive: Boolean = true,
    val alias: String? = null   // 新增：GET /api/customers 返回的原始逗号分隔字符串
)

data class ActiveCustomer(
    val id: Long,
    val code: String,
    val name: String,
    val aliases: List<String> = emptyList()   // 新增：拆分/trim/小写/过滤过短值之后的结果
)
```

**匹配函数**（新增到 `DockReceivingConstants.kt`，跟 `resolveCustomerFromAnalyze` 相邻）：
```kotlin
private const val MIN_ALIAS_LENGTH = 3
private const val WHOLE_WORD_MAX_LENGTH = 4

// alias 在 parseAliasField 里已经转过小写，haystackLower 调用方也要先转小写——两边都已是小写，
// 这里不需要再叠加 RegexOption.IGNORE_CASE（低优先级简化，功能不受影响，写错也不会出错）。
private fun aliasMatches(alias: String, haystackLower: String): Boolean {
    if (alias.length > WHOLE_WORD_MAX_LENGTH) return haystackLower.contains(alias)
    val re = Regex("(?<![a-z0-9])${Regex.escape(alias)}(?![a-z0-9])")
    return re.containsMatchIn(haystackLower)
}

fun matchCustomerByCodeOrAlias(
    rawCode: String?,
    rawName: String?,
    activeCustomers: List<ActiveCustomer>
): ActiveCustomer? {
    // lowercase(Locale.ROOT) 而不是无参 lowercase()：避免运行设备语言是土耳其语时 "I"/"i" 的
    // locale-sensitive 大小写转换把编码/别名比对搞错（低优先级，但既然要写就写对）。
    val code = rawCode?.trim()?.lowercase(Locale.ROOT)
    if (code != null) {
        activeCustomers.firstOrNull { it.code.lowercase(Locale.ROOT) == code }?.let { return it }
    }
    val name = rawName?.trim()?.lowercase(Locale.ROOT) ?: return null
    val hits = activeCustomers.filter { c -> c.aliases.any { aliasMatches(it, name) } }
    return hits.singleOrNull()
}
```
（注意：这个函数跟现有的 `resolveCustomerFromAnalyze` 是两套不同的返回形状——后者是 `Pair<Long?, String>` 且承担"没命中就回退成原始文本"的职责。建议 `resolveCustomerFromAnalyze` 内部在 `codeKey` 没命中之后，调用这个新函数做兜底，命中就返回该客户的 id/name，没命中才走"存原始文本"这条老路径。）

**Repository 层**：`CustomerRepository.getActiveCustomers()` 里补上 `aliases = parseAliasField(it.alias)` 的映射（拆分小写+过滤短别名，逻辑跟上面 `matchCustomerByCodeOrAlias` 用的规则一致，抽成一个 `parseAliasField(raw: String?): List<String>` 共享函数）。

**单测**：直接照抄 [`shippinglabel识别.md` 第 5 节](../../RMA/docs/shippinglabel识别.md) 列出的用例搬到 Kotlin，包括几个真实踩过坑的边界（保证两端行为不漂移）：
- `customerCode` 精确匹配优先于 alias。
- `ef tiktok` → `"EF TikTok RETURN"`（≥5 字符 contains）。
- `e-conology` → `"E-CONOLOGY"` 命中，但 `econology`（缺连字符）不应该误报命中。
- `bambu lab` → `"BAMBU LAB"` 命中，但 `bambulab`（缺空格）不应该误报命中带空格原文。
- `eco` 命中独立文本 `"ECO"`，但不命中 `"DECOR"`/`"ECOLOGY"`。
- alias 命中多个客户 → 返回 `null`。
- 别名短于 3 字符在解析阶段就被过滤掉，不参与匹配。
- 大小写、首尾空格不影响结果。

### 2.5 接入前确认清单（不再是阻塞项，但接入前建议走一遍）

- [ ] 确认 PDA 当前指向的部署环境（`https://fbd.shipswithus.com/fbd-rma-api/`，release/debug 都是同一个，见 [build.gradle.kts:22](../app/build.gradle.kts:22)）已经跑上 RMA `6d2f65c` 这次改动——手动 `GET /api/customers` 拿一条数据确认返回体有 `alias` 字段。全局响应用 `JsonNamingPolicy.CamelCase`（[Program.cs:29](../../RMA/rma-system/backend/RmaSystem.Api/Program.cs:29)），字段名是小写开头的 `alias`，跟 PDA `Json { ignoreUnknownKeys = true }` 能正常反序列化，理论上不需要额外适配，但联调时第一次实际验证一下。
- [ ] 用真实几个客户的 alias 数据（比如 `docs/alias-seed-apply.sql` 里已经灌进生产库的那批：EcoFlow/`eco`、Bambulab/`bambu lab`、DJI 等）跑一遍 PDA 单测 + 手动拍照验证（不要只信 mock 数据）。
- [ ] `GET /api/customers` 目前是无参数拉全量客户，不分页——如果客户表体量变大，注意跟第 3 节的全局缓存一起评估请求体大小，暂时不是问题但值得留意。

---

## 3. 全局数据缓存：Customer 列表登录时加载一次

### 3.1 现状：每次开批都重新拉一遍

[`DockReceivingViewModel.kt:69`](../app/src/main/kotlin/com/pda/app/ui/dockreceiving/DockReceivingViewModel.kt:69)：
```kotlin
is NetworkResult.Success -> {
    refreshActiveCustomers()   // 每次 startBatch() 成功都重新 GET /api/customers
    ...
}
```
`startBatch()` 是高频操作（每开一个新批次都会调一次），在仓库现场网络条件本来就不算稳定的场景下，这是一次完全可以省掉的网络往返——客户主数据的变化频率远低于"每次开批"的频率。

> 补充：查证过 web 前端**自己也没有做这个优化**——`PhotoTab.tsx`/`ReceivingEntryDrawer.tsx` 各自在组件挂载的 `useEffect` 里独立调 `customersApi.list()`，没有全局 context/React Query 缓存。所以这一条不是"对齐 web 的更优架构"，而是 **PDA 自己的架构改进**：PC 端浏览器标签页常驻、网络稳定，重复请求成本低；PDA 是仓库现场手持设备，`startBatch` 又是高频操作，值得单独优化，不需要等 web 那边先做。

### 3.2 目标：登录成功后加载一次，进程内复用

参考现有 `SessionManager`（[SessionManager.kt](../app/src/main/kotlin/com/pda/app/data/session/SessionManager.kt)）已经是持有会话状态的 Hilt Singleton 这个既有模式，新增一个同级的 `CustomerDirectory` singleton（或者直接扩展 `SessionManager`，两种都可以，倾向前者以保持 `SessionManager` 职责单一）：

> ⚠️ 这段代码是本文档第四次修订，累计修了三类问题：
> 1. 第二版：`myGeneration == generation` 判断 + `job.join()` 挂着锁等网络——"校验 generation"和"写入
>    `_customers`/`loaded`"是两条分开的语句，中间有没有互斥保护的窗口，`session` 变化的协程可能正好插进
>    这个窗口；持锁等网络 IO 也有死锁隐患。第三版把锁收窄成只保护同步字段读写，核对+写入放进同一个临界区。
> 2. 第三版仍然有个问题（这版修）：`init` 里那个 `sessionManager.session.map{...}.distinctUntilChanged()`
>    是**从这个 collector 自己的视角**判断"变没变"——`StateFlow.collect` 对一个新订阅的 collector 永远会先
>    回放当前值，而 `distinctUntilChanged()` 只在自己的历史上比较，所以第一次收到的值**必然**被判定成"变化"，
>    哪怕这个 session 早就存在、根本不是这一刻才变的。如果登录成功后 `ensureLoaded()` 恰好先于这个 collector
>    拿到调度（两者都在 `Dispatchers.Default` 线程池上，谁先跑不是确定的），`ensureLoaded()` 会在
>    `generation=0` 时正常发起请求；随后 collector 才第一次追上现状，把这次"回放"误判成"会话变化"，
>    `generation++` 并连带取消掉这个刚发起、其实完全合法的请求——消费端这次 `ensureLoaded()` 会返回空列表，
>    第一单可能就用空数据做客户匹配了。
>
> 根子问题是"`ensureLoaded()` 的 `generation`"和"collector 的 `distinctUntilChanged()`"是两套互相不知道
>对方存在的"变化判断"逻辑。修法：两边都不自己判断，统一经过同一个 `syncObservedTokenLocked()`，拿同一个
> `observedToken` 字段（受同一把 `stateMutex` 保护）跟 `sessionManager.currentToken`/collector 收到的 token
> 比较——**谁先调用、谁就先把 `observedToken` 更新掉，后到的一方发现 `observedToken` 已经是自己看到的值，
> 就知道这不是一次新变化，什么都不用做**。`Job.cancel()` 本身是非阻塞的信号（不等它跑完），所以放进这同一个
> 临界区里调用不违反"锁不能跨网络等待"的约束。

```kotlin
@Singleton
class CustomerDirectory @Inject constructor(
    private val customerRepo: CustomerRepository,
    private val sessionManager: SessionManager   // 只读它，绝不能反过来让 SessionManager 持有 CustomerDirectory，见下面"清理时机"
) {
    private val _customers = MutableStateFlow<List<ActiveCustomer>>(emptyList())
    val customers: StateFlow<List<ActiveCustomer>> = _customers.asStateFlow()

    // stateMutex 只保护下面这几个字段的读写，绝不跨越 deferred.await() 这种网络等待——
    // 加锁的代码块必须是纯同步、瞬间完成的，这样"session 变化清空缓存"和"加载结果落地"
    // 才能真正互斥，不会有中间窗口。
    private val stateMutex = Mutex()
    private var generation = 0
    private var loaded = false
    private var inFlight: Deferred<List<ActiveCustomer>?>? = null   // null 结果代表这一次加载失败
    // "当前状态是围绕哪个 token 建立的"——ensureLoaded() 和下面的 session collector 都要经过
    // syncObservedTokenLocked() 跟这个字段比较，不能各自维护一套"变没变"的判断逻辑。初始值 null
    // 正好对应"还没登录"这个默认状态，不需要额外哨兵值。
    private var observedToken: String? = null

    // Hilt Singleton 存活期跟进程一样长，这里用一个自持的 scope。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            sessionManager.session
                .map { it?.token }
                .collect { token -> stateMutex.withLock { syncObservedTokenLocked(token) } }
        }
    }

    /**
     * 必须在持有 stateMutex 时调用。核对 [token] 是否跟 [observedToken] 不同——不同才是一次真正的
     * 会话变化（登出/401 过期/重新登录切换成另一个非空 session），推进 generation、清空缓存、取消
     * 在途请求；跟 [observedToken] 相同就什么都不做（这正是修复"collector 第一次回放被误判成变化"
     * 的关键：谁先观察到某个 token，谁就把它记下来，后来者不会重复处理同一次变化）。
     */
    private fun syncObservedTokenLocked(token: String?) {
        if (observedToken == token) return
        observedToken = token
        generation++
        loaded = false
        _customers.value = emptyList()
        inFlight?.cancel()   // 非阻塞信号，不等它跑完；就算这行完全不生效，下面 generation 核对仍保证正确性
        inFlight = null
    }

    /**
     * 已加载过就直接返回；未加载/`forceRefresh` 时发起（或复用别的调用者已经发起的）一次加载，
     * 挂起到结果落地（或被判定过期丢弃）再返回。多个调用者并发调用只会触发一次真正的网络请求。
     */
    suspend fun ensureLoaded(forceRefresh: Boolean = false) {
        // 第一步：在锁内决定"用谁的 Deferred"，不等待网络——这一步必须极快，不能把网络 await 放进来。
        val claim = stateMutex.withLock {
            // 不依赖"反应式 collector 迟早会追上"：每次调用先自己核对一次当前 token，覆盖
            // "登录成功、ensureLoaded() 先于 collector 拿到调度"这种情况——collector 后面再收到
            // 同一个 token 时，会在 syncObservedTokenLocked() 里发现 observedToken 已经是这个值，
            // 直接跳过，不会把这次请求误判成旧会话的请求给取消掉。
            syncObservedTokenLocked(sessionManager.currentToken)
            if (loaded && !forceRefresh) return
            val reusable = inFlight
            if (reusable != null && !forceRefresh) {
                generation to reusable
            } else {
                val myGeneration = generation
                // customerRepo.getActiveCustomers() 是 Loading → 一个终结 Success/Error 的 Flow
                // （既有 Repository 的通用模式），这里只关心那个终结结果。
                val deferred = scope.async {
                    val result = customerRepo.getActiveCustomers()
                        .first { it !is NetworkResult.Loading }
                    (result as? NetworkResult.Success)?.data
                }
                inFlight = deferred
                myGeneration to deferred
            }
        }
        val (myGeneration, deferred) = claim

        // 第二步：网络等待完全在锁外，不持锁等 IO，不会跟 session 变化的清空逻辑产生死锁或长时间互斥。
        //
        // ⚠️ 实现时注意：不能直接写 `runCatching { deferred.await() }.getOrNull()`。deferred 虽然
        // 是 CustomerDirectory.scope 的子协程、结构上跟调用方所在的协程无关，但 await() 本身是在
        // 调用方的协程里挂起的——如果调用方自己被取消（比如这是从 viewModelScope 发起的调用，
        // ViewModel 被清除了），await() 同样会抛 CancellationException，这跟"deferred 自己被
        // syncObservedTokenLocked() cancel 掉"是两种不同来源、但外观相同的异常，`runCatching`
        // 会不分青红皂白全部吞掉——这样一来 ensureLoaded() 会在调用方已经被取消的情况下继续往下跑、
        // 最后"正常返回"，破坏了协程取消语义（调用方以为自己被取消了，实际上这个函数还是跑完了）。
        // 要显式区分，只吞"deferred 自己过期"这一种，调用方自己被取消要照常重新抛出去：
        val data = try {
            deferred.await()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e   // 调用方自己被取消：重新抛出，别吞
            null   // 调用方仍然 active，说明是 deferred 自己被取消（过期请求）——按失败处理，继续往下走
        } catch (e: Exception) {
            null   // 其它异常（网络错误等）按失败处理
        }

        // 第三步：核对 generation + 写入结果 + 清理 inFlight，三件事在同一个临界区内原子完成——
        // 不会被 session 变化的清空逻辑插到"核对通过"和"写入"之间。
        stateMutex.withLock {
            if (inFlight === deferred) inFlight = null
            if (myGeneration != generation) return@withLock   // 这一代已经作废，结果直接丢弃
            if (data != null) {
                _customers.value = data
                loaded = true
            }
            // data == null（失败）：不覆盖、不置 loaded=true，下次 ensureLoaded() 会重试。
        }
    }
}
```

（`forceRefresh` 并发调用是个次要边界情况：两次 `forceRefresh=true` 几乎同时发起、且中途没有 session 变化时，`generation` 不会变，后完成的那次请求会覆盖先完成的那次——通常业务上不构成问题，如果要严格解决可以再加一个单调递增的 `loadId` 而不是复用 `generation`，这次先不做，除非确实需要频繁并发手动刷新。）

**加载时机（要解决"登录后立刻进 Dock、拍第一张时列表还没加载完"这个竞态）**：
- `LoginViewModel.login(...)` 成功后调用一次 `customerDirectory.ensureLoaded()`，fire-and-forget，不阻塞跳转到 Home——这是"尽量提前预热"，不是唯一保障。
- **真正的保障在消费端**：`DockReceivingViewModel.startBatch()` 成功回调、以及 AI 识别结果回来准备调用 `resolveCustomerFromAnalyze` 之前，都 `suspend` 调用一次 `customerDirectory.ensureLoaded()`（不是 fire-and-forget，要 `await`）。因为 `ensureLoaded()` 已加载过就是一次 `loaded` 布尔判断直接返回，几乎零开销，所以哪怕登录时已经预热过，这里再调一次也不会有额外网络请求；但如果登录后的预热请求还没回来（比如登录成功后用户手速极快直接进 Dock 开批拍照），这里会真正等它拉完，保证"第一单"也能吃到客户列表，而不是退化成空列表且再也不会重试。
- 需要补的测试：模拟 `ensureLoaded()` 的底层请求有延迟，验证在它完成之前调用 `resolveCustomerFromAnalyze` 相关流程时，是等待加载完成后再匹配，而不是直接拿到空列表匹配失败且不重试。

**清理时机**：不要让 `SessionManager.clear()`/`expire()` 直接调用 `customerDirectory.clear()`——`CustomerDirectory` 已经依赖 `CustomerRepository`，而 `CustomerRepository → CustomerApiService → Retrofit → OkHttpClient → AuthInterceptor → SessionManager` 这条链路本来就存在，如果反过来让 `SessionManager` 持有/调用 `CustomerDirectory`，会在 Hilt 图里绕出一个环（`SessionManager → CustomerDirectory → ... → AuthInterceptor → SessionManager`），编译或运行时会出问题。上面样例代码改成**`CustomerDirectory` 单向依赖 `SessionManager`、被动观察 `session` 流**，这样清理逻辑自己收口，不需要 `HomeViewModel` 登出、`AuthInterceptor` 401 两处调用点都记得手动清一次，也不会绕出依赖环。

**`DockReceivingViewModel` 改动**：把 `refreshActiveCustomers()` 从 `startBatch()` 成功回调里删掉，改成 `suspend` 调 `customerDirectory.ensureLoaded()` 之后读 `customerDirectory.customers.value` 取当前快照（`resolveCustomerFromAnalyze` 是同步调用，不需要订阅 `StateFlow` 持续更新，取一次快照即可，但取之前必须先 `ensureLoaded()`）。

**要不要提供手动刷新**：建议加一个轻量入口（比如 Dock Receiving 页面下拉刷新，或者切换仓库时顺带 `forceRefresh = true` 调一次）——客户主数据虽然低频变化，但新建客户当天就要在 PDA 上能识别到的场景是存在的（对照 RMA 那份 alias 文档，客户经常是当天临时建的）。具体交互方式（下拉刷新 / 设置页手动按钮 / 仅登录时加载不提供手动刷新）需要跟你确认，见下面"待确认问题"。

### 3.3 测试点
- 登录成功后只调用一次 `GET /api/customers`；同一登录会话内开多个批次不应该再次请求。
- 登出后再登录（同一进程内）应该重新加载一次，不沿用上一个账号缓存的数据；`SessionManager.expire()`（token 过期 401）同样要触发清空，不能只覆盖 `clear()` 这一条路径。
- **并发/时序**：登录后的预热请求还没返回时就进入 Dock 开批/拍第一张照片，客户匹配应该等 `ensureLoaded()` 完成后再进行，而不是拿到空列表直接判定"未匹配"且之后不再重试。
- `ensureLoaded()` 被多个调用点（登录预热 + Dock 消费端）短时间内并发调用，只应该触发一次真正的网络请求（验证共享 `inFlight` Deferred 生效，没有重复的 `GET /api/customers`）。
- **generation 校验与写入的原子性（P1，必须覆盖）**：模拟一次加载的网络请求已经完成、正准备进入"核对 generation + 写入"这个临界区的瞬间，session 恰好发生变化（比如用 `TestDispatcher` 精确控制两个协程的执行顺序，让 session 变化的 `collect` 和加载结果的提交在同一个"检查点"之间交错）；验证最终 `customers`/`loaded` 是"session 变化后清空"的状态，旧结果不会在核对和写入之间的空档被塞进去——这条测的是"原子性"本身，跟上面"加载中登出"那条测的是"两件事发生的先后顺序"不完全一样，两条都要留着。
- 加载失败（网络错误）不应该阻塞登录流程；`activeCustomers` 降级为空列表，客户识别退化成"只能手填"，不影响运单号识别主流程；且 `loaded` 不应该被置为 `true`，下次 `ensureLoaded()` 要能重试。
- 如果实现手动刷新：验证 `forceRefresh` 确实发起新请求并覆盖旧数据。
- **"加载中登出"（P1，必须覆盖）**：`ensureLoaded()` 的底层请求人为延迟到登出动作之后才返回成功；验证 `customers`/`loaded` 最终仍然是登出后的空状态，不能被这个"迟到"的成功结果覆盖回去。
- **"加载中登出后立即换账号登录"（P1，必须覆盖）**：账号 A 的 `ensureLoaded()` 请求还在路上时触发登出、再登录账号 B；账号 A 的请求稍后才返回（带着账号 A 的客户列表）；验证最终 `customers` 是账号 B 的数据（或者账号 B 自己触发的加载还没完成时是空列表），绝不能是账号 A 的数据——即验证 `generation` 校验确实生效，而不是靠"清一次就够了"的假设。
- **"登录后 collector 尚未处理 token，Dock 已调用 ensureLoaded"（P1，必须覆盖）**：用 `TestDispatcher`/手动控制协程调度顺序，让 `init` 里那个订阅 `sessionManager.session` 的 collector 协程在登录成功后**故意不给它执行机会**，抢在它之前直接调用 `ensureLoaded()`；验证这次 `ensureLoaded()` 依然能正常发起请求、拿到结果、写入 `customers`/`loaded=true`，不会被"稍后才轮到执行"的 collector 误判成需要清空/取消的旧会话请求。这条测的正是 `syncObservedTokenLocked()` 存在的意义——如果实现时退回到"只让 collector 里的 `distinctUntilChanged()` 判断变化"，这条测试应该失败。

---

## 4. 建议实施顺序

三件事现在都没有代码层面的硬阻塞了，可以按下面顺序排期，也可以并行：

1. **第 3 项（客户列表缓存）**——纯 PDA 内部改动，无外部依赖，建议优先做或者跟第 2 项一起做：第 2 项的 `matchCustomerByCodeOrAlias` 要吃 `ActiveCustomer.aliases`，而 `ActiveCustomer` 从哪里加载（`refreshActiveCustomers()` 还是新的 `CustomerDirectory`）会影响 2.4 节里 `resolveCustomerFromAnalyze` 具体怎么拿到 `activeCustomers` 这个参数——两个一起做能避免改两遍调用点。
2. **第 2 项（Alias 匹配）**——后端已就绪，接入前先过一遍 2.5 节的确认清单（主要是确认部署环境已经跑上最新代码），确认完就能直接实现 + 联调。
3. **第 1 项（收货预警）**——后端接口已上线、无阻塞，可以独立排期，跟前两项没有依赖关系。

## 5. 待确认问题

- 第 3 项的手动刷新入口要不要做、做成什么交互（下拉刷新 / 独立按钮 / 干脆不做，等下次登录自然刷新）？
- 第 1 项的弹窗确认后，`acknowledgeAlert` 调用失败要不要给操作员一个可见的失败提示（web 是完全静默失败），还是保持跟 web 一致的静默策略？
- 第 3 项的 `CustomerDirectory` 要不要在 Dock Receiving 页面提供一个"客户列表最后更新时间"之类的调试信息（方便现场排查"为什么这个客户没识别出来"是因为列表没刷新还是 alias 没配），还是先不做，等真的遇到问题再加？
