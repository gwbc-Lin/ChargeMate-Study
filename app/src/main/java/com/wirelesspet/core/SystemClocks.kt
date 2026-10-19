package com.wirelesspet.core

import android.os.SystemClock

/** 真机单调时钟：基于 SystemClock.elapsedRealtime()，不受用户改系统时间影响。 */
object SystemMonoClockHolder {
    val clock: MonoClock = MonoClock { SystemClock.elapsedRealtime() }
}

object SystemWallClockHolder {
    val clock: WallClock = WallClock { System.currentTimeMillis() }
}
