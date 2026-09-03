# PDA Auto-Save + Customer Name Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (inline) or subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align PDA Dock Receiving with web PhotoTab: recognize tracking/carrier/customerName, auto-save after successful recognition, confirm on duplicate tracking; fix create payload to `photoPaths`.

**Architecture:** Keep confirm draft UI (Approach 2). Extend analyze/create models; add search duplicate API; ViewModel auto-submits once per photo when ready; Screen adds customer field + duplicate dialog.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Retrofit, Kotlinx Serialization, Coroutines/Flow, JUnit.

## Global Constraints

- No customer list / `customerId` matching — only AI `customerName`.
- Duplicate check: `GET /api/receiving-items/search` with `trackingNumberExact` + `receivedDateFrom` (10 days); query failure = not duplicate.
- Same photo auto-submits at most once; manual tracking edits do not re-trigger auto-save.
- User-facing strings in zh/en/es via `AppStrings`.
- MAD stack only; no Gson/Moshi/LiveData.

## File map

| File | Role |
|------|------|
| `ReceivingModels.kt` | `customerName`, `photoPaths`, search DTOs |
| `ReceivingApiService.kt` | `searchItems` |
| `ReceivingRepository.kt` | map customerName; `isDuplicateTracking` |
| `DockReceivingUiState.kt` | customer + duplicate pending + autoSubmitConsumed |
| `DockReceivingViewModel.kt` | auto-submit + duplicate confirm |
| `DockReceivingScreen.kt` | customer field + dialog |
| `AppStrings` + zh/en/es | new strings |
| Tests | models, repo, VM, string parity |

---

### Task 1: Models + API + Repository

**Files:**
- Modify: `app/src/main/kotlin/com/pda/app/data/api/model/ReceivingModels.kt`
- Modify: `app/src/main/kotlin/com/pda/app/data/api/ReceivingApiService.kt`
- Modify: `app/src/main/kotlin/com/pda/app/data/repository/ReceivingRepository.kt`
- Modify: `app/src/test/java/com/pda/app/ReceivingRepositoryTest.kt`
- Modify: fake API stubs in VM tests later

- [ ] **Step 1: Update models**

`CreateItemRequest`: replace `photoPath` with `photoPaths: List<String>? = null`; add `customerName: String? = null`.

`ShippingAnalyzeResponse` / `ShippingAnalysis`: add `customerName: String? = null`.

`ReceivingItemDto` / `ReceivingItemUi`: add `customerName` (default empty on UI).

Add:

```kotlin
@Serializable
data class ReceivingItemSearchPage(
    val items: List<ReceivingItemRowDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val size: Int = 20
)

@Serializable
data class ReceivingItemRowDto(
    val receivingItemId: Int? = null,
    val trackingNumber: String? = null
)
```

- [ ] **Step 2: API + repo**

```kotlin
@GET("api/receiving-items/search")
suspend fun searchItems(
    @Query("trackingNumberExact") trackingNumberExact: String,
    @Query("receivedDateFrom") receivedDateFrom: String,
    @Query("page") page: Int = 1,
    @Query("pageSize") pageSize: Int = 1
): Response<ReceivingItemSearchPage>
```

`analyzeShipping` map `customerName`. `getItems` map `customerName`.

```kotlin
open fun isDuplicateTracking(trackingNumber: String): Flow<NetworkResult<Boolean>> = flow {
    emit(NetworkResult.Loading)
    try {
        val from = LocalDate.now().minusDays(10).toString() // yyyy-MM-dd
        val resp = api.searchItems(trackingNumberExact = trackingNumber, receivedDateFrom = from)
        if (resp.isSuccessful && resp.body() != null) {
            emit(NetworkResult.Success((resp.body()!!.total) > 0))
        } else {
            // Spec: treat search failure as not duplicate
            emit(NetworkResult.Success(false))
        }
    } catch (e: Exception) {
        emit(NetworkResult.Success(false))
    }
}.flowOn(Dispatchers.IO)
```

- [ ] **Step 3: Tests for analyze customerName + photoPaths create + duplicate search**

- [ ] **Step 4: Commit** `feat(data): customerName, photoPaths, duplicate tracking search`

---

### Task 2: ViewModel auto-submit + duplicate confirm

**Files:**
- Modify: `DockReceivingUiState.kt`, `DockReceivingViewModel.kt`
- Modify: `DockReceivingViewModelTest.kt`

**ConfirmState adds:**
- `customerName`, `customerAutoFilled`
- `pendingDuplicateTracking: String?`
- `autoSubmitConsumed: Boolean` (set true when auto path starts or after manual tracking edit)

**Flow:** After barcode+analyze settle and upload OK + tracking non-blank + !autoSubmitConsumed → `tryAutoSubmit()`:
1. set `autoSubmitConsumed = true`
2. `isDuplicateTracking` → if true set `pendingDuplicateTracking`; else `performSave()`
3. `confirmDuplicateSave()` / `dismissDuplicateSave()`

`onTrackingChanged`: set `autoSubmitConsumed = true` (no re-auto).
`onCustomerChanged` for edits.
`saveItem` / `scanItem`: include `customerName`, `photoPaths = photoPath?.let { listOf(it) }`; scan path also checks duplicate first.

Keep internal `photoPath: String?` in ConfirmState for upload URL; only wire `photoPaths` at create.

- [ ] **Step 1: Failing VM tests** (auto-save, customerName in req, duplicate dialog, search error still saves, no auto on blank tracking, no second auto after edit)
- [ ] **Step 2: Implement VM**
- [ ] **Step 3: Tests pass**
- [ ] **Step 4: Commit** `feat(dock): auto-save after OCR with duplicate confirm`

---

### Task 3: UI + i18n

**Files:** Screen + AppStrings + zh/en/es + AppStringsParityTest (automatic via interface)

Strings:
- `dock_customerName`
- `dock_duplicateTitle`
- `fun dock_duplicateBody(tracking: String): String`
- `dock_duplicateConfirm` (仍要加入)
- reuse `common_cancel`

ConfirmFields: customer OutlinedTextField.
AlertDialog when `pendingDuplicateTracking != null`.

- [ ] **Step 1: i18n + Screen**
- [ ] **Step 2: Commit** `feat(ui): customer name field and duplicate dialog`

---

### Task 4: Verify

- [ ] `.\gradlew.bat test`
- [ ] Fix any breakage from `ShippingAnalysis` / `photoPaths` signature changes
