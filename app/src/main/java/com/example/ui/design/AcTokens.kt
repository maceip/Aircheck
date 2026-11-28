package com.example.ui.design

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Hardware finish palette for Aircheck design v2 (docs/DESIGN_SYSTEM.md).
 * Light = brushed aluminum (TP-7 / OP-1 Field light), Dark = anodized stealth black.
 */
data class AcPalette(
    val isDark: Boolean,
    val chassis: Color,
    val chassisHi: Color,
    val chassisLo: Color,
    val ui: Color,
    val ink: Color,
    val ink2: Color,
    val hairline: Color,
    val well: Color,
    val mediaKey: Color,
    val mediaKeyHi: Color,
    val mediaKeyLo: Color,
    val raiseHighlight: Color,
    val raiseShadow: Color
)

val LightAcPalette = AcPalette(
    isDark = false,
    chassis = Color(0xFFD9DAD7),
    chassisHi = Color(0xFFF1F1EF),
    chassisLo = Color(0xFFA8A9A5),
    ui = Color(0xFFF5F5F3),
    ink = Color(0xFF141414),
    ink2 = Color(0xFF5C5D5A),
    hairline = Color(0xFFC4C5C1),
    well = Color(0xFF0B0B0B),
    mediaKey = Color(0xFF303033),
    mediaKeyHi = Color(0xFF45454A),
    mediaKeyLo = Color(0xFF1A1A1C),
    raiseHighlight = Color.White,
    raiseShadow = Color(0xFF5C5D5A)
)

val DarkAcPalette = AcPalette(
    isDark = true,
    chassis = Color(0xFF1A1A1C),
    chassisHi = Color(0xFF2E2E32),
    chassisLo = Color(0xFF0F0F11),
    ui = Color(0xFF252528),
    ink = Color(0xFFF0F0EE),
    ink2 = Color(0xFF9B9C98),
    hairline = Color(0xFF323236),
    well = Color(0xFF080809),
    mediaKey = Color(0xFF303033),
    mediaKeyHi = Color(0xFF45454A),
    mediaKeyLo = Color(0xFF141416),
    raiseHighlight = Color(0xFF3A3A3F),
    raiseShadow = Color(0xFF000000)
)

/**
 * Aircheck design v2 color tokens (docs/DESIGN_SYSTEM.md, approved by Ryan 2026-11-27;
 * Stealth Black finish approved 2026-11-28).
 *
 * These are the only colors UI code may use. The op* colors are for thin-line graphics drawn
 * inside an [AcDisplay] only, never for app controls.
 */
object AcTokens {
    private var palette by mutableStateOf(LightAcPalette)

    val isDark: Boolean get() = palette.isDark

    fun setDark(dark: Boolean) {
        val target = if (dark) DarkAcPalette else LightAcPalette
        if (palette != target) palette = target
    }

    /** Screen background (device chassis). */
    val Chassis: Color get() = palette.chassis
    /** Panel bevel highlight. */
    val ChassisHi: Color get() = palette.chassisHi
    /** Panel bevel shade. */
    val ChassisLo: Color get() = palette.chassisLo

    /** App key and card surface. */
    val Ui: Color get() = palette.ui
    /** Always-light ink for glyphs/text on red keys, dark media keys, and black OLED displays. */
    val DisplayInk: Color = Color(0xFFF5F5F3)
    /** Text. */
    val Ink: Color get() = palette.ink
    /** Secondary text. */
    val Ink2: Color get() = palette.ink2
    /** Dividers and engraved seams. */
    val Hairline: Color get() = palette.hairline

    /** Neumorphic highlight / cast shadow endpoints. */
    val RaiseHighlight: Color get() = palette.raiseHighlight
    val RaiseShadow: Color get() = palette.raiseShadow

    /** Primary, selected, active media icon, LED on. */
    val Red: Color = Color(0xFFD71921)
    val RedHi: Color = Color(0xFFFF4A3D)
    val RedLo: Color = Color(0xFF8E0F14)

    /** Media key well. */
    val Well: Color get() = palette.well
    /** Media key body. */
    val MediaKey: Color get() = palette.mediaKey
    val MediaKeyHi: Color get() = palette.mediaKeyHi
    val MediaKeyLo: Color get() = palette.mediaKeyLo

    /** OP-1 display background. */
    val Display: Color = Color(0xFF0A0A0A)

    // Thin-line graphics inside displays only.
    val OpBlue: Color = Color(0xFF4A7FC4)
    val OpOrange: Color = Color(0xFFD08A3E)
    val OpGrey: Color = Color(0xFF9B9B9B)
    val OpRed: Color = Color(0xFFEC4A2E)
    val OpGreen: Color = Color(0xFF3FBF7F)
}
