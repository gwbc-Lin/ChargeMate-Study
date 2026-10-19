package com.wirelesspet.presentation

/** 方案变更不重新开始计时；零表示无限制。时间来自单调时钟。 */
class PresentationLifetime(private val startedAtMs: Long) {
    fun remainingMs(maxDurationMs: Long, nowMs: Long): Long? =
        if (maxDurationMs <= 0) null
        else (maxDurationMs - (nowMs - startedAtMs).coerceAtLeast(0)).coerceAtLeast(0)
}
