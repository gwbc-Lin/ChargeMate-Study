package com.wirelesspet.power

/** Shared by the companion badge and home preview; driven only by real battery updates. */
object BatteryCaption {
    fun message(power: PowerSnapshot): String = when {
        power.status == ChargingStatus.FULL || power.levelPercent == 100 -> "充满了，也不许走～"
        power.status == ChargingStatus.NOT_CHARGING -> "怎么停了？继续嘛～"
        power.status == ChargingStatus.CHARGING -> when (power.levelPercent) {
            in 0..19 -> "没力气了…快给我～"
            in 20..49 -> "慢慢来…时间还早～"
            in 50..79 -> "还不够…还想要…"
            in 80..99 -> "快满了…再久一点～"
            else -> "别急…我还想要～"
        }
        power.levelPercent in 0..19 -> "都快耗尽了，你得负责！"
        else -> "还没接上呢…"
    }

    fun badge(power: PowerSnapshot): String =
        if (power.levelPercent in 0..100) "${power.levelPercent}% · ${message(power)}" else message(power)
}
