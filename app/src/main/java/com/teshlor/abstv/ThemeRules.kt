package com.teshlor.abstv

import java.util.Calendar

enum class AbsTheme(val key: String) {
    AUTUMN("autumn"), HALLOWEEN("halloween"), THANKSGIVING("thanksgiving"), CHRISTMAS("christmas"),
    WINTER("winter"), SPRING("spring"), SUMMER("summer"),
    NEW_YEAR("newyear"), AURORA("aurora"), VALENTINE("valentine"), FIREFLIES("fireflies"), JULY_4("july4");

    companion object {
        fun fromKey(key: String?): AbsTheme? = entries.firstOrNull { it.key == key?.lowercase() }
    }
}

/**
 * Picks the theme for a local date (see design/themes/HANDOFF.md section 2). Holidays and moments
 * replace the season. minSdk 23 without desugaring, so this uses Calendar rather than java.time.
 */
fun themeFor(now: Calendar = Calendar.getInstance()): AbsTheme {
    val y = now.get(Calendar.YEAR)
    val m = now.get(Calendar.MONTH) + 1
    val d = now.get(Calendar.DAY_OF_MONTH)
    val md = m * 100 + d
    val doy = now.get(Calendar.DAY_OF_YEAR)
    val nov1 = Calendar.getInstance().apply { clear(); set(y, Calendar.NOVEMBER, 1) }
    val t = 1 + (Calendar.THURSDAY - nov1.get(Calendar.DAY_OF_WEEK) + 7) % 7 + 21 // 4th Thursday
    val tDoy = nov1.get(Calendar.DAY_OF_YEAR) + t - 1
    val dec26 = Calendar.getInstance().apply { clear(); set(y, Calendar.DECEMBER, 26) }.get(Calendar.DAY_OF_YEAR)
    return when {
        md in 1025..1031 -> AbsTheme.HALLOWEEN
        doy in (tDoy - 3)..(tDoy + 3) -> AbsTheme.THANKSGIVING
        doy in (tDoy + 4)..dec26 -> AbsTheme.CHRISTMAS
        md == 1231 || md == 101 -> AbsTheme.NEW_YEAR
        md in 115..131 -> AbsTheme.AURORA
        md in 212..214 -> AbsTheme.VALENTINE
        md in 621..630 -> AbsTheme.FIREFLIES
        md in 701..704 -> AbsTheme.JULY_4
        m in 3..5 -> AbsTheme.SPRING
        m in 6..8 -> AbsTheme.SUMMER
        m in 9..11 -> AbsTheme.AUTUMN
        else -> AbsTheme.WINTER
    }
}
