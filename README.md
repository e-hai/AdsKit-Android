# AdsKit-Android

Android 广告聚合 SDK：统一封装 AdMob / AppLovin MAX，宿主通过单一门面 `AdsManager` 接入。

## 架构

单适配器模式：同一时刻只有一个 `AdsProviderAdapter` 生效；切换 Provider 需重新 `initialize()`（先销毁旧适配器并清空预加载缓存）。Provider 经 **Java SPI** 按 classpath 发现。

### 模块依赖

```mermaid
flowchart TB
  subgraph Host["宿主 App"]
    Sample[":sample"]
  end

  subgraph Artifacts["发布产物"]
    Ads[":ads / 全量包"]
    AdMob[":ads-admob"]
    AppLovin[":ads-applovin"]
    Core[":ads-core<br/>AdsManager · UMP · API"]
  end

  subgraph SDKs["底层 SDK"]
    GMA["Google Mobile Ads"]
    MAX["AppLovin MAX"]
  end

  Sample -->|implementation| Ads
  Ads -->|api| AdMob
  Ads -->|api| AppLovin
  Ads -->|api| Core
  AdMob -->|api| Core
  AppLovin -->|api| Core
  AdMob --> GMA
  AppLovin --> MAX
```

### 运行时调用链

```mermaid
sequenceDiagram
  participant App as 宿主
  participant Mgr as AdsManager
  participant Factory as SPI Factory
  participant Adapter as AdsProviderAdapter
  participant Handler as AdsLoadHandler
  participant Entity as AdsEntity
  participant SDK as AdMob / MAX

  App->>Mgr: initialize(config)
  Mgr->>Factory: create(providerType)
  Factory-->>Mgr: adapter
  Mgr->>Adapter: initialize()
  Adapter->>SDK: SDK init
  Adapter-->>Mgr: onResult(success)
  Mgr-->>App: onResult(true) 主线程

  App->>Mgr: loadAd / preloadAd
  alt 预加载命中且未过期
    Mgr-->>App: onAdLoaded(cached)
  else 网络加载
    Mgr->>Handler: loadAd
    Handler->>Adapter: loadAd
    Adapter->>SDK: load
    SDK-->>Adapter: callback
    Adapter-->>Handler: AdsProviderListener
    Handler-->>App: AdsListener 主线程
  end

  App->>Entity: AdsEntity.show / destroy
  Entity->>Adapter: showAd / destroyAd
```

### 核心类关系

```mermaid
classDiagram
  class AdsManager {
    +initialize()
    +loadAd()
    +preloadAd()
    +destroy()
  }
  class AdsLoadHandler {
    +loadAd()
  }
  class AdsEntity {
    +show()
    +destroy()
  }
  class AdsProviderAdapter {
    <<interface>>
    +initialize()
    +loadAd()
    +showAd()
    +destroyAd()
  }
  class AdmobProviderAdapter
  class ApplovinProviderAdapter
  class AdsListener {
    <<interface>>
  }
  class AdCallback

  AdsManager --> AdsProviderAdapter : SPI 单实例
  AdsManager --> AdsLoadHandler : 网络加载
  AdsLoadHandler --> AdsProviderAdapter
  AdsLoadHandler --> AdsEntity : onAdLoaded
  AdsEntity --> AdsProviderAdapter : show / destroy
  AdmobProviderAdapter ..|> AdsProviderAdapter
  ApplovinProviderAdapter ..|> AdsProviderAdapter
  AdCallback ..|> AdsListener
  AdsLoadHandler ..> AdsListener : 主线程回调
```

## 接入与依赖 (GitHub Packages)

SDK 官方包统一发布于 **GitHub Packages** Maven 仓库（相比 JitPack 构建更加稳定、依赖下载迅速，直接由 GitHub Actions 官方流水线验证并发布）。

### 1. 配置仓库与认证凭据

> [!NOTE]
> GitHub Packages 要求必须通过认证（Personal Access Token）才能拉取包。

