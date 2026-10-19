package com.wirelesspet.data

/** Stable persisted names and CSS identifiers; no external fonts are required. */
enum class ClockStyle(val label: String, val cssName: String, val colors: List<Long>, val weight: Int) {
    AURORA("极光渐变", "aurora", listOf(0xFFDAF9F3, 0xFFA6CEE9, 0xFFC8B5F0), 800),
    MINIMAL("极简纯白", "minimal", listOf(0xFFF5F7FC, 0xFFF5F7FC), 800),
    NEON("霓虹紫", "neon", listOf(0xFFF3B4EF, 0xFFB6A4FF, 0xFF84DAFF), 800),
    MINT("薄荷清新", "mint", listOf(0xFFD8FFF2, 0xFF78D9C0), 800),
    AMBER("复古琥珀", "amber", listOf(0xFFFFDEA0, 0xFFEFA34E), 800),
}
