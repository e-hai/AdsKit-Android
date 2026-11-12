package com.kit.ads

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.kit.ads.provider.AdsProviderConfig
import com.kit.ads.provider.AdsProviderType
import com.kit.ads.provider.FakeAdsProviderAdapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.widget.FrameLayout
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [24])
class AdsManagerTest {

    @Before
    fun setUp() {
        AdsManager.resetForTest()
        AdsManager.setLogListener(null)
    }

    @After
    fun tearDown() {
        AdsManager.setLogListener(null)
        AdsManager.resetForTest()
    }

    @Test
    fun loadAd_beforeInitialize_failsWithStateIdle() {
        val result = loadAdAndAwaitFailure()

        assertEquals("STATE_IDLE", result.errorCode)
    }

    @Test
    fun loadAd_whileInitializing_failsWithStateInitializing() {
        val fakeAdapter = FakeAdsProviderAdapter(deferInitCallback = true)
        AdsManager.testAdapterFactory = { fakeAdapter }

        val initLatch = CountDownLatch(1)
        AdsManager.initialize(app(), admobConfig()) { initLatch.countDown() }
        drainMainLooper()

        val result = loadAdAndAwaitFailure()
        assertEquals("STATE_INITIALIZING", result.errorCode)

        fakeAdapter.completeInitialize(success = true)
        drainMainLooper()
        initLatch.await(2, TimeUnit.SECONDS)
    }

    @Test
    fun loadAd_afterFailedInitialize_failsWithStateFailed() {
        AdsManager.testAdapterFactory = { FakeAdsProviderAdapter(initSuccess = false) }

        val initLatch = CountDownLatch(1)
        AdsManager.initialize(app(), admobConfig()) { success ->
            assertTrue(!success)
            initLatch.countDown()
        }
        drainMainLooper()
        initLatch.await(2, TimeUnit.SECONDS)

        val result = loadAdAndAwaitFailure()
        assertEquals("STATE_FAILED", result.errorCode)
    }

    @Test
    fun loadAd_providerMismatch_failsWithProviderMismatch() {
        AdsManager.testAdapterFactory = { FakeAdsProviderAdapter() }
        initializeSuccessfully(AdsProviderType.ADMOB)

        val result = loadAdAndAwaitFailure(
            request = testRequest(providerType = AdsProviderType.APPLOVIN)
        )
        assertEquals("PROVIDER_MISMATCH", result.errorCode)
    }

    @Test
    fun initialize_sameProviderTwice_isSkipped() {
        val fakeAdapter = FakeAdsProviderAdapter()
        AdsManager.testAdapterFactory = { fakeAdapter }

        initializeSuccessfully(AdsProviderType.ADMOB)

        val latch = CountDownLatch(1)
        var duplicateSuccess: Boolean? = null
        AdsManager.initialize(app(), admobConfig()) { success ->
            duplicateSuccess = success
            latch.countDown()
        }
        drainMainLooper()
        assertTrue(latch.await(2, TimeUnit.SECONDS))

        assertEquals(1, fakeAdapter.initializeCallCount)
        assertEquals(true, duplicateSuccess)
    }

    @Test
    fun initialize_switchProvider_clearsPreloadedCache() {
        val admobAdapter = FakeAdsProviderAdapter()
        val applovinAdapter = FakeAdsProviderAdapter()
        AdsManager.testAdapterFactory = { type ->
            when (type) {
                AdsProviderType.ADMOB -> admobAdapter
                AdsProviderType.APPLOVIN -> applovinAdapter
            }
        }
        initializeSuccessfully(AdsProviderType.ADMOB)

        val request = testRequest()
        AdsManager.preloadAd(app(), request)
        drainMainLooper()
        assertEquals(1, admobAdapter.loadAdCallCount)

        initializeSuccessfully(AdsProviderType.APPLOVIN)

        // Cache cleared on switch — loadAd must hit network on the new adapter
        val loadLatch = CountDownLatch(1)
        var loaded: AdsEntity? = null
        AdsManager.loadAd(
            app(),
            testRequest(providerType = AdsProviderType.APPLOVIN),
            object : AdCallback() {
                override fun onAdLoaded(ad: AdsEntity) {
                    loaded = ad
                    loadLatch.countDown()
                }
            },
        )
        drainMainLooper()
        assertTrue(loadLatch.await(2, TimeUnit.SECONDS))
        assertNotNull(loaded)
        assertEquals(1, applovinAdapter.loadAdCallCount)
        // Old cached ad was not returned (would have kept admob load count at 1 without new network)
        assertEquals(1, admobAdapter.loadAdCallCount)
    }

