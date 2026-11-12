package com.kit.ads

import androidx.annotation.RestrictTo

/**
 * SDK 内部调试模式开关，由 [AdsManager.initialize] 中的 `debugEnabled` 参数统一赋值。
 * 默认关闭（`false`），不对外公开引用。
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object AdsDebug {
    @JvmField
    @Volatile
    var isEnabled: Boolean = false
}
