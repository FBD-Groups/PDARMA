# FBDRMA (PDA)
android 下载试用链接
https://underwire-numeral-operative.ngrok-free.dev/pda/

Android 手持终端应用，对接 RMA 后端 API，用于仓库现场作业（收货、盘点等）。

## 功能

| 模块 | 状态 | 说明 |
|------|------|------|
| 登录 | ✅ | 调用 RMA `POST /api/auth/login`，JWT 鉴权 |
| 会话管理 | ✅ | DataStore 持久化 Token，OkHttp 自动附加 `Authorization` |
| 首页 | ✅ | 问候语、仓库切换、退出登录 |
| 仓库列表 | ✅ | 拉取仓库列表，DataStore 记住上次选择 |
| Dock Receive | ✅ | CameraX 拍照 → AI 识别运单/承运商 → 创建批次并逐件收货 |
| Receive Report | ✅ | 最近三天已收货批次（密集账本视图、按天分组），点批次下钻看单件明细（运单/承运商/需复核） |

## 技术栈

- **Kotlin 2.0** + K2 编译器
- **Jetpack Compose** — 声明式 UI
- **MVVM** — `StateFlow` + Unidirectional Data Flow
- **Hilt** — 依赖注入（KSP）
- **Retrofit + OkHttp** — 网络层
- **Kotlinx Serialization** — JSON 解析
- **DataStore Preferences** — Token / 用户偏好存储

`minSdk 26` · `targetSdk 35` · `compileSdk 35` · JVM 17

## 项目结构

```
app/src/main/kotlin/com/pda/app/
├── PdaApplication.kt          # @HiltAndroidApp
├── MainActivity.kt            # 单 Activity + Navigation Compose
├── di/
│   └── NetworkModule.kt       # Retrofit / OkHttp / Json
├── data/
│   ├── NetworkResult.kt
│   ├── api/                   # Retrofit 接口与 DTO（Auth / Warehouse / Receiving）
│   ├── repository/            # AuthRepository, WarehouseRepository, ReceivingRepository
│   ├── prefs/                 # UserPreferences (DataStore)
│   └── session/               # SessionManager
└── ui/
    ├── login/                 # LoginScreen + LoginViewModel
    ├── home/                  # HomeScreen + HomeViewModel（仓库切换、功能入口）
    ├── dockreceiving/         # DockReceivingScreen + ViewModel（拍照收货）
    ├── receivereport/         # ReceiveReportScreen + ViewModel（收货报表）
    ├── batchdetail/           # BatchDetailScreen + ViewModel（批次单件明细）
    └── theme/
```

## 环境要求

- Android Studio Ladybug (2024.2+) 或更新版本
- JDK 17
- Android SDK 35
- 可访问的 RMA 后端（IIS 部署或本地 `dotnet run`）

## 构建与运行

```powershell
# 克隆
git clone https://github.com/kyle66889/PDARMA.git
cd PDARMA

# 构建 Debug APK
.\gradlew.bat assembleDebug

# 安装到已连接设备 / 模拟器
.\gradlew.bat installDebug

# 单元测试
.\gradlew.bat test
```

在 Android Studio 中：**File → Open** 选择项目根目录，Sync Gradle 后点击 Run。

## API 地址配置

后端 Base URL 通过 `BuildConfig.RMA_BASE_URL` 注入，在 `app/build.gradle.kts` 中按构建类型区分：

| 构建类型 | 默认地址 | 说明 |
|----------|----------|------|
| **debug** | `http://10.0.2.2/` | 模拟器访问宿主机 `localhost` |
| **release** | `http://FBDDEV002/rma-api/` | 生产 IIS 子应用路径 |

**真机调试**：将 debug 的 URL 改为服务器局域网 IP，例如：

```kotlin
buildConfigField("String", "RMA_BASE_URL", "\"http://192.168.1.100/\"")
```

同时在 `app/src/main/res/xml/network_security_config.xml` 中允许对应域名的 HTTP 明文流量。

## 登录接口

对接 RMA 后端公开接口：

```
POST /api/auth/login
Content-Type: application/json

{ "username": "admin", "password": "admin123" }
```

成功响应：

```json
{
  "token": "<JWT>",
  "user": {
    "userId": "...",
    "username": "admin",
    "email": "...",
    "fullName": "...",
    "roles": ["Admin"]
  }
}
```

## 日志

Logcat 使用 `PDA` 前缀过滤：

| Tag | 内容 |
|-----|------|
| `PDA/OkHttp` | HTTP 请求/响应（仅 Debug） |
| `PDA/AuthRepository` | 登录流程 |
| `PDA/LoginViewModel` | 登录 UI 状态流转 |
| `PDA/HomeViewModel` | 首页仓库加载与切换 |
| `PDA/ReceivingRepository` | 收货批次 / 单件 / 报表数据 |
| `PDA/DockReceivingViewModel` | 拍照收货流程 |
| `PDA/ReceiveReportViewModel` | 收货报表加载 |
| `PDA/BatchDetailViewModel` | 批次明细加载 |

密码不会写入日志。

## 默认测试账号

RMA 开发环境种子用户（以实际后端配置为准）：

- 用户名：`admin`
- 密码：`admin123`

## 相关仓库

- **PDA 客户端（本仓库）**：[kyle66889/PDARMA](https://github.com/kyle66889/PDARMA)
- **RMA 后端 / Web**：同级目录 `RMA` 项目（ASP.NET Core + React）

## License

内部项目，暂未开源许可。
