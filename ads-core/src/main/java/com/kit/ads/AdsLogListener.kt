package com.kit.ads

/**
 * SDK 日志级别。
 */
enum class AdsLogLevel {
    DEBUG,
    WARN,
    ERROR,
}

/**
 * 外部日志拦截回调接口。
 *
 * 通过 [AdsManager.initialize] 或 [AdsManager.setLogListener] 注入后，
 * SDK 内部日志将不再直接输出到 Logcat，而是统一回调给宿主处理（如转发至自定义日志库或崩溃上报）。
 */
fun interface AdsLogListener {
    fun onLog(level: AdsLogLevel, tag: String, message: String, throwable: Throwable?)
}
