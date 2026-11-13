package com.kit.ads.provider.admob

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.RequiresPermission
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.OnPaidEventListener
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.kit.ads.AdsLogger
import com.kit.ads.AdsPaidEvent
import com.kit.ads.AdsRequest
import com.kit.ads.AdsType
import com.kit.ads.provider.AdsProviderAdapter
import com.kit.ads.provider.AdsProviderConfig
import com.kit.ads.provider.AdsProviderListener

internal class AdmobProviderAdapter : AdsProviderAdapter {

    @SuppressLint("DefaultLocale")
    override fun initialize(
        context: Application,
        config: AdsProviderConfig,
        listener: (success: Boolean) -> Unit,
    ) {
        d("initialize start")
        try {
            MobileAds.initialize(context) { initializationStatus ->
                val statusMap = initializationStatus.adapterStatusMap
                for ((adapterClass, status) in statusMap) {
                    d(
                        "mediationAdapter name=$adapterClass description=${status?.description} latency=${status?.latency}",
                    )
                }
                d("initialize complete success=true adapters=${statusMap.size}")
                listener.invoke(true)
            }
        } catch (ex: Exception) {
            e("initialize complete success=false error=${ex.message}", ex)
            listener.invoke(false)
        }
    }

    override fun openDebug(activity: Activity) {
        d("openDebug")
        MobileAds.openAdInspector(activity) { error ->
            if (error != null) {
                e("openAdInspector closed error=${error.message}")
            } else {
                d("openAdInspector closed")
            }
        }
    }

    @RequiresPermission(Manifest.permission.INTERNET)
    override fun loadAd(
        context: Context,
        request: AdsRequest,
        listener: AdsProviderListener,
    ) {
        log(request, "loadAd")
        when (request.adType) {
            AdsType.BANNER -> loadBanner(context, request, listener)
            AdsType.SPLASH -> loadSplash(context, request, listener)
            AdsType.REWARDED -> loadRewarded(context, request, listener)
            AdsType.INTERSTITIAL -> loadInterstitial(context, request, listener)
            AdsType.NATIVE -> loadNative(context, request, listener)
            AdsType.MREC -> loadMrec(context, request, listener)
        }
    }

