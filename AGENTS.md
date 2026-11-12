# AdsKit-Android – AGENTS.md

This document is specifically crafted for AI coding agents (Claude, GPT, Codex, etc.) to quickly understand the codebase architecture, design invariants, memory & lifecycle models, and guidelines for making safe and idiomatic changes in the `AdsKit-Android` repository.

---

## 1. What This Repository Is

`AdsKit-Android` is an Android ad mediation SDK written in **Kotlin** wrapping **Google Mobile Ads (AdMob)** and **AppLovin MAX** under a unified `AdsManager` facade.

### Key Architectural Invariants
- **Single-Adapter Mode**: `AdsManager` activates exactly one `AdsProviderAdapter` at any given time.
- **Provider Switching**: Calling `AdsManager.initialize(...)` with a different provider automatically tears down the active adapter (`destroy()`), cancels active loads, and evicts all preloaded ads from cache.
- **Java SPI Provider Discovery**: Concrete adapters implement `AdsProviderAdapterRegistrar` and register via `META-INF/services/com.kit.ads.provider.AdsProviderAdapterRegistrar`. At runtime, `AdsProviderAdapterFactory` uses `ServiceLoader` to discover providers present on the classpath.
- **Main Thread Dispatch**: All public API callbacks (`AdsListener`, `AdsManager.initialize(onResult)`) are strictly dispatched to Android's **Main Thread** via `AdsLoadHandler` and Android `Handler(Looper.getMainLooper())`.
- **GDPR Startup Order**: Both documentation and `:sample` UI follow `UMP.start(...)` → `AdsManager.initialize(...)` → `loadAd(...)` so third-party mediation adapters are not initialized prior to user consent.
- **Zero Cross-Platform Leaks**: This repository is completely self-contained and must not include any iOS-specific dependencies, configurations, or references.

---

## 2. Project Layout

```
AdsKit-Android/
├── ads-core/                     # Core SDK (namespace: com.kit.ads) — AdsManager, UMP, contracts
│   ├── build.gradle.kts          # Publishes artifact `AdsKit-core`
│   └── src/
│       ├── main/java/com/kit/ads/
│       │   ├── AdsManager.kt              # [Entry Point] Singleton facade with state machine & preload cache
│       │   ├── AdsInitState.kt            # IDLE, INITIALIZING, READY, FAILED
│       │   ├── AdsRequest.kt              # Data class: triggerId, adUnitId, adType, providerType, preloadTtlMs
│       │   ├── AdsType.kt                 # BANNER, SPLASH, REWARDED, INTERSTITIAL, NATIVE, MREC
│       │   ├── AdsEntity.kt               # Loaded ad wrapper with show(activity, container) and destroy()
│       │   ├── AdsListener.kt             # Callback interface + AdCallback abstract base class
│       │   ├── AdsPaidEvent.kt            # Revenue event data class (valueMicros, currencyCode, precision)
│       │   ├── AdsLogger.kt               # Internal (@RestrictTo LIBRARY_GROUP) tagged logger
│       │   ├── AdsDebug.kt                # Internal (@RestrictTo LIBRARY_GROUP) debug flag
│       │   ├── AdsLoadHandler.kt          # Load execution & main-thread callback dispatcher
│       │   ├── ump/
│       │   │   └── UMP.kt                 # Google UMP consent flow wrapper
│       │   └── provider/
│       │       ├── AdsProviderAdapter.kt          # [Interface] Core adapter contract
│       │       ├── AdsProviderAdapterFactory.kt   # Java SPI factory
│       │       ├── AdsProviderAdapterRegistrar.kt # SPI registration interface
│       │       ├── AdsProviderConfig.kt           # Config data class (providerType, apiKey)
│       │       ├── AdsProviderType.kt             # ADMOB / APPLOVIN enum
│       │       ├── AdsProviderListener.kt         # Internal adapter callback interface
│       │       └── ProviderNotAvailableException.kt
│       └── test/java/com/kit/ads/                 # Robolectric unit tests
├── ads-admob/                    # AdMob adapter (namespace: com.kit.ads.provider.admob)
│   ├── build.gradle.kts          # Publishes artifact `AdsKit-admob`
│   └── src/main/
│       ├── java/com/kit/ads/provider/admob/
│       │   ├── AdmobProviderAdapter.kt
│       │   └── AdmobProviderAdapterRegistrar.kt
│       └── resources/META-INF/services/com.kit.ads.provider.AdsProviderAdapterRegistrar
├── ads-applovin/                 # AppLovin MAX adapter (namespace: com.kit.ads.provider.applovin)
│   ├── build.gradle.kts          # Publishes artifact `AdsKit-applovin`
│   └── src/main/
│       ├── java/com/kit/ads/provider/applovin/
│       │   ├── ApplovinProviderAdapter.kt
│       │   └── ApplovinProviderAdapterRegistrar.kt
│       └── resources/META-INF/services/com.kit.ads.provider.AdsProviderAdapterRegistrar
├── ads/                          # Full bundle aggregator (publishes artifact `AdsKit`)
│   └── build.gradle.kts          # api(:ads-core, :ads-admob, :ads-applovin)
├── sample/                       # Interactive Jetpack Compose sample app
│   ├── build.gradle.kts          # Reads test keys/units from local.properties or BuildConfig defaults
│   └── src/main/java/com/kit/ads/sample/
│       ├── App.kt                # Application subclass demonstrating SDK initialization
│       ├── MainActivity.kt       # Full 1:1 demo screen (Compose)
│       └── SampleConfig.kt       # Test ad unit IDs and provider config constants
├── .github/workflows/
│   ├── build.yml                 # CI verification: unit tests, sample build, AAR size limits
│   └── publish.yml               # CD automated release to GitHub Packages
├── gradle/
│   ├── libs.versions.toml        # Version catalog (AGP, Kotlin, SDK versions, dependencies)
│   └── artifact-size-limits.properties # CI AAR / APK size limits
├── scripts/
│   └── check-artifact-sizes.sh   # Validates build artifact sizes against CI gates
├── build.gradle.kts              # Root build file (plugin declarations only)
├── settings.gradle.kts           # Module inclusion (:ads-core, :ads-admob, :ads-applovin, :ads, :sample)
├── gradle.properties
├── jitpack.yml                   # Legacy JitPack build definition (JDK 17)
├── README.md                     # Chinese developer documentation
└── AGENTS.md                     # This agent guide
```

