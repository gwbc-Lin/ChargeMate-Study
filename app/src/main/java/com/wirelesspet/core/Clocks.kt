package com.wirelesspet.core

/**
 * 单调时钟：防抖、动画节奏、展示超时都基于它，避免用户改系统时间后出现负间隔或跳变。
 * 真机实现映射到 SystemClock.elapsedRealtime()。
 */
fun interface MonoClock {
    fun elapsedRealtimeMs(): Long
}

/** 本地时间：只用于日志时间戳与生效时段判断。 */
fun interface WallClock {
    fun currentTimeMillis(): Long
}

/** 测试用可控时钟。 */
class FakeMonoClock(var nowMs: Long = 0L) : MonoClock {
    override fun elapsedRealtimeMs(): Long = nowMs

    fun advanceBy(deltaMs: Long): Long {
        nowMs += deltaMs
        return nowMs
    }
}
