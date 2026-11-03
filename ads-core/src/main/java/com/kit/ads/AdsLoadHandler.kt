package com.kit.ads

import android.content.Context
import com.kit.ads.provider.AdsProviderAdapter
import com.kit.ads.provider.AdsProviderListener

private const val TAG = "AdsKit-AdsLoadHandler"

/**
 * 广告加载处理器
 *
 * 封装一次广告加载请求的生命周期：将 [AdsProviderAdapter.loadAd] 的底层回调
 * 映射为公开的 [AdsListener] 回调，并确保所有回调在主线程派发。
 */
internal class AdsLoadHandler(
    private val request: AdsRequest,
    private val providerAdapter: AdsProviderAdapter  // 广告提供商适配器

) {


    /**
     * 加载广告
     *
     * 调用广告提供商适配器加载广告资源。
     *
     * @param context 上下文
     */
    fun loadAd(context: Context, listener: AdsListener) {
        val triggerId = request.triggerId
        val adUnitId = request.adUnitId
        val adType = request.adType
        val providerType = request.providerType
        val adsProviderListener = object : AdsProviderListener {

            override fun onAdStartedToLoad() {
                AdsLogger.d(
                    TAG,
                    "onAdStartedToLoad id=$triggerId unit=$adUnitId type=$adType provider=$providerType"
                )
                listener.onAdStartedToLoad()
            }

            override fun onAdLoaded(ad: Any) {
                AdsLogger.d(
                    TAG,
                    "onAdLoaded id=$triggerId unit=$adUnitId type=$adType provider=$providerType"
                )
                val adEntity = AdsEntity(providerAdapter, request, this, ad)
                listener.onAdLoaded(adEntity)
            }

            override fun onAdFailedToLoad(error: String, errorCode: String?) {
                AdsLogger.e(
                    TAG,
                    "onAdFailedToLoad id=$triggerId unit=$adUnitId type=$adType provider=$providerType error=$error errorCode=$errorCode"
                )
                listener.onAdFailedToLoad(error, errorCode)
            }

            override fun onAdShown() {
                AdsLogger.d(
                    TAG,
                    "onAdShown id=$triggerId unit=$adUnitId type=$adType provider=$providerType"
                )
                listener.onAdShown()
            }

            override fun onAdClicked() {
                AdsLogger.d(
                    TAG,
                    "onAdClicked id=$triggerId unit=$adUnitId type=$adType provider=$providerType"
                )
                listener.onAdClicked()
            }

            override fun onAdPaidEvent() {
                AdsLogger.d(
                    TAG,
                    "onAdPaidEvent id=$triggerId unit=$adUnitId type=$adType provider=$providerType"
                )
                listener.onAdPaidEvent()
            }

            override fun onAdPaidEvent(paid: AdsPaidEvent) {
                AdsLogger.d(
                    TAG,
                    "onAdPaidEvent id=$triggerId unit=$adUnitId type=$adType provider=$providerType valueMicros=${paid.valueMicros} currency=${paid.currencyCode} precision=${paid.precision}",
                )
                listener.onAdPaidEvent(paid)
            }

            override fun onAdClosed() {
                AdsLogger.d(
                    TAG,
                    "onAdClosed id=$triggerId unit=$adUnitId type=$adType provider=$providerType"
                )
                listener.onAdClosed()
            }

            override fun onAdUserEarnedReward() {
                AdsLogger.d(
                    TAG,
                    "onAdUserEarnedReward id=$triggerId unit=$adUnitId type=$adType provider=$providerType"
                )
                listener.onAdUserEarnedReward()
            }

            override fun onAdFailedToShow(ad: Any, error: String, errorCode: String?) {
                AdsLogger.e(
                    TAG,
                    "onAdFailedToShow id=$triggerId unit=$adUnitId type=$adType provider=$providerType error=$error errorCode=$errorCode",
                )
                listener.onAdFailedToShow(error, errorCode)
            }
        }
        providerAdapter.loadAd(context, request, adsProviderListener)
    }

}
