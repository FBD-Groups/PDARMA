# CLAUDE.md — PDA Project

Guidance for Claude Code when working in this repository. Derived from the project's
Cursor rules (`.cursorrules`, `.cursor/rules/*.mdc`) and the conventions already present
in the code.

You are an expert senior Android developer for the **PDA** handheld-device app. Write
clean, robust Kotlin following current official Google Modern Android Development (MAD)
recommendations (as of 2026).

## What this app is

An Android app for PDA / handheld devices that talks to an RMA backend API. Current
scope: token-based auth (login). `minSdk = 26`, `targetSdk`/`compileSdk = 35`,
JVM target 17, application id `com.pda.app`.

## Build & run

```powershell
.\gradlew.bat assembleDebug        # build debug APK
.\gradlew.bat installDebug         # build + install on connected device/emulator
.\gradlew.bat test                 # unit tests
.\gradlew.bat lint                 # Android lint
```

- Backend base URL is injected via `BuildConfig.RMA_BASE_URL` per build type in
  `app/build.gradle.kts`:
  - **debug** → `https://fbd.shipswithus.com/fbd-rma-api/` (production; change to
    `http://10.0.2.2/` for emulator→host `localhost`, or the device's reachable host IP
    when running on a physical PDA against a local backend).
  - **release** → `https://fbd.shipswithus.com/fbd-rma-api/` (minified, ProGuard enabled).

## Core tech stack

Always use this stack. **NEVER** suggest legacy alternatives unless explicitly asked:

- **Language**: Kotlin 2.0 (K2 compiler)
- **UI**: Jetpack Compose only — **NO XML / Views**
- **Architecture**: MVVM / MVI with Unidirectional Data Flow (UDF)
- **DI**: Hilt (dagger-hilt) — KSP, not kapt
- **Async**: Coroutines & Flow (`StateFlow`, `SharedFlow`) — **NO RxJava, NO LiveData**
- **Network**: Retrofit + OkHttp
- **JSON**: Kotlinx Serialization — **NO Gson, NO Moshi**
- **Persistence**: DataStore Preferences (token storage)
- **Build**: Gradle Kotlin DSL with the version catalog (`gradle/libs.versions.toml`)

When adding a dependency, declare it in `gradle/libs.versions.toml` and reference it as
`libs.*` in `app/build.gradle.kts`. Do not hard-code versions in the build file.

## Project layout

Source lives under `app/src/main/kotlin` (not `.../java`), package root `com.pda.app`,
organized **feature-by-package**:

```
com/pda/app/
  PdaApplication.kt        # @HiltAndroidApp Application
  MainActivity.kt          # @AndroidEntryPoint single activity, Compose host
  di/                      # Global Hilt modules (e.g. NetworkModule)
  data/
    NetworkResult.kt       # sealed result wrapper
    api/                   # Retrofit *ApiService interfaces
    api/model/             # @Serializable DTOs (request/response)
    repository/            # Repositories (Hilt-injected, expose Flow)
  ui/
    <feature>/             # e.g. ui/login/ — Screen + ViewModel + UiState together
    theme/                 # Compose theme
```

> Note: a legacy `com/example/fbdrma` tree still exists under `app/src/main/java` and the
> test source sets. New code goes under `com.pda.app` in the `kotlin` source set; treat the
> old package as deprecated and migrate/remove rather than extend it.

## Coding conventions (follow the existing code)

### Result wrapping & repositories
- Wrap all API calls in the `NetworkResult<T>` sealed class (`Success` / `Error(message, code?)`
  / `Loading`) — see [NetworkResult.kt](app/src/main/kotlin/com/pda/app/data/NetworkResult.kt).
- Repositories expose `Flow<NetworkResult<T>>` built with `flow { emit(...) }` and terminated
  with `.flowOn(Dispatchers.IO)`. Pattern reference:
  [AuthRepository.kt](app/src/main/kotlin/com/pda/app/data/repository/AuthRepository.kt) — emit
  `Loading`, call the API, branch on `response.isSuccessful`, parse the server `error` field from
  the error body, and map HTTP codes to messages. Always wrap in `try/catch` and emit
  `NetworkResult.Error` on exception.
- Map DTOs to clean domain models; never expose raw DTOs with redundant network fields to the UI.

### ViewModels
- `@HiltViewModel` + `@Inject constructor(...)`. **Never** hold `Context`, `Activity`,
  `Fragment`, or any View reference.
- Keep `MutableStateFlow` private; expose read-only `StateFlow` via `asStateFlow()`.
- Each feature defines its own UI-state sealed interface/class (e.g. `LoginUiState` with
  `Idle / Loading / Success / Error`).
- Consume repository flows with `.onEach { ... }.launchIn(viewModelScope)` (see
  [LoginViewModel.kt](app/src/main/kotlin/com/pda/app/ui/login/LoginViewModel.kt)); launch
  coroutines only in `viewModelScope`.

### Compose
- Small, reusable, **stateless** composables — state down, events up.
- Collect flows with `collectAsStateWithLifecycle()` (`lifecycle-runtime-compose`).
- Cache local UI state with `remember` / `rememberSaveable`.
- Obtain ViewModels via `hiltViewModel()`.

### Hilt / DI
- `@HiltAndroidApp` on the Application, `@AndroidEntryPoint` on the Activity.
- Provide network/data bindings in `@Module @InstallIn(SingletonComponent::class)` objects
  with `@Provides @Singleton` — see
  [NetworkModule.kt](app/src/main/kotlin/com/pda/app/di/NetworkModule.kt). The shared `Json`
  uses `ignoreUnknownKeys = true`, `isLenient = true`, `coerceInputValues = true`.

### Models
- DTOs are `@Serializable` (Kotlinx Serialization).

### Logging
- Use `android.util.Log` with a `TAG` constant in the class `companion object`, namespaced
  `"PDA/<ClassName>"` (e.g. `"PDA/AuthRepository"`). OkHttp logging uses tag `"PDA/OkHttp"`
  and is BODY-level only in debug builds.

### User-facing strings
- End-user messages are in **Chinese** (e.g. `"用户名或密码错误"`). Keep that convention for
  anything the user sees.

## Things to avoid

Deprecated / banned APIs: `LiveData`, `RxJava`, `AsyncTask`, `findViewById`,
`Activity.runOnUiThread`, XML layouts, Gson, Moshi, kapt. Add KDoc only where an
architectural choice is non-obvious — don't over-comment.
