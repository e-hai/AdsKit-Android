package com.kit.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import com.kit.ads.provider.AdsProviderAdapter
import com.kit.ads.provider.AdsProviderAdapterFactory
import com.kit.ads.provider.AdsProviderConfig
import com.kit.ads.provider.AdsProviderType

/**
 * AdsKit 单例门面。
 *
 * **线程约定**：所有公开 API 必须在主线程调用。Provider 异步回调中的状态更新也会切回主线程。
 */
object AdsManager {
    const val TAG = "AdsKit"

    /** 默认预加载 TTL：55 分钟（略短于常见 App Open / Rewarded 约 1 小时有效期） */
    const val DEFAULT_PRELOAD_TTL_MS: Long = 55L * 60L * 1000L

    @VisibleForTesting
    internal var testAdapterFactory: ((AdsProviderType) -> AdsProviderAdapter)? = null

    @VisibleForTesting
    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    // 预加载广告缓存，按 triggerId 索引，消费后立即移除（仅主线程访问）
    private val preloadedAds = mutableMapOf<String, PreloadedEntry>()

    private data class PreloadedEntry(
        val ad: AdsEntity,
        val cachedAtMs: Long,
        val ttlMs: Long,
    ) {
        fun isExpired(nowMs: Long): Boolean =
            ttlMs > 0L && nowMs - cachedAtMs >= ttlMs
    }

    private enum class InitState {
        IDLE,          // 未初始化
        INITIALIZING,  // 初始化中
        READY,         // 初始化成功
        FAILED         // 初始化失败
    }

    private var initState = InitState.IDLE
    private var initToken = 0
    private var adsProviderAdapter: AdsProviderAdapter? = null
    private var initializedProviderType: AdsProviderType? = null


    /**
     * 当前初始化状态（只读）。
     *
     * Must be called on the main thread.
     */
    @MainThread
    fun getInitState(): AdsInitState = when (initState) {
        InitState.IDLE -> AdsInitState.IDLE
        InitState.INITIALIZING -> AdsInitState.INITIALIZING
        InitState.READY -> AdsInitState.READY
        InitState.FAILED -> AdsInitState.FAILED
    }

    /**
     * 当前已初始化成功的 Provider；未 READY 时为 null。
     *
     * Must be called on the main thread.
     */
    @MainThread
    fun getInitializedProviderType(): AdsProviderType? = initializedProviderType

    private fun resolveTtlMs(request: AdsRequest): Long =
        request.preloadTtlMs ?: DEFAULT_PRELOAD_TTL_MS

    /**
     * 设置或清除外部日志拦截器。
     *
     * 非空时 SDK 内部日志将不再输出到默认 Logcat，而是统一回调给 [listener]；
     * 传 `null` 可恢复为受 `logEnabled` 控制的默认 Logcat 输出。
     */
    fun setLogListener(listener: AdsLogListener?) {
        AdsLogger.logListener = listener
    }

