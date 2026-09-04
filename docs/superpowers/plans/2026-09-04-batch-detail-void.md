# Batch Detail Void + customerName Implementation Plan

> **For agentic workers:** Implement task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Batch detail rows show customerName and a Void button that calls RMA `POST /api/receiving-items/{id}/void`, confirms first, then removes the row.

**Architecture:** Retrofit void endpoint → ReceivingRepository.voidItem → BatchDetailViewModel removes item on success; Compose AlertDialog + destructive TextButton.

**Tech Stack:** Kotlin, Compose Material3, Hilt, Retrofit, Flow/NetworkResult

## Global Constraints

- User-facing strings via AppStrings (zh/en/es) + AppStringsParityTest
- ViewModel no Context; Chinese error messages for user-facing API errors where existing pattern uses Chinese

---

### Task 1: API + Repository

- [ ] Add `voidItem` to ReceivingApiService
- [ ] Add `voidItem` to ReceivingRepository (+ FakeReceivingApiService override)
- [ ] Optionally map/filter `status == "V"` out of getItems so reload stays clean

### Task 2: ViewModel + UiState

- [ ] Success holds voidingItemId + message
- [ ] `requestVoid` / `confirmVoid` / `cancelVoid` or screen-owned dialog + `voidItem(id)`
- [ ] Unit tests for remove-on-success / keep-on-error

### Task 3: UI + i18n

- [ ] ItemRow: customerName + Void button
- [ ] AlertDialog confirm
- [ ] Strings: void, voidConfirm, voidFailed, voiding
