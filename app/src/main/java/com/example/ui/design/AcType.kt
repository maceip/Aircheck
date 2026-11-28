package com.example.ui.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.R

/** Bundled OFL fonts (res/font): Doto, Jost, Inter, JetBrains Mono (licenses in docs/fonts). */
object AcFonts {
    val Doto = FontFamily(
        Font(R.font.doto, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800)))
    )
    val Jost = FontFamily(
        Font(R.font.jost, FontWeight.ExtraLight, variationSettings = FontVariation.Settings(FontVariation.weight(200))),
        Font(R.font.jost, FontWeight.Light, variationSettings = FontVariation.Settings(FontVariation.weight(300)))
    )
    val Inter = FontFamily(
        Font(R.font.inter, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.inter, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
        Font(R.font.inter, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600)))
    )
    val JetBrainsMono = FontFamily(
        Font(R.font.jetbrains_mono_var, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.jetbrains_mono_var, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500)))
    )
}

/**
 * Aircheck design v2 type scale (docs/DESIGN_SYSTEM.md). No raw `fontSize =` outside these styles.
 * Styles read [AcTokens.Ink] / [AcTokens.Ink2] dynamically so Light and Stealth finishes update
 * automatically; recolor with `.copy(color = AcTokens.X)`.
 * [Caption] text is written UPPERCASE by the caller.
 */
object AcType {
    /** "01 SPARK" step header (active word in red). */
    val Step: TextStyle
        get() = TextStyle(fontFamily = AcFonts.Doto, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, letterSpacing = 0.04.em, color = AcTokens.Ink)

    /** Screen question / title. */
    val Headline: TextStyle
        get() = TextStyle(fontFamily = AcFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 29.sp, letterSpacing = (-0.01).em, color = AcTokens.Ink)

    /** Sheet titles. */
    val Title: TextStyle
        get() = TextStyle(fontFamily = AcFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 23.sp, color = AcTokens.Ink)

    /** Beat text, transcript, any content you read. */
    val Body: TextStyle
        get() = TextStyle(fontFamily = AcFonts.Inter, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp, color = AcTokens.Ink)

    /** Helper copy. */
    val BodySmall: TextStyle
        get() = TextStyle(fontFamily = AcFonts.Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, color = AcTokens.Ink2)

    /** Text on keys. */
    val Label: TextStyle
        get() = TextStyle(fontFamily = AcFonts.JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = AcTokens.Ink)

    /** Eyebrows, beat tags, timestamps (UPPERCASE). */
    val Caption: TextStyle
        get() = TextStyle(fontFamily = AcFonts.JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, letterSpacing = 0.1.em, color = AcTokens.Ink2)

    /** Timecodes and numbers inside displays (Jost 200, 15–30sp). Default 22sp; see [numeral]. */
    val Numeral: TextStyle
        get() = numeral(22.sp)

    /** [Numeral] at a size clamped to the approved 15–30sp range. */
    fun numeral(size: TextUnit): TextStyle {
        val clamped = size.value.coerceIn(15f, 30f).sp
        return TextStyle(
            fontFamily = AcFonts.Jost,
            fontWeight = FontWeight.ExtraLight,
            fontSize = clamped,
            letterSpacing = 0.02.em,
            fontFeatureSettings = "tnum",
            color = AcTokens.DisplayInk
        )
    }
}