#### 步骤 1：准备 GitHub Token
前往 [GitHub Settings → Developer Settings → Personal Access Tokens](https://github.com/settings/tokens) 创建一个具有 **`read:packages`** 权限的 Token。

#### 步骤 2：配置本地凭据（全局或工程）
在用户家目录的 `~/.gradle/gradle.properties`（推荐，避免将 Token 意外提交至代码库）：
```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_PERSONAL_ACCESS_TOKEN
```
或在 CI / 部署环境中配置系统环境变量：
```bash
export GITHUB_ACTOR="YOUR_GITHUB_USERNAME"
export GITHUB_TOKEN="YOUR_GITHUB_PERSONAL_ACCESS_TOKEN"
```

#### 步骤 3：在工程中添加 Maven 仓库

**Kotlin DSL (`settings.gradle.kts`)**：
```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/e-hai/AdsKit-Android")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR") ?: ""
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}
```

**Groovy DSL (`settings.gradle`)**：
```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/e-hai/AdsKit-Android")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR") ?: ""
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}
```

### 2. 引入 SDK 依赖

根据所需广告平台按需引入：

| 产物 (Artifact) | 模块 | 说明 |
|---|---|---|
| `AdsKit` | `:ads` | **全量包**（包含 core + admob + applovin，支持运行时双平台动态切换） |
| `AdsKit-admob` | `:ads-admob` | **AdMob 瘦包**（仅集成 AdMob，传递依赖 core，大幅缩减包体积） |
| `AdsKit-applovin` | `:ads-applovin` | **AppLovin 瘦包**（仅集成 AppLovin MAX，传递依赖 core） |
| `AdsKit-core` | `:ads-core` | **核心包**（仅公共 API、`AdsManager`、UMP，适合自研适配器扩展） |

**Kotlin DSL (`app/build.gradle.kts`)**：
```kotlin
dependencies {
    // 方案 A：全量包（推荐，开箱即用）
    implementation("com.github.e-hai.AdsKit-Android:AdsKit:1.0.1")

    // 方案 B：AdMob 瘦包（仅包含 AdMob 及中介）
    // implementation("com.github.e-hai.AdsKit-Android:AdsKit-admob:1.0.1")

    // 方案 C：AppLovin 瘦包（仅包含 AppLovin MAX 及中介）
    // implementation("com.github.e-hai.AdsKit-Android:AdsKit-applovin:1.0.1")
}
```

> **💡 从 JitPack 迁移指南**：
> 1. 将 `settings.gradle.kts` 中的 `maven { url = uri("https://jitpack.io") }` 替换为上述带有鉴权的 GitHub Packages 仓库地址。
> 2. 依赖坐标格式完全统一，无需变更 group 与 artifact 命名规则。
> 3. 本地调试仍可直接通过模块引入：`implementation(project(":ads"))`。

### Sample 配置（`local.properties`）

```properties
admob.app.id=ca-app-pub-...
applovin.sdk.key=...
admob.test.device.id=...              # 可选，AdMob 测试设备哈希 ID
applovin.ad.unit.banner=...
applovin.ad.unit.rewarded=...
applovin.ad.unit.splash=...
applovin.ad.unit.interstitial=...
applovin.ad.unit.native=...
applovin.ad.unit.mrec=...
```

## 公开类型

| 类型 | 说明 |
|------|------|
| `AdsManager` | 单例门面；初始化状态机；预加载缓存（含 TTL） |
| `AdsInitState` | `IDLE` / `INITIALIZING` / `READY` / `FAILED` |
| `AdsEntity` | 已加载广告的展示句柄，`show()` / `destroy()` |
| `AdsListener` / `AdCallback` | 主线程回调 |
| `AdsPaidEvent` | 收益：`valueMicros` / `currencyCode` / `precision` |
| `AdsRequest` | `triggerId` / `adUnitId` / `adType` / `providerType` / `preloadTtlMs` |
| `AdsType` | 见下表 |
| `AdsProviderConfig` / `AdsProviderType` | Provider 配置 |
| `UMP` | Google UMP（`:ads-core`） |
| `AdsLogger` / `AdsDebug` | 日志开关；AdMob 测试设备 ID |

### AdsType

| 值 | 说明 | `container` |
|----|------|-------------|
| `BANNER` | 横幅 | **需要**，承载 Banner 视图 |
| `MREC` | 300×250 矩形 | **需要** |
| `NATIVE` | 原生广告 | **需要** |
| `SPLASH` | **App Open 开屏**（非普通 Splash 图） | 可传任意 `ViewGroup` |
| `INTERSTITIAL` | 插屏全屏 | 可传任意 `ViewGroup` |
| `REWARDED` | 激励视频 | 可传任意 `ViewGroup` |

> `SPLASH` 在底层对应 AdMob `AppOpenAd` / MAX `MaxAppOpenAd`。

## 核心 API

- **`initialize(app, config, onResult?)`** — 单适配器；同 Provider 跳过 → `true`；并发 → `false`；切换清预加载；`onResult` 主线程
- **`getInitState()` / `getInitializedProviderType()`**
- **`loadAd` / `preloadAd`** — 状态与 Provider 校验；预加载默认 TTL **55 分钟**（`AdsRequest.preloadTtlMs` 可覆盖）
- **`destroy()`** — 释放适配器并清空缓存
- **`openDebug(activity)`** — AdMob Ad Inspector / MAX Mediation Debugger

Provider 通过 **Java SPI** 注册；classpath 缺少对应模块时 `initialize()` 返回 `false`。

### 推荐流程

```
AdMob:     initialize → UMP.start() → loadAd
AppLovin:  UMP.start() → initialize → loadAd
```

### 回调

```
onAdStartedToLoad()
onAdLoaded(ad)
onAdFailedToLoad(error) / onAdFailedToLoad(error, errorCode?)
onAdShown() / onAdClicked() / onAdClosed()
onAdPaidEvent() / onAdPaidEvent(paid: AdsPaidEvent)
onAdUserEarnedReward()
onAdFailedToShow(error, errorCode?)
```

### 错误码

| 错误码 | 说明 |
|--------|------|
| `STATE_INITIALIZING` / `STATE_IDLE` / `STATE_FAILED` | 初始化状态不符 |
| `PROVIDER_MISMATCH` | Request 与已初始化 Provider 不一致 |
| `DISPLAY_*` | AppLovin 展示阶段错误 |
| SDK 原生码 | AdMob / AppLovin 透传 |

### 日志

- 过滤 Logcat **`AdsKit`** 可看到全部 SDK 日志（子 TAG 均含此前缀）
- 常用子 TAG：`AdsKit-Admob`、`AdsKit-AppLovin`、`AdsKit-AdsLoadHandler`、`AdsKit-UMP`、`AdsKit-Sample`
- 格式统一为 `key=value`，例如：
  - 核心：`onAdLoaded id=... unit=... type=... provider=...`
  - 适配器：`loadAd` / `onAdShown` / `onAdFailedToLoad` / `showAd` / `destroyAd`
  - 生命周期：`initialize start/complete`、`destroy`、`clearPreloadedAds`、`loadAd rejected`
- 失败用 `AdsLogger.e` / `w`；Release 默认静默（`AdsLogger.enabled` 跟随 `BuildConfig.DEBUG`）

```kotlin
// 可选：AdMob 测试设备（Sample 从 local.properties 注入）
AdsDebug.admobTestDeviceIds = listOf("YOUR_HASHED_DEVICE_ID")
```

## 使用示例

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AdsManager.initialize(
            this,
            AdsProviderConfig(AdsProviderType.ADMOB, "ca-app-pub-3940256099942544~3347511713"),
        ) { success ->
            Log.d("App", "init=$success state=${AdsManager.getInitState()}")
        }
    }
}