---

## 3. Core Subsystems

### A. State Machine (`AdsManager`)
`AdsManager` enforces 4 mutually exclusive states:
1. `IDLE` (Initial state, or after `destroy()`)
2. `INITIALIZING` (Concurrent calls ignored while initializing; stale callbacks guarded via `initToken`)
3. `READY` (Adapter initialized, ads can be requested)
4. `FAILED` (Adapter creation failed or underlying ad network SDK initialization failed)

### B. Preload & Cache TTL
- Requests specify a unique `triggerId` string (used as the cache key).
- Preloaded ads are cached by `triggerId` with a timestamp and TTL (default: `DEFAULT_PRELOAD_TTL_MS` = 55 minutes, or custom `AdsRequest.preloadTtlMs`).
- `loadAd()` checks the cache first. If a matching unexpired `AdsEntity` is found, it is consumed immediately via `onAdLoaded()` without a network call.
- Cache is **single-use**: once consumed, it is removed from the cache.
- Expired entities are automatically destroyed (`destroy()`).
- Switching providers or calling `AdsManager.destroy()` purges and destroys all preloaded ads.

### C. Unified Show API (`AdsEntity.show(activity, container)`)
- **Full-Screen Ads** (`SPLASH` / App Open, `INTERSTITIAL`, `REWARDED`): Displayed modally using the `Activity`. The `container` parameter is required by the method signature but ignored by the underlying adapter.
- **Container Ads** (`BANNER`, `MREC`, `NATIVE`): Added as views into the provided `ViewGroup` container. Old views are removed and proper layout params are attached.

### D. Java SPI Architecture
- Adapters are decoupled from `AdsManager` using standard Java SPI (`ServiceLoader`).
- Modules on the classpath self-register through `META-INF/services/com.kit.ads.provider.AdsProviderAdapterRegistrar`.
- Host apps can import only `AdsKit-admob` or only `AdsKit-applovin` to drastically reduce app size. If a requested provider module is missing at runtime, `initialize()` safely fails with `onResult(false)` and logs an informative message.

---

## 4. Threading, Lifecycle & Memory Invariants

1. **Main-Thread Callback Dispatch**:
   - Ad network SDK callbacks may originate from background binder threads.
   - `AdsLoadHandler` wraps all client callbacks and posts them to `Looper.getMainLooper()`.
   - Never dispatch public listener events directly from a background thread.