    @Test
    fun showAd_failedToShow_forwardsToListener() {
        val fakeAdapter = FakeAdsProviderAdapter(showFails = true)
        AdsManager.testAdapterFactory = { fakeAdapter }
        initializeSuccessfully(AdsProviderType.ADMOB)

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = FrameLayout(activity)
        val failLatch = CountDownLatch(1)
        var showErrorCode: String? = null

        AdsManager.loadAd(app(), testRequest(), object : AdCallback() {
            override fun onAdLoaded(ad: AdsEntity) {
                ad.show(activity, container)
            }

            override fun onAdFailedToShow(error: String, errorCode: String?) {
                showErrorCode = errorCode
                failLatch.countDown()
            }
        })
        drainMainLooper()
        assertTrue(failLatch.await(2, TimeUnit.SECONDS))
        assertEquals("DISPLAY_FAKE", showErrorCode)
    }

    @Test
    fun getInitState_tracksLifecycle() {
        assertEquals(AdsInitState.IDLE, AdsManager.getInitState())
        assertNull(AdsManager.getInitializedProviderType())

        val fakeAdapter = FakeAdsProviderAdapter(deferInitCallback = true)
        AdsManager.testAdapterFactory = { fakeAdapter }
        AdsManager.initialize(app(), admobConfig())
        drainMainLooper()
        assertEquals(AdsInitState.INITIALIZING, AdsManager.getInitState())

        fakeAdapter.completeInitialize(success = true)
        drainMainLooper()
        assertEquals(AdsInitState.READY, AdsManager.getInitState())
        assertEquals(AdsProviderType.ADMOB, AdsManager.getInitializedProviderType())

        AdsManager.destroy()
        assertEquals(AdsInitState.IDLE, AdsManager.getInitState())
        assertNull(AdsManager.getInitializedProviderType())
    }

    @Test
    fun preloadAd_expiredCache_triggersNetworkReload() {
        val fakeAdapter = FakeAdsProviderAdapter()
        AdsManager.testAdapterFactory = { fakeAdapter }
        initializeSuccessfully(AdsProviderType.ADMOB)

        var now = 1_000L
        AdsManager.clockMs = { now }

        val request = testRequest(preloadTtlMs = 100L)
        AdsManager.preloadAd(app(), request)
        drainMainLooper()
        assertEquals(1, fakeAdapter.loadAdCallCount)

        now = 1_200L // past TTL
        val loadLatch = CountDownLatch(1)
        AdsManager.loadAd(app(), request, object : AdCallback() {
            override fun onAdLoaded(ad: AdsEntity) {
                loadLatch.countDown()
            }
        })
        drainMainLooper()
        assertTrue(loadLatch.await(2, TimeUnit.SECONDS))
        assertEquals(2, fakeAdapter.loadAdCallCount)
    }

    @Test
    fun preloadAd_loadFailure_doesNotCache() {
        val fakeAdapter = FakeAdsProviderAdapter(loadSucceeds = false)
        AdsManager.testAdapterFactory = { fakeAdapter }
        initializeSuccessfully(AdsProviderType.ADMOB)

        val request = testRequest()
        AdsManager.preloadAd(app(), request)
        drainMainLooper()
        assertEquals(1, fakeAdapter.loadAdCallCount)

        val result = loadAdAndAwaitFailure(request = request)
        assertEquals("FAKE_LOAD_FAILED", result.errorCode)
        assertEquals(2, fakeAdapter.loadAdCallCount)
    }

