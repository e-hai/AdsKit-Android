package com.kit.ads.ump

import android.app.Activity
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Google UMP 隐私同意封装，供 AdMob / AppLovin 共用。
 */
object UMP {

    /**
     * 启动隐私同意流程。
     *
     * @param activity 用于展示同意表单
     * @param debugEnabled 是否开启 UMP 调试模式（默认 false；开启后将模拟 EEA 地区并重置同意状态，仅限本地测试）
     * @param umpTestDeviceIds UMP 调试用测试设备哈希 ID 列表（仅在 [debugEnabled] = true 时生效）
     * @param callBack `true` 表示 [ConsentInformation.canRequestAds] 为 true
     */
    @JvmOverloads
    fun start(
        activity: Activity,
        debugEnabled: Boolean = false,
        umpTestDeviceIds: List<String> = emptyList(),
        callBack: (gathered: Boolean) -> Unit,
    ) {
        val params = if (debugEnabled) {
            val debugSettingsBuilder = ConsentDebugSettings.Builder(activity)
                .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
            umpTestDeviceIds.forEach { deviceId ->
                debugSettingsBuilder.addTestDeviceHashedId(deviceId)
            }
            ConsentRequestParameters.Builder()
                .setConsentDebugSettings(debugSettingsBuilder.build())
                .build()
        } else {
            ConsentRequestParameters.Builder()
                .setTagForUnderAgeOfConsent(false)
                .build()
        }

        val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
        if (debugEnabled) {
            consentInformation.reset()
        }

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                if (!activity.isFinishing) {
                    showConsentForm(consentInformation, activity, callBack)
                }
            },
            {
                if (!activity.isFinishing) {
                    checkUser(consentInformation, callBack)
                }
            },
        )
    }

    private fun showConsentForm(
        consentInformation: ConsentInformation,
        activity: Activity,
        callBack: (gathered: Boolean) -> Unit,
    ) {
        UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
            if (!activity.isFinishing) {
                checkUser(consentInformation, callBack)
            }
        }
    }

    private fun checkUser(
        consentInformation: ConsentInformation,
        callBack: (gathered: Boolean) -> Unit,
    ) {
        callBack.invoke(consentInformation.canRequestAds())
    }
}