2. **View Cleanup on `destroy()`**:
   - For `BANNER`, `MREC`, and `NATIVE`, calling `AdsEntity.destroy()` is **mandatory** to destroy WebViews, remove parent references, and prevent activity context leaks.
   - Host apps should call `entity.destroy()` in `onDestroy()` or when navigating away.
3. **No Memory Leaks in Preload Cache**:
   - Cached `AdsEntity` holds a reference to the ad instance. When replaced, expired, or purged via `AdsManager.destroy()`, `entity.destroy()` must be invoked immediately to free native memory.

---

## 5. Ad Format & Test Unit ID Reference

Official Google AdMob test IDs for Android (safe for development and verification):

| Format | `AdsType` | AdMob Test Unit ID | Notes |
|---|---|---|---|
| **Banner** | `BANNER` | `ca-app-pub-3940256099942544/9214589741` | Adaptive or standard 320x50 |
| **App Open** | `SPLASH` | `ca-app-pub-3940256099942544/9257395921` | Full-screen app open splash |
| **Rewarded** | `REWARDED` | `ca-app-pub-3940256099942544/5224354917` | Video reward ad |
| **Interstitial** | `INTERSTITIAL` | `ca-app-pub-3940256099942544/1033173712` | Full-screen interstitial |
| **Native** | `NATIVE` | `ca-app-pub-3940256099942544/2247696110` | Custom native ad layout |
| **MREC** | `MREC` | `ca-app-pub-3940256099942544/9214589741` | 300x250 medium rectangle |

> **AdMob App ID**: `ca-app-pub-3940256099942544~3347511713` (Must match `<meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" ... />` in `AndroidManifest.xml`).

---

## 6. Standardized Error Codes & Logging Conventions

### Error Codes

| Code | Meaning |
|---|---|
| `STATE_INITIALIZING` | `loadAd()` or `preloadAd()` invoked while `AdsManager` is still initializing |
| `STATE_IDLE` | Invoked before `AdsManager.initialize()` was called |
| `STATE_FAILED` | Invoked after SDK initialization failed |
| `PROVIDER_MISMATCH` | `AdsRequest.providerType` differs from the initialized provider |
| `DISPLAY_*` | AppLovin show-phase display failures (e.g. `DISPLAY_AD_ALREADY_SHOWN`) |
| Platform codes | Raw error code strings passed through from AdMob or AppLovin SDKs |

### Tagged Structured Logging & Debug Switches

`AdsLogger` and `AdsDebug` are internal to the SDK (`@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)`) and **must not be referenced by host apps**. Both default to **`false`** and are configured via `AdsManager.initialize(..., logEnabled = false, debugEnabled = false, ...)`. `UMP.start(...)` uses an independent local `debugEnabled` parameter and emits no internal SDK logs:

| Parameter / API | Default | Scope & Side Effects |
|---|---|---|
| `AdsManager.initialize(..., logEnabled)` | `false` | Sets internal `AdsLogger.enabled`. Controls `AdsKit-*` Logcat output only; no behavioral side effects. |
| `AdsManager.initialize(..., logListener)` / `setLogListener(...)` | `null` | External `AdsLogListener` interceptor (`AdsLogLevel`, `tag`, `message`, `throwable`). When non-null, intercepts all SDK logs and suppresses default Logcat output. |
| `AdsManager.initialize(..., debugEnabled)` | `false` | Sets internal `AdsDebug.isEnabled`. **Test-mode toggle (has side effects)**: replaces AdMob `request.adUnitId` with official test unit IDs and enables AppLovin `setVerboseLogging(true)`. |
| `UMP.start(activity, debugEnabled, umpTestDeviceIds, ...)` | `false`, `emptyList()` | Independent local UMP debug parameters (not tied to `AdsDebug`). When `debugEnabled == true`, simulates EEA geography and resets UMP consent. Recommended to run **before** `AdsManager.initialize(...)` for GDPR compliance. |

All logs use the `AdsKit-{Component}` convention via `AdsLogger`:

| TAG | Source | Key Event Strings |
|---|---|---|
| `AdsKit` | `AdsManager.kt` | `initialize start/complete`, `stateCheck`, `destroy`, `clearPreloadedAds` |
| `AdsKit-AdsLoadHandler` | `AdsLoadHandler.kt` | `loadAd start`, `onAdLoaded`, `onAdFailedToLoad` |
| `AdsKit-AdsEntity` | `AdsEntity.kt` | `show start/success`, `destroy` |
| `AdsKit-Admob` | `AdmobProviderAdapter.kt` | `loadAd`, `onAdLoaded`, `showAd` |
| `AdsKit-Applovin` | `ApplovinProviderAdapter.kt` | `loadAd`, `onAdLoaded`, `showAd` |
| `AdsKit-Sample` | Demo App | User interactions, event stream logging |

