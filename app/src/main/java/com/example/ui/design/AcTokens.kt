package com.example.ui.design

import androidx.compose.ui.graphics.Color

/**
 * Aircheck design v2 color tokens (docs/DESIGN_SYSTEM.md, approved by Ryan 2026-11-27).
 *
 * These are the only colors UI code may use. The op* colors are for thin-line graphics drawn
 * inside an [AcDisplay] only, never for app controls.
 */
object AcTokens {
    /** Screen background (light grey device chassis). */
    val Chassis = Color(0xFFD9DAD7)
    /** Panel bevel highlight. */
    val ChassisHi = Color(0xFFF1F1EF)
    /** Panel bevel shade. */
    val ChassisLo = Color(0xFFA8A9A5)

    /** App key and card surface. */
    val Ui = Color(0xFFF5F5F3)
    /** Text. */
    val Ink = Color(0xFF141414)
    /** Secondary text. */
    val Ink2 = Color(0xFF5C5D5A)
    /** Dividers. */
    val Hairline = Color(0xFFC4C5C1)

    /** Primary, selected, active media icon, LED on. */
    val Red = Color(0xFFD71921)
    val RedHi = Color(0xFFFF4A3D)
    val RedLo = Color(0xFF8E0F14)

    /** Media key well. */
    val Well = Color(0xFF0B0B0B)
    /** Media key body. */
    val MediaKey = Color(0xFF303033)
    val MediaKeyHi = Color(0xFF45454A)
    val MediaKeyLo = Color(0xFF1A1A1C)

    /** OP-1 display background. */
    val Display = Color(0xFF0A0A0A)

    // Thin-line graphics inside displays only.
    val OpBlue = Color(0xFF4A7FC4)
    val OpOrange = Color(0xFFD08A3E)
    val OpGrey = Color(0xFF9B9B9B)
    val OpRed = Color(0xFFEC4A2E)
    val OpGreen = Color(0xFF3FBF7F)
}
