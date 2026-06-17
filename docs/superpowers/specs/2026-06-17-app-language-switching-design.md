# Design: In-App Language Switching (中文 / English / Español)

**Date:** 2026-06-17
**Status:** Approved design — pending implementation plan

## Goal

Let the user pick the app language (Chinese, English, Spanish) on the **login screen**.
The choice is **persisted** and, once selected, **all UI labels, hints, buttons, dialogs,
and status messages** render in that language across every screen — with **no Activity
restart** (instant recomposition).

## Non-Goals / Out of Scope

- **Server-returned error messages stay as-is** (Chinese, from the backend). The app does
  not translate them.
- **Carriers and Conditions stay untranslated.** `CARRIERS = ["UPS", "FedEx", …]` and
  `CONDITIONS = ["Good", "Fair", …]` are business values aligned with the RMA web app and
  sent to the backend. They render identically in all languages.
- **User input is never translated.** Tracking numbers, batch numbers, counts, etc. remain
  digits/English regardless of language.
- No Android `res/values-*/strings.xml` resources, no `Configuration`/`Locale` override in
  `MainActivity`. This is a pure-Compose solution (the app has no XML and no string
  resources for this feature).

## Approach: Option B — `CompositionLocal` + strings object

The app is 100% Jetpack Compose with all text currently hardcoded in Kotlin. We centralize
every UI string into an `AppStrings` interface with one implementation per language, persist
the selected `AppLanguage` in DataStore, and provide the resolved `AppStrings` down the tree
via a `CompositionLocal`. Switching language writes to DataStore; the root re-collects the
flow and re-provides, recomposing every screen instantly.

## Components

New package `com.pda.app.ui.i18n`:

```
ui/i18n/
  AppLanguage.kt      enum: Chinese / English / Spanish (persistedName, displayName, strings)
  AppStrings.kt       interface — all UI strings (vals + parameterized funs)
  EnglishStrings.kt   object EnglishStrings : AppStrings
  ChineseStrings.kt   object ChineseStrings : AppStrings
  SpanishStrings.kt   object SpanishStrings : AppStrings
  LocalAppStrings.kt  staticCompositionLocalOf<AppStrings> { ChineseStrings }
```

### `AppLanguage`

```kotlin
enum class AppLanguage(val persistedName: String, val displayName: String) {
    Chinese("Chinese", "中文"),
    English("English", "English"),
    Spanish("Spanish", "Español");

    // Computed (not a constructor arg) to avoid enum/object init-order concerns.
    val strings: AppStrings
        get() = when (this) {
            Chinese -> ChineseStrings
            English -> EnglishStrings
            Spanish -> SpanishStrings
        }

    companion object {
        /** null/unknown → Chinese (current app default). */
        fun fromName(name: String?): AppLanguage =
            entries.firstOrNull { it.persistedName == name } ?: Chinese
    }
}
```

### `AppStrings` interface (full inventory)

Static text are `val`s; parameterized text are `fun`s. Brand string `"FBD RMA"` stays a
hardcoded constant (brand name, not translated).

**Common**
- `val common_retry` — 重试 / Retry / Reintentar
- `val common_cancel` — 取消 / Cancel / Cancelar
- `val common_back` — 返回 / Back / Atrás (contentDescription)
- `val common_loading` — 加载中… / Loading… / Cargando…

**Login**
- `val login_subtitle` — 仓库管理系统 / Warehouse Management System / Sistema de Gestión de Almacén
- `val login_username` — 用户名 / Username / Usuario
- `val login_password` — 密码 / Password / Contraseña
- `val login_showPassword` / `val login_hidePassword` — 显示/隐藏密码 (contentDescription)
- `val login_rememberCredentials` — 记住账号密码 / Remember credentials / Recordar credenciales
- `val login_loginButton` — 登 录 / Sign In / Iniciar Sesión
- `val login_language` — 语言 / Language / Idioma (selector label)

**Home**
- `val home_selectWarehouse` — 选择仓库 / Select warehouse / Seleccionar almacén
- `val home_switchWarehouse` — 切换仓库 (contentDescription)
- `fun home_greeting(name: String)` — 你好，{name} / Hello, {name} / Hola, {name}
- `val home_selectWarehouseFirst` — 请先选择仓库 / Please select a warehouse first / Seleccione un almacén primero
- `val home_noWarehouses` — 暂无可用仓库 / No warehouses available / No hay almacenes disponibles
- `val home_dockReceive` — Dock 收货 / Dock Receive / Recepción
- `val home_receiveReport` — 收货报表 / Receive Report / Informe de Recepción
- `val home_logout` — 退出登录 (contentDescription)