    @Test
    fun initialize_staleCallbackAfterDestroy_doesNotSetReady() {
        val fakeAdapter = FakeAdsProviderAdapter(deferInitCallback = true)
        AdsManager.testAdapterFactory = { fakeAdapter }

        AdsManager.initialize(app(), admobConfig())
        drainMainLooper()
        assertEquals(AdsInitState.INITIALIZING, AdsManager.getInitState())

        AdsManager.destroy()
        drainMainLooper()
        assertEquals(AdsInitState.IDLE, AdsManager.getInitState())

        fakeAdapter.completeInitialize(success = true)
        drainMainLooper()
        assertEquals(AdsInitState.IDLE, AdsManager.getInitState())
        assertNull(AdsManager.getInitializedProviderType())
    }

    @Test
    fun initialize_concurrentCall_returnsFalse() {
        val fakeAdapter = FakeAdsProviderAdapter(deferInitCallback = true)
        AdsManager.testAdapterFactory = { fakeAdapter }

        val firstLatch = CountDownLatch(1)
        AdsManager.initialize(app(), admobConfig()) { firstLatch.countDown() }
        drainMainLooper()

        val secondLatch = CountDownLatch(1)
        var secondResult: Boolean? = null
        AdsManager.initialize(app(), admobConfig()) { success ->
            secondResult = success
            secondLatch.countDown()
        }
        drainMainLooper()
        assertTrue(secondLatch.await(2, TimeUnit.SECONDS))
        assertEquals(false, secondResult)

        fakeAdapter.completeInitialize(success = true)
        drainMainLooper()
        firstLatch.await(2, TimeUnit.SECONDS)
    }

    @Test
    fun loadAd_providerCallbackOnBackgroundThread_dispatchesToMainThread() {
        val fakeAdapter = FakeAdsProviderAdapter(callbackOnBackground = true)
        AdsManager.testAdapterFactory = { fakeAdapter }
        initializeSuccessfully(AdsProviderType.ADMOB)

        val latch = CountDownLatch(1)
        var callbackThread: Thread? = null
        AdsManager.loadAd(app(), testRequest(), object : AdCallback() {
            override fun onAdLoaded(ad: AdsEntity) {
                callbackThread = Thread.currentThread()
                latch.countDown()
            }
        })
        fakeAdapter.awaitBackgroundCallbacks()
        drainMainLooper()
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(Looper.getMainLooper().thread, callbackThread)
    }

    @Test
    fun destroy_resetsToIdle() {
        AdsManager.testAdapterFactory = { FakeAdsProviderAdapter() }
        initializeSuccessfully(AdsProviderType.ADMOB)
        AdsManager.destroy()

        val result = loadAdAndAwaitFailure()
        assertEquals("STATE_IDLE", result.errorCode)
    }

    @Test
    fun preloadAd_thenLoadAd_returnsCachedAdWithoutSecondNetworkLoad() {
        val fakeAdapter = FakeAdsProviderAdapter()
        AdsManager.testAdapterFactory = { fakeAdapter }
        initializeSuccessfully(AdsProviderType.ADMOB)

        val request = testRequest()
        AdsManager.preloadAd(app(), request)
        drainMainLooper()

        val loadLatch = CountDownLatch(1)
        var loadedAd: AdsEntity? = null
        AdsManager.loadAd(app(), request, object : AdCallback() {
            override fun onAdLoaded(ad: AdsEntity) {
                loadedAd = ad
                loadLatch.countDown()
            }
        })
        drainMainLooper()
        loadLatch.await(2, TimeUnit.SECONDS)

        assertNotNull(loadedAd)
        assertEquals(1, fakeAdapter.loadAdCallCount)
    }

    @Test
    fun destroy_clearsPreloadedCache() {
        val fakeAdapter = FakeAdsProviderAdapter()
        AdsManager.testAdapterFactory = { fakeAdapter }
        initializeSuccessfully(AdsProviderType.ADMOB)

        val request = testRequest()
        AdsManager.preloadAd(app(), request)
        drainMainLooper()
        AdsManager.destroy()

        val result = loadAdAndAwaitFailure(request = request)
        assertEquals("STATE_IDLE", result.errorCode)
        assertEquals(1, fakeAdapter.loadAdCallCount)
    }