    @RequiresPermission(Manifest.permission.INTERNET)
    private fun loadBanner(
        context: Context,
        request: AdsRequest,
        listener: AdsProviderListener,
    ) {
        val unitId = request.adUnitId
        val ad = AdView(context).apply {
            adUnitId = unitId
            val adWidth = defaultBannerAdWidth(context)
            val adSize = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidth)
            d("bannerSize adaptive widthDp=$adWidth heightDp=${adSize.height}")
            setAdSize(adSize)
        }
        ad.adListener = object : AdListener() {
            override fun onAdClicked() {
                log(request, "onAdClicked", unitId)
                listener.onAdClicked()
            }

            override fun onAdClosed() {
                // Banner: called when returning from overlay, not ad dismiss
                log(request, "onAdOverlayClosed", unitId)
            }

            override fun onAdFailedToLoad(adError: LoadAdError) {
                logLoadAdError(request, unitId, adError)
                listener.onAdFailedToLoad(adError.message, adError.code.toString())
            }

            override fun onAdImpression() {
                log(request, "onAdShown", unitId)
                listener.onAdShown()
            }

            override fun onAdLoaded() {
                log(request, "onAdLoaded", unitId)
                listener.onAdLoaded(ad)
            }

            override fun onAdOpened() {
                log(request, "onAdOpened", unitId)
            }
        }
        ad.onPaidEventListener = paidListener(request, unitId, listener)
        listener.onAdStartedToLoad()
        ad.loadAd(AdRequest.Builder().build())
    }

    private fun defaultBannerAdWidth(context: Context): Int {
        val displayMetrics = context.resources.displayMetrics
        return (displayMetrics.widthPixels / displayMetrics.density).toInt()
    }

    private fun loadRewarded(
        context: Context,
        request: AdsRequest,
        listener: AdsProviderListener,
    ) {
        listener.onAdStartedToLoad()
        val unitId = request.adUnitId
        RewardedAd.load(
            context,
            unitId,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    logLoadAdError(request, unitId, adError)
                    listener.onAdFailedToLoad(adError.message, adError.code.toString())
                }

                override fun onAdLoaded(ad: RewardedAd) {
                    attachFullScreenCallbacks(request, unitId, ad, listener)
                    ad.onPaidEventListener = paidListener(request, unitId, listener)
                    log(request, "onAdLoaded", unitId)
                    listener.onAdLoaded(ad)
                }
            },
        )
    }

    private fun loadInterstitial(
        context: Context,
        request: AdsRequest,
        listener: AdsProviderListener,
    ) {
        listener.onAdStartedToLoad()
        val unitId = request.adUnitId
        InterstitialAd.load(
            context,
            unitId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    logLoadAdError(request, unitId, adError)
                    listener.onAdFailedToLoad(adError.message, adError.code.toString())
                }

                override fun onAdLoaded(ad: InterstitialAd) {
                    attachFullScreenCallbacks(request, unitId, ad, listener)
                    ad.onPaidEventListener = paidListener(request, unitId, listener)
                    log(request, "onAdLoaded", unitId)
                    listener.onAdLoaded(ad)
                }
            },
        )
    }

    @RequiresPermission(Manifest.permission.INTERNET)
    private fun loadNative(
        context: Context,
        request: AdsRequest,
        listener: AdsProviderListener,
    ) {
        listener.onAdStartedToLoad()
        val unitId = request.adUnitId
        AdLoader.Builder(context, unitId)
            .forNativeAd { nativeAd ->
                nativeAd.setOnPaidEventListener { paid ->
                    logPaid(request, unitId, paid.valueMicros, paid.currencyCode, paid.precisionType.toString())
                    listener.onAdPaidEvent(
                        AdsPaidEvent(
                            valueMicros = paid.valueMicros,
                            currencyCode = paid.currencyCode,
                            precision = paid.precisionType.toString(),
                        ),
                    )
                }
                log(request, "onAdLoaded", unitId)
                listener.onAdLoaded(nativeAd)
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    logLoadAdError(request, unitId, adError)
                    listener.onAdFailedToLoad(adError.message, adError.code.toString())
                }

                override fun onAdClicked() {
                    log(request, "onAdClicked", unitId)
                    listener.onAdClicked()
                }

                override fun onAdImpression() {
                    log(request, "onAdShown", unitId)
                    listener.onAdShown()
                }
            })
            .build()
            .loadAd(AdRequest.Builder().build())
    }

    @RequiresPermission(Manifest.permission.INTERNET)
    private fun loadMrec(
        context: Context,
        request: AdsRequest,
        listener: AdsProviderListener,
    ) {
        val unitId = request.adUnitId
        val ad = AdView(context).apply {
            adUnitId = unitId
            setAdSize(AdSize.MEDIUM_RECTANGLE)
        }
        ad.adListener = object : AdListener() {
            override fun onAdClicked() {
                log(request, "onAdClicked", unitId)
                listener.onAdClicked()
            }

            override fun onAdFailedToLoad(adError: LoadAdError) {
                logLoadAdError(request, unitId, adError)
                listener.onAdFailedToLoad(adError.message, adError.code.toString())
            }

            override fun onAdImpression() {
                log(request, "onAdShown", unitId)
                listener.onAdShown()
            }

            override fun onAdLoaded() {
                log(request, "onAdLoaded", unitId)
                listener.onAdLoaded(ad)
            }
        }
        ad.onPaidEventListener = paidListener(request, unitId, listener)
        listener.onAdStartedToLoad()
        ad.loadAd(AdRequest.Builder().build())
    }

    private fun attachFullScreenCallbacks(
        request: AdsRequest,
        unitId: String,
        ad: Any,
        listener: AdsProviderListener,
    ) {
        val callback = object : FullScreenContentCallback() {
            override fun onAdClicked() {
                log(request, "onAdClicked", unitId)
                listener.onAdClicked()
            }

            override fun onAdDismissedFullScreenContent() {
                log(request, "onAdClosed", unitId)
                listener.onAdClosed()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                e(
                    "onAdFailedToShow id=${request.triggerId} unit=$unitId type=${request.adType} error=${error.message} errorCode=${error.code}",
                )
                listener.onAdFailedToShow(ad, error.message, error.code.toString())
            }

            override fun onAdImpression() {
                log(request, "onAdShown", unitId)
                listener.onAdShown()
            }

            override fun onAdShowedFullScreenContent() {
                log(request, "onAdShowedFullScreen", unitId)
            }
        }
        when (ad) {
            is RewardedAd -> ad.fullScreenContentCallback = callback
            is InterstitialAd -> ad.fullScreenContentCallback = callback
            is AppOpenAd -> ad.fullScreenContentCallback = callback
        }
    }

    private fun loadSplash(
        context: Context,
        request: AdsRequest,
        listener: AdsProviderListener,
    ) {
        listener.onAdStartedToLoad()
        val unitId = request.adUnitId
        AppOpenAd.load(
            context,
            unitId,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    attachFullScreenCallbacks(request, unitId, ad, listener)
                    ad.onPaidEventListener = paidListener(request, unitId, listener)
                    log(request, "onAdLoaded", unitId)
                    listener.onAdLoaded(ad)
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    logLoadAdError(request, unitId, loadAdError)
                    listener.onAdFailedToLoad(loadAdError.message, loadAdError.code.toString())
                }
            },
        )
    }

    override fun showAd(
        activity: Activity,
        container: ViewGroup,
        request: AdsRequest,
        ad: Any,
        listener: AdsProviderListener,
    ) {
        log(request, "showAd")
        when (request.adType) {
            AdsType.SPLASH -> showSplash(activity, ad as AppOpenAd)
            AdsType.BANNER, AdsType.MREC -> showBanner(container, ad as AdView)
            AdsType.REWARDED -> showRewarded(activity, request, ad as RewardedAd, listener)
            AdsType.INTERSTITIAL -> showInterstitial(activity, ad as InterstitialAd)
            AdsType.NATIVE -> showNative(container, ad as NativeAd)
        }
    }

    private fun showBanner(container: ViewGroup, ad: AdView) {
        if (ad.parent != null) {
            (ad.parent as ViewGroup).removeView(ad)
        }
        container.addView(ad)
    }

    private fun showRewarded(
        activity: Activity,
        request: AdsRequest,
        ad: RewardedAd,
        listener: AdsProviderListener,
    ) {
        ad.show(activity) { rewardItem ->
            d(
                "onAdUserEarnedReward id=${request.triggerId} unit=${ad.adUnitId} type=${request.adType} amount=${rewardItem.amount} rewardType=${rewardItem.type}",
            )
            listener.onAdUserEarnedReward()
        }
    }

    private fun showInterstitial(activity: Activity, ad: InterstitialAd) {
        ad.show(activity)
    }

    private fun showNative(container: ViewGroup, nativeAd: NativeAd) {
        val context = container.context
        val adView = NativeAdView(context)
        val headlineView = TextView(context)
        headlineView.text = nativeAd.headline
        adView.headlineView = headlineView
        adView.addView(
            headlineView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        nativeAd.mediaContent?.let {
            val mediaView = MediaView(context)
            adView.mediaView = mediaView
            adView.addView(
                mediaView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        adView.setNativeAd(nativeAd)
        container.removeAllViews()
        container.addView(adView)
    }

    private fun showSplash(activity: Activity, ad: AppOpenAd) {
        ad.show(activity)
    }

    override fun destroyAd(ad: Any) {
        when (ad) {
            is AdView -> {
                ad.removeAllViews()
                ad.destroy()
                d("destroyAd type=AdView unit=${ad.adUnitId}")
            }
            is NativeAd -> {
                ad.destroy()
                d("destroyAd type=NativeAd")
            }
        }
    }

    private companion object {
        private const val TAG = "AdsKit-Admob"

        fun d(msg: String) = AdsLogger.d(TAG, msg)

        fun w(msg: String) = AdsLogger.w(TAG, msg)

        fun e(msg: String, tr: Throwable? = null) {
            if (tr != null) {
                AdsLogger.e(TAG, msg, tr)
            } else {
                AdsLogger.e(TAG, msg)
            }
        }

        fun log(request: AdsRequest, event: String, unitId: String = request.adUnitId, extras: String = "") {
            val base = "$event id=${request.triggerId} unit=$unitId type=${request.adType}"
            d(if (extras.isEmpty()) base else "$base $extras")
        }

        fun logPaid(
            request: AdsRequest,
            unitId: String,
            valueMicros: Long,
            currency: String,
            precision: String,
        ) {
            d(
                "onAdPaidEvent id=${request.triggerId} unit=$unitId type=${request.adType} valueMicros=$valueMicros currency=$currency precision=$precision",
            )
        }

        fun paidListener(
            request: AdsRequest,
            unitId: String,
            listener: AdsProviderListener,
        ): OnPaidEventListener = OnPaidEventListener { paid ->
            logPaid(request, unitId, paid.valueMicros, paid.currencyCode, paid.precisionType.toString())
            listener.onAdPaidEvent(
                AdsPaidEvent(
                    valueMicros = paid.valueMicros,
                    currencyCode = paid.currencyCode,
                    precision = paid.precisionType.toString(),
                ),
            )
        }

        fun logLoadAdError(request: AdsRequest, unitId: String, error: LoadAdError) {
            val hint = when (error.code) {
                0 -> "INTERNAL_ERROR"
                2 -> "NETWORK_ERROR"
                3 -> "NO_FILL"
                else -> null
            }
            e(
                "onAdFailedToLoad id=${request.triggerId} unit=$unitId type=${request.adType} error=${error.message} errorCode=${error.code} domain=${error.domain} hint=$hint response=${error.responseInfo}",
            )
        }
    }
}