// UMP → 加载 Banner
UMP.start(activity) { _ ->
    val request = AdsRequest(
        triggerId = "home_banner",
        adUnitId = "ca-app-pub-3940256099942544/9214589741",
        adType = AdsType.BANNER,
        providerType = AdsProviderType.ADMOB,
    )
    AdsManager.loadAd(activity, request, object : AdCallback() {
        override fun onAdLoaded(ad: AdsEntity) {
            ad.show(activity, bannerContainer)
        }
        override fun onAdFailedToLoad(error: String, errorCode: String?) {
            Log.e("Ads", "load failed: $error ($errorCode)")
        }
        override fun onAdPaidEvent(paid: AdsPaidEvent) {
            Log.d("Ads", "revenue micros=${paid.valueMicros} ${paid.currencyCode}")
        }
        override fun onAdFailedToShow(error: String, errorCode: String?) {
            Log.e("Ads", "show failed: $error ($errorCode)")
        }
    })
}

// onDestroy：Banner / Native / MREC 需 destroy
override fun onDestroy() {
    adEntity?.destroy()
    super.onDestroy()
}
```

## 构建与发布

### 本地编译与单测
```bash
./gradlew :ads-core:testDebugUnitTest
./gradlew :sample:assembleDebug
```

### 本地发布与体积校验
```bash
./gradlew publishToMavenLocal -Pgroup=com.github.ci -Pversion=test
./scripts/check-artifact-sizes.sh com.github.ci test  # AAR/APK 体积门禁校验
```

### 发布到 GitHub Packages
1. **自动发布**：打 Tag（如 `1.0.1` 或 `v1.0.1`）推送到远端，触发 `.github/workflows/publish.yml` 自动编译、测试并发布至 GitHub Packages。
2. **手动触发**：在 GitHub Actions 页面选择 `Publish to GitHub Packages` workflow，输入版本号即可手动触发。
3. **本地发布到 GitHub Packages**：
   ```bash
   ./gradlew publishReleasePublicationToGitHubPackagesRepository -Pgpr.user=e-hai -Pgpr.key=YOUR_GITHUB_TOKEN
   ```

- 版本号：统一维护在 `gradle/libs.versions.toml` → `sdkVersion`
- CI：`.github/workflows/build.yml`（PR / 主分支构建与体积校验）
- CD：`.github/workflows/publish.yml`（GitHub Packages 自动发布）

更多实现细节、SPI、ProGuard、命名约定见 **`AGENTS.md`**。