    @Test
    fun initialize_success_allowsNetworkLoad() {
        val fakeAdapter = FakeAdsProviderAdapter()
        AdsManager.testAdapterFactory = { fakeAdapter }
        initializeSuccessfully(AdsProviderType.ADMOB)

        val loadLatch = CountDownLatch(1)
        var loadedAd: AdsEntity? = null
        AdsManager.loadAd(app(), testRequest(), object : AdCallback() {
            override fun onAdLoaded(ad: AdsEntity) {
                loadedAd = ad
                loadLatch.countDown()
            }
        })
        drainMainLooper()
        loadLatch.await(2, TimeUnit.SECONDS)

        assertNotNull(loadedAd)
        assertEquals(1, fakeAdapter.loadAdCallCount)
    }

    private fun initializeSuccessfully(providerType: AdsProviderType) {
        val latch = CountDownLatch(1)
        var success = false
        AdsManager.initialize(app(), config(providerType)) { result ->
            success = result
            latch.countDown()
        }
        drainMainLooper()
        latch.await(2, TimeUnit.SECONDS)
        assertTrue(success)
    }

    private fun loadAdAndAwaitFailure(
        request: AdsRequest = testRequest(),
    ): FailureResult {
        val latch = CountDownLatch(1)
        var result = FailureResult()
        AdsManager.loadAd(app(), request, object : AdCallback() {
            override fun onAdFailedToLoad(error: String, errorCode: String?) {
                result = FailureResult(error, errorCode)
                latch.countDown()
            }
        })
        drainMainLooper()
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        return result
    }

    @Test
    fun initialize_setsLogAndDebugSwitches() {
        val fakeAdapter = FakeAdsProviderAdapter()
        AdsManager.testAdapterFactory = { fakeAdapter }

        AdsManager.initialize(app(), admobConfig())
        drainMainLooper()
        assertEquals(false, AdsLogger.enabled)
        assertEquals(false, AdsDebug.isEnabled)

        AdsManager.destroy()
        AdsManager.initialize(
            context = app(),
            config = admobConfig(),
            logEnabled = true,
            debugEnabled = true,
        )
        drainMainLooper()
        assertEquals(true, AdsLogger.enabled)
        assertEquals(true, AdsDebug.isEnabled)
    }

    @Test
    fun initialize_andSetLogListener_interceptsLogs() {
        val fakeAdapter = FakeAdsProviderAdapter()
        AdsManager.testAdapterFactory = { fakeAdapter }

        val captured = mutableListOf<Pair<AdsLogLevel, String>>()
        AdsManager.initialize(
            context = app(),
            config = admobConfig(),
            logEnabled = false,
            logListener = { level, tag, message, _ ->
                captured += level to "[$tag] $message"
            },
        )
        drainMainLooper()

        assertTrue(captured.any { it.first == AdsLogLevel.DEBUG && it.second.contains("initialize start") })
        assertTrue(captured.any { it.first == AdsLogLevel.DEBUG && it.second.contains("initialize complete") })

        val countBeforeClear = captured.size
        AdsManager.setLogListener(null)
        AdsManager.destroy()
        assertEquals(countBeforeClear, captured.size)
    }

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    private fun admobConfig(): AdsProviderConfig =
        AdsProviderConfig(AdsProviderType.ADMOB, "test-app-id")

    private fun config(providerType: AdsProviderType): AdsProviderConfig =
        when (providerType) {
            AdsProviderType.ADMOB -> admobConfig()
            AdsProviderType.APPLOVIN -> AdsProviderConfig(AdsProviderType.APPLOVIN, "test-sdk-key")
        }

    private fun testRequest(
        triggerId: String = "home_banner",
        providerType: AdsProviderType = AdsProviderType.ADMOB,
        preloadTtlMs: Long? = null,
    ): AdsRequest = AdsRequest(
        triggerId = triggerId,
        adUnitId = "test-ad-unit",
        adType = AdsType.BANNER,
        providerType = providerType,
        preloadTtlMs = preloadTtlMs,
    )

    private fun drainMainLooper() {
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    }

    private data class FailureResult(
        val error: String? = null,
        val errorCode: String? = null,
    )
}
