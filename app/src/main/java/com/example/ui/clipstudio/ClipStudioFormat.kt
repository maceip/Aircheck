package com.example.ui.clipstudio

import androidx.compose.ui.graphics.Color
import com.example.ui.design.AcTokens

/**
 * Parses a persisted 6-digit hex accent string (e.g. `#D71921`) from a newsroom look into a [Color].
 * This is user content rendered into the video overlay, not a UI color.
 */
internal fun parseAccentHex(hex: String?): Color {
    val clean = hex.orEmpty().trim().removePrefix("#")
    return runCatching {
        if (clean.length == 6) Color((0xFF000000L or clean.toLong(16)).toInt()) else AcTokens.Red
    }.getOrDefault(AcTokens.Red)
}

/** Formats milliseconds as `m:ss` (e.g. `0:58`, `1:05`). */
fun mmss(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Formats an epoch timestamp into a concise uppercase relative age string (e.g. `2 DAYS AGO`, `TODAY`). */
fun formatRelativeAgeUpper(updatedAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (updatedAtMillis <= 0L) return "RECENT"
    val diffMs = (nowMillis - updatedAtMillis).coerceAtLeast(0L)
    val mins = diffMs / 60_000L
    val hours = diffMs / 3_600_000L
    val days = diffMs / 86_400_000L
    return when {
        mins < 60L -> if (mins <= 1L) "JUST NOW" else "$mins MINS AGO"
        hours < 24L -> if (hours == 1L) "1 HOUR AGO" else "$hours HOURS AGO"
        days == 1L -> "1 DAY AGO"
        days < 30L -> "$days DAYS AGO"
        else -> "${days / 30L} MO AGO"
    }
}
