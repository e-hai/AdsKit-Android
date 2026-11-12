package com.kit.ads.sample

import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kit.ads.AdCallback
import com.kit.ads.AdsEntity
import com.kit.ads.AdsManager
import com.kit.ads.AdsPaidEvent
import com.kit.ads.AdsRequest
import com.kit.ads.AdsType
import com.kit.ads.provider.AdsProviderType
import com.kit.ads.ump.UMP

class MainActivity : ComponentActivity() {

    private var bannerEntity: AdsEntity? = null

    private fun isContainerAd(type: AdsType): Boolean =
        type == AdsType.BANNER || type == AdsType.NATIVE || type == AdsType.MREC

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                SampleDemoScreen(
                    activity = this,
                    onLog = ::appendLog,
                    onLoadBanner = ::loadBanner,
                    onPreloadBanner = ::preloadBanner,
                    onShowCachedBanner = ::showCachedBanner,
                    onLoadSplash = ::loadSplash,
                    onLoadRewarded = ::loadRewarded,
                    onLoadInterstitial = ::loadInterstitial,
                    onLoadNative = ::loadNative,
                    onLoadMrec = ::loadMrec,
                    onOpenDebug = { AdsManager.openDebug(this) },
                    onInitialize = ::initializeProvider,
                    onDestroyManager = ::destroyManager,
                    onRequestUmp = ::requestUmp,
                )
            }
        }
    }

    override fun onDestroy() {
        bannerEntity?.destroy()
        bannerEntity = null
        super.onDestroy()
    }

    private fun appendLog(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d("AdsKit-Sample", message)
        }
    }

    private fun initializeProvider(provider: AdsProviderType, onResult: (Boolean) -> Unit) {
        AdsManager.initialize(
            context = application,
            config = SampleConfig.providerConfig(provider),
            logEnabled = BuildConfig.DEBUG,
            debugEnabled = BuildConfig.DEBUG,
        ) { success ->
            appendLog("initialize($provider) success=$success")
            onResult(success)
        }
    }

    private fun destroyManager(onDone: () -> Unit) {
        bannerEntity?.destroy()
        bannerEntity = null
        AdsManager.destroy()
        appendLog("AdsManager.destroy()")
        onDone()
    }

    private fun requestUmp(onResult: (Boolean) -> Unit) {
        val umpTestDeviceIds = BuildConfig.ADMOB_TEST_DEVICE_ID
            .takeIf { it.isNotEmpty() }
            ?.let(::listOf)
            ?: emptyList()
        UMP.start(
            activity = this,
            debugEnabled = BuildConfig.DEBUG,
            umpTestDeviceIds = umpTestDeviceIds,
        ) { gathered ->
            appendLog("UMP consent gathered=$gathered")
            onResult(gathered)
        }
    }

    private fun loadAd(
        request: AdsRequest,
        container: ViewGroup,
        onLoaded: (() -> Unit)? = null,
    ) {
        appendLog("loadAd triggerId=${request.triggerId} type=${request.adType} provider=${request.providerType}")
        AdsManager.loadAd(this, request, object : AdCallback() {
            override fun onAdStartedToLoad() {
                appendLog("onAdStartedToLoad ${request.adType}")
            }

            override fun onAdLoaded(ad: AdsEntity) {
                appendLog("onAdLoaded ${request.adType}")
                if (isContainerAd(request.adType)) {
                    bannerEntity?.destroy()
                    bannerEntity = ad
                }
                ad.show(this@MainActivity, container)
                onLoaded?.invoke()
            }

            override fun onAdFailedToLoad(error: String, errorCode: String?) {
                appendLog("onAdFailedToLoad ${request.adType}: $error (code=$errorCode)")
            }

            override fun onAdShown() {
                appendLog("onAdShown ${request.adType}")
            }

            override fun onAdClicked() {
                appendLog("onAdClicked ${request.adType}")
            }

            override fun onAdClosed() {
                appendLog("onAdClosed ${request.adType}")
            }

            override fun onAdUserEarnedReward() {
                appendLog("onAdUserEarnedReward")
            }

            override fun onAdPaidEvent(paid: AdsPaidEvent) {
                appendLog(
                    "onAdPaidEvent ${request.adType} micros=${paid.valueMicros} ${paid.currencyCode} precision=${paid.precision}",
                )
            }
        })
    }

    private fun loadBanner(provider: AdsProviderType, container: ViewGroup) {
        loadAd(SampleConfig.request(provider, AdsType.BANNER), container)
    }

    private fun loadSplash(provider: AdsProviderType, container: ViewGroup) {
        loadAd(SampleConfig.request(provider, AdsType.SPLASH), container)
    }

    private fun loadRewarded(provider: AdsProviderType, container: ViewGroup) {
        loadAd(SampleConfig.request(provider, AdsType.REWARDED), container)
    }

    private fun loadInterstitial(provider: AdsProviderType, container: ViewGroup) {
        loadAd(SampleConfig.request(provider, AdsType.INTERSTITIAL), container)
    }

    private fun loadNative(provider: AdsProviderType, container: ViewGroup) {
        loadAd(SampleConfig.request(provider, AdsType.NATIVE), container)
    }

    private fun loadMrec(provider: AdsProviderType, container: ViewGroup) {
        loadAd(SampleConfig.request(provider, AdsType.MREC), container)
    }

    private fun preloadBanner(provider: AdsProviderType) {
        val request = SampleConfig.preloadBannerRequest(provider)
        appendLog("preloadAd triggerId=${request.triggerId}")
        AdsManager.preloadAd(this, request)
    }

    private fun showCachedBanner(provider: AdsProviderType, container: ViewGroup) {
        loadAd(SampleConfig.preloadBannerRequest(provider), container) {
            appendLog("showCachedBanner: served from preload cache if available")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SampleDemoScreen(
    activity: MainActivity,
    onLog: (String) -> Unit,
    onLoadBanner: (AdsProviderType, ViewGroup) -> Unit,
    onPreloadBanner: (AdsProviderType) -> Unit,
    onShowCachedBanner: (AdsProviderType, ViewGroup) -> Unit,
    onLoadSplash: (AdsProviderType, ViewGroup) -> Unit,
    onLoadRewarded: (AdsProviderType, ViewGroup) -> Unit,
    onLoadInterstitial: (AdsProviderType, ViewGroup) -> Unit,
    onLoadNative: (AdsProviderType, ViewGroup) -> Unit,
    onLoadMrec: (AdsProviderType, ViewGroup) -> Unit,
    onOpenDebug: () -> Unit,
    onInitialize: (AdsProviderType, (Boolean) -> Unit) -> Unit,
    onDestroyManager: (() -> Unit) -> Unit,
    onRequestUmp: ((Boolean) -> Unit) -> Unit,
) {
    val bannerContainer = remember { FrameLayout(activity) }
    var selectedProvider by remember { mutableStateOf(AdsProviderType.ADMOB) }
    var initStatus by remember { mutableStateOf("未初始化") }
    var consentStatus by remember { mutableStateOf("未请求") }
    val eventLog = remember { mutableStateListOf<String>() }

    fun log(message: String) {
        eventLog.add(0, message)
        onLog(message)
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("AdsKit Sample", style = MaterialTheme.typography.headlineSmall)
            Text(
                "演示 UMP 隐私同意、SDK 初始化、预加载、多种广告类型，以及 Provider 切换。",
                style = MaterialTheme.typography.bodyMedium,
            )

            SectionTitle("1. 隐私同意（UMP）")
            Text("状态: $consentStatus", style = MaterialTheme.typography.bodySmall)
            ActionButton("请求 UMP 同意") {
                onRequestUmp { gathered ->
                    consentStatus = if (gathered) "已同意，可初始化广告" else "未同意或受限"
                    log("UMP 结果: $consentStatus")
                }
            }
            Text(
                "建议流程：UMP → 初始化 SDK → 加载广告（符合欧盟 GDPR 及三方中介隐私合规要求）。",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()

            SectionTitle("2. Provider 配置与初始化")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderChip("AdMob", selectedProvider == AdsProviderType.ADMOB) {
                    selectedProvider = AdsProviderType.ADMOB
                    log("选择 Provider: ADMOB")
                }
                ProviderChip("AppLovin", selectedProvider == AdsProviderType.APPLOVIN) {
                    selectedProvider = AdsProviderType.APPLOVIN
                    log("选择 Provider: APPLOVIN")
                }
            }
            Text("当前: $selectedProvider", style = MaterialTheme.typography.bodySmall)
            Text(
                "状态: $initStatus | SDK: ${AdsManager.getInitState()}" +
                    (AdsManager.getInitializedProviderType()?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("初始化 SDK", Modifier.weight(1f)) {
                    onInitialize(selectedProvider) { success ->
                        initStatus = if (success) "READY ($selectedProvider)" else "FAILED"
                        log("初始化结果: $initStatus")
                    }
                }
                ActionButton("销毁 SDK", Modifier.weight(1f)) {
                    onDestroyManager {
                        initStatus = "IDLE"
                        log("SDK 已销毁，状态 IDLE")
                    }
                }
            }

            if (selectedProvider == AdsProviderType.APPLOVIN && !SampleConfig.AppLovinUnit.hasRealUnits()) {
                Text(
                    "AppLovin 广告位未配置：在 local.properties 设置 applovin.ad.unit.* 后重新编译。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (BuildConfig.ADMOB_TEST_DEVICE_ID.isEmpty()) {
                Text(
                    "AdMob 测试设备未配置：可在 local.properties 设置 admob.test.device.id（Logcat 首次请求后出现）。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider()

            SectionTitle("3. 广告场景")
            ActionButton("Banner") { onLoadBanner(selectedProvider, bannerContainer) }
            ActionButton("开屏 Splash") { onLoadSplash(selectedProvider, bannerContainer) }
            ActionButton("激励视频 Rewarded") { onLoadRewarded(selectedProvider, bannerContainer) }
            ActionButton("插屏 Interstitial") { onLoadInterstitial(selectedProvider, bannerContainer) }
            ActionButton("原生 Native") { onLoadNative(selectedProvider, bannerContainer) }
            ActionButton("MREC") { onLoadMrec(selectedProvider, bannerContainer) }

            HorizontalDivider()

            SectionTitle("4. 预加载")
            ActionButton("预加载 Banner") { onPreloadBanner(selectedProvider) }
            ActionButton("消费预加载 Banner") { onShowCachedBanner(selectedProvider, bannerContainer) }
            Text(
                "triggerId=${SampleConfig.TriggerId.PRELOAD_BANNER}，首次 loadAd 命中缓存。",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()

            SectionTitle("5. 调试")
            ActionButton("打开 SDK Debug 面板") {
                onOpenDebug()
                log("openDebug()")
            }

            HorizontalDivider()

            SectionTitle("Banner / Native / MREC 容器")
            AndroidView(
                factory = { bannerContainer },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
            )

            SectionTitle("事件日志")
            if (eventLog.isEmpty()) {
                Text("暂无日志", style = MaterialTheme.typography.bodySmall)
            } else {
                eventLog.take(12).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun ProviderChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun ActionButton(
    text: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
        Text(text)
    }
}