    /**
     * 初始化广告提供商适配器。
     *
     * Must be called on the main thread. [onResult] is always invoked on the main thread.
     *
     * @param context 应用的上下文
     * @param config 广告提供商的配置
     * @param logEnabled 是否开启 SDK 内部默认 Logcat 日志（默认 false；当配置了 [logListener] 时由拦截器接管）
     * @param debugEnabled 是否开启调试模式（默认 false，仅限测试环境使用）
     * @param logListener 可选外部日志拦截器（非空时覆盖注入，传 null 保留此前通过 [setLogListener] 设置的拦截器）
     * @param onResult 初始化完成回调
     */
    @JvmOverloads
    @MainThread
    fun initialize(
        context: Application,
        config: AdsProviderConfig,
        logEnabled: Boolean = false,
        debugEnabled: Boolean = false,
        logListener: AdsLogListener? = null,
        onResult: ((success: Boolean) -> Unit)? = null,
    ) {
        AdsLogger.enabled = logEnabled
        AdsDebug.isEnabled = debugEnabled
        if (logListener != null) {
            AdsLogger.logListener = logListener
        }

        val providerType = config.providerType
        if (testAdapterFactory == null && !AdsProviderAdapterFactory.isAvailable(providerType)) {
            AdsLogger.e(
                TAG,
                "initialize failed provider=$providerType error=Provider not on classpath. Add AdsKit-admob or AdsKit-applovin.",
            )
            initState = InitState.FAILED
            onResult?.invoke(false)
            return
        }

        val adapter = testAdapterFactory?.invoke(providerType)
            ?: AdsProviderAdapterFactory.create(providerType)

        when (initState) {
            InitState.INITIALIZING -> {
                AdsLogger.w(
                    TAG,
                    "Ignoring initialize($providerType), another initialize is in progress.",
                )
                onResult?.invoke(false)
                return
            }

            InitState.READY -> {
                val currentType = initializedProviderType
                if (currentType == providerType) {
                    AdsLogger.w(
                        TAG,
                        "AdsManager already initialized with $providerType. Skip duplicate initialize.",
                    )
                    onResult?.invoke(true)
                    return
                }
                AdsLogger.w(
                    TAG,
                    "Reinitializing from $currentType to $providerType. Releasing old adapter.",
                )
            }

            InitState.IDLE, InitState.FAILED -> Unit
        }

        val oldAdapter = adsProviderAdapter
        adsProviderAdapter = null
        initializedProviderType = null
        initState = InitState.INITIALIZING
        initToken++
        val token = initToken

        oldAdapter?.destroy()
        // Provider 切换 / 重新初始化时丢弃旧缓存，避免返回绑定到旧 adapter 的 AdsEntity
        clearPreloadedAds()

        AdsLogger.d(TAG, "initialize start provider=$providerType token=$token")
        adapter.initialize(context, config) { success ->

            if (token != initToken) {
                AdsLogger.w(
                    TAG,
                    "initialize staleCallback provider=$providerType token=$token currentToken=$initToken",
                )
                adapter.destroy()
                return@initialize
            }
            initState = if (success) InitState.READY else InitState.FAILED
            if (success) {
                adsProviderAdapter = adapter
                initializedProviderType = providerType
                AdsLogger.d(
                    TAG,
                    "initialize complete provider=$providerType success=true state=READY"
                )
            } else {
                adsProviderAdapter = null
                initializedProviderType = null
                AdsLogger.e(
                    TAG,
                    "initialize complete provider=$providerType success=false state=FAILED"
                )
            }
            onResult?.invoke(success)
        }
    }

    /**
     * 销毁当前适配器，释放所有资源。
     *
     * Must be called on the main thread.
     * 调用后 AdsManager 回到未初始化状态，可重新 initialize。
     */
    @MainThread
    fun destroy() {
        val previousProvider = initializedProviderType
        val toDestroy = adsProviderAdapter
        initState = InitState.IDLE
        initToken++
        adsProviderAdapter = null
        initializedProviderType = null
        AdsLogger.d(TAG, "destroy previousProvider=$previousProvider")
        toDestroy?.destroy()
        clearPreloadedAds()
    }

    private fun clearPreloadedAds() {
        val entries = preloadedAds.values.toList()
        if (entries.isEmpty()) {
            return
        }
        preloadedAds.clear()
        AdsLogger.d(TAG, "clearPreloadedAds count=${entries.size}")
        entries.forEach { entry ->
            try {
                entry.ad.destroy()
            } catch (_: Exception) {
                // best-effort cleanup
            }
        }
    }

    @VisibleForTesting
    @MainThread
    internal fun resetForTest() {
        destroy()
        testAdapterFactory = null
        clockMs = { System.currentTimeMillis() }
    }

    /**
     * 开启调试模式，显示广告 SDK 的调试信息。
     *
     * Must be called on the main thread.
     *
     * @param activity 当前 Activity
     */
    @MainThread
    fun openDebug(activity: Activity) {
        val adapter = adsProviderAdapter
        if (adapter != null) {
            adapter.openDebug(activity)
        } else {
            AdsLogger.e(TAG, "openDebug rejected error=No adapter initialized")
        }
    }