**Dock Receiving**
- `val dock_title` — Dock 收货 / Dock Receive / Recepción
- `fun dock_batchTitle(number: String)` — 批次 {number} / Batch {number} / Lote {number}
- `val dock_inputMethod` — 录入方式 / Input Method / Método de Entrada
- `val dock_inputMethodPicture` — 拍照 / Picture / Foto
- `val dock_inputMethodBarcode` — 扫码 / Barcode Scan / Escaneo
- `val dock_startBatch` — 开始批次 / Start Batch / Iniciar Lote
- `val dock_closeBatch` — 关闭批次 / Close Batch / Cerrar Lote
- `val dock_close` — 关闭 / Close / Cerrar
- `val dock_confirm` — 确认 / Confirm / Confirmar
- `val dock_scanHint` — 扫描或输入运单号… / Scan or enter tracking #… / Escanear o ingresar # de rastreo…
- `val dock_trackingLabel` — 运单号 / Tracking # / # de Rastreo
- `val dock_carrier` — 承运商 / Carrier / Transportista
- `val dock_condition` — 状态 / Condition / Condición
- `fun itemCount(n: Int)` — {n} 件 / {n} items / {n} artículos (shared: Dock status bar,
  Batch Detail header, Receive Report totals)
- `val dock_uploading` — 上传中… / Uploading… / Subiendo…
- `val dock_analyzing` — 识别中… / Analyzing… / Analizando…
- `val dock_uploadFailed` — 上传失败 — 请重拍 / Upload failed — retake / Error al subir — reintentar
- `val dock_saved` — 已保存 / Saved / Guardado
- `val dock_needsReview` — 需复核 (contentDescription)
- `val dock_noTracking` — （无单号） / (no tracking #) / (sin # de rastreo)
- `val dock_cameraPermission` — full camera-permission message, 3 languages
- `fun dock_closeBatchPrompt(itemCount: Int, needsReviewCount: Int)` — assembles the full
  dialog body sentence per language (each language owns word order; if `needsReviewCount > 0`
  it includes the "N need review" clause)
- `val dock_selectWarehouseFirst` — 请先选择仓库 / Select a warehouse first / Seleccione un almacén primero
- `val dock_photoProcessingFailed` — 照片处理失败 — 请重拍 / Photo processing failed — retake / …
- `fun dock_batchClosed(number: String)` — {number} 已关闭 / {number} closed / {number} cerrado

**Batch Detail**
- `val batch_empty` — 该批次暂无明细 / No items in this batch / No hay artículos en este lote
- (item count uses the shared `fun itemCount(n)` defined under Dock Receiving above)
- `val batch_noTracking` — （无单号） / (no tracking #) / (sin # de rastreo)
- `val batch_needsReview` — 需复核 / Needs review / Requiere revisión

**Receive Report**
- `val report_title` — 收货报表 / Receive Report / Informe de Recepción
- `val report_empty` — 最近三天无收货 / No receipts in the last 3 days / Sin recepciones en los últimos 3 días
- `val report_today` — 今天 / Today / Hoy
- `val report_yesterday` — 昨天 / Yesterday / Ayer
- `fun report_daySummary(batches: Int, items: Int)` — {b} 批 · {i} 件 / {b} batches · {i} items / {b} lotes · {i} artículos

> Where wording is identical (e.g., Dock title vs Home tile), they still get distinct keys for
> clarity, but may share the same literal. The single shared `fun itemCount(n)` is the one
> intentional consolidation.

### `LocalAppStrings`

```kotlin
val LocalAppStrings = staticCompositionLocalOf<AppStrings> { ChineseStrings }
```

`staticCompositionLocalOf` (not `compositionLocalOf`) because the value changes rarely; when
it does we want the whole subtree to recompose, which is exactly the desired behavior.

## Persistence (DataStore)

Extend the existing `UserPreferences` interface + `DataStoreUserPreferences`:

```kotlin
// interface
val appLanguage: Flow<String?>            // AppLanguage.persistedName; null until first pick
suspend fun setAppLanguage(name: String)

// impl
private val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")
override val appLanguage = context.dataStore.data.map { it[KEY_APP_LANGUAGE] }
override suspend fun setAppLanguage(name: String) =
    context.dataStore.edit { it[KEY_APP_LANGUAGE] = name }
```

## Wiring

### Read path — `MainActivity` (pure reader)

`MainActivity` is already `@AndroidEntryPoint`. Inject `UserPreferences`, collect the
language flow, resolve to `AppStrings`, and provide it above the nav host. No setter logic,
no Configuration override.

```kotlin
@Inject lateinit var userPreferences: UserPreferences

setContent {
    val langName by userPreferences.appLanguage.collectAsStateWithLifecycle(initialValue = null)
    val strings = AppLanguage.fromName(langName).strings
    CompositionLocalProvider(LocalAppStrings provides strings) {
        PdaTheme { /* nav host */ }
    }
}
```

Note: `collectAsStateWithLifecycle(initialValue = null)` → `fromName(null)` → `Chinese` for
the first frame, then re-provides once DataStore emits. No flash for Chinese default; a brief
first-frame Chinese flash is acceptable for other languages (sub-frame in practice).

### Write path — `LoginViewModel` (direct to DataStore)

`LoginViewModel` **already injects `UserPreferences`** — no constructor change. Add:

```kotlin
fun selectLanguage(language: AppLanguage) {
    viewModelScope.launch { userPreferences.setAppLanguage(language.persistedName) }
}
```

Any future `SettingsViewModel` does the same — it injects `UserPreferences` and calls
`setAppLanguage`. `MainActivity` is never involved in mutation. This is the key architectural
decision: ViewModels own the write path against the data layer; the Activity only observes.

### Login screen selector UI

A compact language selector at the **top of the login screen** (above the logo), e.g. a row
of three selectable text chips `中文 · English · Español`, current one highlighted. On tap →
`viewModel.selectLanguage(...)`. The login screen reads `LocalAppStrings.current` for its own
labels so it updates instantly when a language is picked.

Current-language highlight is derived by reading back the collected `appLanguage` (the login
screen can collect it via the ViewModel or compare against `LocalAppStrings`). Simplest:
expose the current `AppLanguage` from `LoginViewModel` as a `StateFlow` derived from
`userPreferences.appLanguage`, so the selector reflects the persisted choice.

## Receive Report day-label refactor

`buildReceiveReport()` in `ReceiveReport.kt` currently bakes label strings (`"Today"`,
`"Yesterday"`, `"Jun 15"`) into `ReceiveReportDay.label`. A pure function can't read
`LocalAppStrings`. Refactor:

- `ReceiveReportDay` drops `label: String`, gains `kind: DayKind` (`Today | Yesterday | Older`)
  and keeps `date: LocalDate`.
- `enum class DayKind { Today, Yesterday, Older }`.
- `ReceiveReportScreen` formats the visible header label from `kind` + `date`:
  - `Today` → `LocalAppStrings.current.report_today`
  - `Yesterday` → `LocalAppStrings.current.report_yesterday`
  - `Older` → locale-aware `DateTimeFormatter.ofPattern("MMM d", locale)` where `locale` maps
    from the current `AppLanguage` (zh → `Locale.CHINESE`, en → `Locale.ENGLISH`,
    es → `Locale("es")`). Yields "6月15日" / "Jun 15" / "15 jun".
- `ReceiveReportTest` updated to assert `kind`/`date` instead of label strings (grouping,
  ordering, and 3-day window logic unchanged).

## Testing

- **`AppLanguageTest`** — `fromName(null)` and `fromName("garbage")` → `Chinese`;
  `fromName("English")` → `English`; each entry's `strings` returns the matching object.
- **`AppStringsParityTest`** — using Kotlin reflection over `AppStrings::class`
  declared members, assert every `val` (String-returning, no-arg) is non-blank for all three
  objects, catching any missed translation. (Parameterized funs covered by a few spot checks.)
- **`ReceiveReportTest`** — updated for `DayKind`/`date` (see above); existing grouping/window
  assertions preserved.
- Existing tests (`DockReceivingViewModelTest`, etc.) unaffected — they don't read UI strings.

## Files Touched

**New (6):** `ui/i18n/AppLanguage.kt`, `AppStrings.kt`, `EnglishStrings.kt`,
`ChineseStrings.kt`, `SpanishStrings.kt`, `LocalAppStrings.kt`
**New tests (2):** `AppLanguageTest.kt`, `AppStringsParityTest.kt`

**Modified:**
- `data/prefs/UserPreferences.kt` (+ `appLanguage` / `setAppLanguage`)
- `MainActivity.kt` (inject prefs, provide `LocalAppStrings`)
- `ui/login/LoginViewModel.kt` (`selectLanguage` + current-language StateFlow)
- `ui/login/LoginScreen.kt` (selector UI + use `LocalAppStrings`)
- `ui/home/HomeScreen.kt`, `ui/dockreceiving/DockReceivingScreen.kt`,
  `ui/batchdetail/BatchDetailScreen.kt`, `ui/receivereport/ReceiveReportScreen.kt`,
  `ui/components/PdaTopBar.kt` (replace hardcoded strings with `LocalAppStrings.current`)
- `ui/dockreceiving/DockReceivingViewModel.kt` — **see note below on ViewModel-side strings**
- `ui/receivereport/ReceiveReport.kt` (`DayKind` refactor)

### ViewModel-produced strings

A few user-facing strings originate in ViewModels, not composables (`dock_selectWarehouseFirst`,
`dock_photoProcessingFailed`, `dock_batchClosed`). ViewModels must not hold Context or read a
CompositionLocal. Resolution: these become **state**, not pre-formatted strings — the
ViewModel emits a small sealed/enum message identifier (or the raw data), and the composable
maps it to text via `LocalAppStrings`. For `dock_batchClosed(number)` the VM already has the
batch number, so it emits a `DockMessage.BatchClosed(number)`-style marker and the screen
formats it. This keeps translation entirely in the Compose layer. (Server error messages
continue to flow through as raw strings, untranslated, per Non-Goals.)

## Open Decisions (resolved)

- Default language: **Chinese**.
- Server errors: **untranslated**.
- Carriers/Conditions/user input: **untranslated**.
- No Activity restart, no Locale override: **confirmed**.
