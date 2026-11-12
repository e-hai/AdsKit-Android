package com.kit.ads

import android.util.Log
import androidx.annotation.RestrictTo

/**
 * SDK 内部日志工具类，封装 Android [Log] API 与外部日志拦截器转发。
 *
 * 默认关闭（[enabled] = false），由 [AdsManager.initialize] 中的 `logEnabled` 参数统一控制，
 * 不对外公开引用。
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
object AdsLogger {
    /**
     * 内置控制台日志开关。默认 false；由 [AdsManager.initialize] 赋值。
     */
    @Volatile
    var enabled: Boolean = false

    /**
     * 外部日志拦截器。非空时接管全部日志分发，不再输出到默认 Logcat。
     */
    @Volatile
    var logListener: AdsLogListener? = null

    /** 输出 DEBUG 级别日志 */
    fun d(tag: String, msg: String) {
        dispatch(AdsLogLevel.DEBUG, tag, msg)
    }

    /** 输出 WARN 级别日志 */
    fun w(tag: String, msg: String) {
        dispatch(AdsLogLevel.WARN, tag, msg)
    }

    /** 输出 ERROR 级别日志 */
    fun e(tag: String, msg: String) {
        dispatch(AdsLogLevel.ERROR, tag, msg)
    }

    /** 输出 ERROR 级别日志（含异常堆栈） */
    fun e(tag: String, msg: String, tr: Throwable) {
        dispatch(AdsLogLevel.ERROR, tag, msg, tr)
    }

    private fun dispatch(
        level: AdsLogLevel,
        tag: String,
        msg: String,
        tr: Throwable? = null,
    ) {
        val listener = logListener
        if (listener != null) {
            listener.onLog(level, tag, msg, tr)
            return
        }
        if (!enabled) return
        when (level) {
            AdsLogLevel.DEBUG -> Log.d(tag, msg)
            AdsLogLevel.WARN -> Log.w(tag, msg)
            AdsLogLevel.ERROR -> if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        }
    }
}