    /**
     * 预加载广告（静默缓存，无公开回调）。
     *
     * Must be called on the main thread.
     */
    @MainThread
    fun preloadAd(context: Context, request: AdsRequest) {
        AdsLogger.d(
            TAG,
            "preloadAd triggerId=${request.triggerId} type=${request.adType} provider=${request.providerType}",
        )
        val ttlMs = resolveTtlMs(request)
        val internalListener = object : AdCallback() {
            override fun onAdLoaded(ad: AdsEntity) {
                preloadedAds[request.triggerId] = PreloadedEntry(
                    ad = ad,
                    cachedAtMs = clockMs(),
                    ttlMs = ttlMs,
                )
                AdsLogger.d(
                    TAG,
                    "preloadAd cached triggerId=${request.triggerId} ttlMs=$ttlMs",
                )
            }

            override fun onAdFailedToLoad(error: String, errorCode: String?) {
                AdsLogger.e(
                    TAG,
                    "preloadAd failed triggerId=${request.triggerId} error=$error errorCode=$errorCode",
                )
            }
        }
        executeAdsRequest(context, request, internalListener)
    }

    /**
     * 加载广告。
     *
     * Must be called on the main thread. Listener callbacks are dispatched on the main thread.
     */
    @MainThread
    fun loadAd(context: Context, request: AdsRequest, adListener: AdsListener) {
        executeAdsRequest(context, request, adListener)
    }

    /**
     * 构建并执行广告加载。
     *
     * @param context 上下文
     * @param request 广告触发点请求
     * @param adListener 广告回调监听器
     */
    @MainThread
    private fun executeAdsRequest(
        context: Context,
        request: AdsRequest,
        adListener: AdsListener,
    ) {
        // 检查预加载缓存（过期则丢弃并走网络）
        val cachedEntry = preloadedAds.remove(request.triggerId)
        if (cachedEntry != null) {
            val now = clockMs()
            if (cachedEntry.isExpired(now)) {
                AdsLogger.d(
                    TAG,
                    "loadAd cache expired triggerId=${request.triggerId} ageMs=${now - cachedEntry.cachedAtMs} ttlMs=${cachedEntry.ttlMs}",
                )
                cachedEntry.ad.destroy()
            } else {
                AdsLogger.d(
                    TAG,
                    "loadAd from cache triggerId=${request.triggerId} type=${request.adType} provider=${request.providerType}",
                )
                adListener.onAdLoaded(cachedEntry.ad)
                return
            }
        }

        val adapter = adsProviderAdapter
        val currentProviderType = initializedProviderType
        val state = initState

        AdsLogger.d(
            TAG,
            "loadAd triggerId=${request.triggerId} type=${request.adType} provider=${request.providerType}",
        )
        AdsLogger.d(TAG, "loadAd stateCheck state=$state adapter=${adapter != null}")

        if (state != InitState.READY || adapter == null) {
            val errorMsg = when (state) {
                InitState.INITIALIZING -> "AdsManager initializing. Wait for initialize callback then retry."
                else -> "AdsManager not initialized. No adapter found."
            }
            val code = "STATE_$state"
            AdsLogger.w(
                TAG,
                "loadAd rejected triggerId=${request.triggerId} type=${request.adType} errorCode=$code",
            )
            adListener.onAdFailedToLoad(errorMsg, code)
            return
        }

        if (currentProviderType != request.providerType) {
            val errorMsg = "Provider mismatch. Manager initialized with $currentProviderType, " +
                    "request requests ${request.providerType}."
            AdsLogger.w(
                TAG,
                "loadAd rejected triggerId=${request.triggerId} errorCode=PROVIDER_MISMATCH init=$currentProviderType request=${request.providerType}",
            )
            adListener.onAdFailedToLoad(errorMsg, "PROVIDER_MISMATCH")
        } else {
            AdsLogger.d(
                TAG,
                "loadAd network triggerId=${request.triggerId} type=${request.adType} provider=${request.providerType}",
            )
            AdsLoadHandler(request, adapter)
                .loadAd(context, adListener)
        }
    }
}