Logcat filter: Use `tag:AdsKit` to inspect all mediation operations.

---

## 7. Build Toolchain & Dependency Matrix

### Version Catalog (`gradle/libs.versions.toml`)

| Component | Target Version | Notes |
|---|---|---|
| Gradle | `9.6.0` | Requires UDP socket binding (see Environment Constraints) |
| AGP | `9.2.1` | **Built-in Kotlin** — do not apply `org.jetbrains.kotlin.android` |
| Kotlin | `2.4.0` | Applied via AGP; Compose compiler via `org.jetbrains.kotlin.plugin.compose` |
| JDK | `17` | Minimum compiler and runtime Java version |
| Compile / Target / Min SDK | `37` / `36` / `24` | Android 7.0 (Nougat) minimum |

### GitHub Packages Artifact Coordinates

The repository publishes to **GitHub Packages** (`https://maven.pkg.github.com/e-hai/AdsKit-Android`) under group `com.github.e-hai.AdsKit-Android`:

```kotlin
// Option 1: AdMob only (slim)
implementation("com.github.e-hai.AdsKit-Android:AdsKit-admob:<version>")

// Option 2: AppLovin only (slim)
implementation("com.github.e-hai.AdsKit-Android:AdsKit-applovin:<version>")

// Option 3: Full bundle (core + admob + applovin)
implementation("com.github.e-hai.AdsKit-Android:AdsKit:<version>")

// Core only (custom adapters)
implementation("com.github.e-hai.AdsKit-Android:AdsKit-core:<version>")
```

---

## 8. Verification & Publishing Commands

### Run Unit Tests
Unit tests use Robolectric and verify the init state machine, factory SPI discovery, and cache TTL behavior:
```bash
./gradlew :ads-core:testDebugUnitTest
```

### Build Sample App
```bash
./gradlew :sample:assembleDebug
```

### Validate Maven Local Publication & AAR Sizes
```bash
./gradlew clean publishToMavenLocal -Pgroup=com.github.ci -Pversion=test -x test -x lint
./scripts/check-artifact-sizes.sh com.github.ci test
```

### Publish to GitHub Packages
Automated via `.github/workflows/publish.yml` on tag pushes (`1.0.1`, `v1.0.1`) or manual workflow dispatch.
For manual command-line publishing:
```bash
./gradlew publishReleasePublicationToGitHubPackagesRepository -Pgpr.user=e-hai -Pgpr.key=$GITHUB_TOKEN -Pversion=<version>
```

---

## 9. Important Invariants & Rules for AI Agents

1. **Do NOT add `org.jetbrains.kotlin.android`**: AGP 9.x natively compiles Kotlin. Applying the external Kotlin Android plugin will cause a Gradle configuration failure.
2. **Do NOT use legacy AGP APIs**: `libraryVariants`, `applicationVariants`, and `android.kotlinOptions {}` are obsolete and removed. Use the `androidComponents` Variant API.
3. **Preserve SPI Registration**: Whenever a new adapter class is added or modified, ensure the corresponding SPI file in `resources/META-INF/services/com.kit.ads.provider.AdsProviderAdapterRegistrar` is preserved.
4. **Maintain Backward Compatibility**: Do not remove existing method overloads in `AdsListener` or `AdsEntity`. Always provide sensible defaults for new callback methods.
5. **Keep `maven-publish` on all SDK modules**: Both MavenLocal verification and GitHub Packages publishing rely on `maven-publish`.
6. **Preserve POM SCM / URL**: GitHub Packages relies on the POM metadata and SCM block (`https://github.com/e-hai/AdsKit-Android`) to associate published packages with the repository.
7. **No Cross-Platform Coupling**: Keep Android and iOS repositories 100% separate. Do not reference iOS types or files.
8. **Environment Constraints (Gradle UDP Socket)**:
   Gradle 9 requires UDP socket binding for cross-process file lock contention (`DefaultFileLockCommunicator`). In strictly sandboxed environments where UDP socket binding is prohibited, Gradle will fail to start. In such cases, use an unsandboxed/escalated shell or run tests in CI.
