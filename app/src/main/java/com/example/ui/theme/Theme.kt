package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.BrandChannel

// =============================================================================
// 1. AIRCHECK 'HYBRID STUDIO DECK' COLOR TOKENS
// =============================================================================

/**
 * Authoritative color palette for the Aircheck Hybrid Studio Deck design system.
 * Pairs pitch-black OLED broadcast canvases with tactile metallic hardware controls
 * and dual-channel broadcast conglomerate accents (Acid Volt Lime & Broadcast Cobalt).
 */
object AircheckColors {
    // Pure OLED Broadcast Terminal Canvas
    val OledVoid = Color(0xFF000000)           // Breathable #000000 writing canvas & unobstructed video stage
    val ConsoleObsidian = Color(0xFF0A0C10)    // Quiet cards, on-demand beat trays, stage monitor frame
    val ConsoleElevated = Color(0xFF12151C)    // Raised surface wells, active inline controls, diff trays
    val GraphiteHairline = Color(0xFF232730)   // Crisp 1.dp structural borders and dividers
    val GraphiteSubtle = Color(0xFF1A1D24)     // Low-emphasis recessed outline variant

    // Tactile Glossy Hardware Switcher Materials
    val HardwarePearl = Color(0xFFF2F5F9)      // Top specular highlight on tactile switcher keys & dock chassis
    val HardwareAlloy = Color(0xFFD5DBE3)      // Mid-body brushed polymer/alloy gradient on tactile controls
    val HardwareShadow = Color(0xFF8E96A4)     // Lower bevel depth shadow on hardware keys
    val HardwareWell = Color(0xFF090B0E)       // Recessed pitch-black OLED icon screen inside hardware keys

    // Dual-Channel Global Broadcast Conglomerate Accents
    val AcidVoltLime = Color(0xFFB8FF00)       // Channel A primary CTA (#000000 ink), ✨ shimmer, live accent
    val AcidVoltLimeDim = Color(0xFF243300)    // Channel A tinted container surface
    val BroadcastCobalt = Color(0xFF0088FF)    // Channel B twin accent, active beat border, dock LED glow
    val BroadcastCobaltDim = Color(0xFF001E3D) // Channel B tinted container surface
    val TelemetryCyan = Color(0xFF18D4F5)      // Real RIFF PCM waveform bars & playback scrubber

    // Semantic Broadcast Signal Tokens
    val HazardSignalOrange = Color(0xFFFF451A) // Diagonal 45° CUT beat hazard striping & trim warnings
    val EvidenceAmber = Color(0xFFF5B700)      // Cited evidence pill & runtime caution indicators
    val LiveTallyGreen = Color(0xFF00E676)     // EXPORTED ✓ state, verified citations, green hardware LED pips
    val OnAirCrimson = Color(0xFFFF1744)       // Active mic recording dot, red LED pips, actionable error banners
    val StandbyCobalt = Color(0xFF2979FF)      // Hardware standby LED tally state

    // High-Contrast Broadcast Ink
    val StudioCrisp = Color(0xFFF4F6FA)        // Sentence-case script prose & primary headlines
    val TelemetryMuted = Color(0xFF8A93A2)     // [05s] tabular durations & quiet placeholders
    val PureBlackInk = Color(0xFF000000)       // Maximum contrast ink on Acid Volt Lime CTAs
}

// Top-level token aliases for ergonomic direct imports across Composables
val OledVoid: Color = AircheckColors.OledVoid
val ConsoleObsidian: Color = AircheckColors.ConsoleObsidian
val ConsoleElevated: Color = AircheckColors.ConsoleElevated
val GraphiteHairline: Color = AircheckColors.GraphiteHairline
val HardwarePearl: Color = AircheckColors.HardwarePearl
val HardwareAlloy: Color = AircheckColors.HardwareAlloy
val HardwareShadow: Color = AircheckColors.HardwareShadow
val HardwareWell: Color = AircheckColors.HardwareWell
val AcidVoltLime: Color = AircheckColors.AcidVoltLime
val AcidVoltLimeDim: Color = AircheckColors.AcidVoltLimeDim
val BroadcastCobalt: Color = AircheckColors.BroadcastCobalt
val BroadcastCobaltDim: Color = AircheckColors.BroadcastCobaltDim
val TelemetryCyan: Color = AircheckColors.TelemetryCyan
val HazardSignalOrange: Color = AircheckColors.HazardSignalOrange
val EvidenceAmber: Color = AircheckColors.EvidenceAmber
val LiveTallyGreen: Color = AircheckColors.LiveTallyGreen
val OnAirCrimson: Color = AircheckColors.OnAirCrimson
val StandbyCobalt: Color = AircheckColors.StandbyCobalt
val StudioCrisp: Color = AircheckColors.StudioCrisp
val TelemetryMuted: Color = AircheckColors.TelemetryMuted
val PureBlackInk: Color = AircheckColors.PureBlackInk

// =============================================================================
// 2. BUNDLED TYPOGRAPHY RAMPS (SPACE GROTESK + JETBRAINS MONO)
// =============================================================================

/**
 * Primary editorial & UI typeface bundled statically in res/font/space_grotesk.ttf.
 * Used across screen titles, buttons, section headings, sentence-case script beats, and chyrons.
 */
val SpaceGroteskFontFamily = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal),
    Font(R.font.space_grotesk, FontWeight.Medium),
    Font(R.font.space_grotesk, FontWeight.SemiBold),
    Font(R.font.space_grotesk, FontWeight.Bold)
)

/**
 * Numeric telemetry typeface bundled statically in res/font/jetbrains_mono.ttf.
 * Strictly reserved for tabular timecodes (00:28), [05s] beat duration counters,
 * pitch readouts (118 Hz), and hex/token readouts in System Deck—never for prose.
 */
val JetBrainsMonoFontFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium),
    Font(R.font.jetbrains_mono, FontWeight.Bold)
)

/**
 * Complete Material 3 typography ramp calibrated for pitch-black OLED legibility.
 */
val AircheckTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.75).sp
    ),
    displayMedium = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.6).sp
    ),
    displaySmall = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.35).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.25).sp
    ),
    titleLarge = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleSmall = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp
    ),
    // Editable sentence-case script beats: 16.sp / 24.sp line height per architectural blueprint
    bodyLarge = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    // Tabular monospace micro-telemetry counters
    labelSmall = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontFeatureSettings = "tnum"
    )
)

/**
 * Dedicated tabular monospace styles for timecodes, beat durations, and System Deck hex readouts.
 */
object AircheckMonoTypography {
    val timecodeLarge = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = "tnum"
    )
    val timecode = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum"
    )
    val hexToken = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontFeatureSettings = "tnum"
    )
}

val TelemetryTimecodeStyle: TextStyle = AircheckMonoTypography.timecode

// =============================================================================
// 3. SHAPE, SPATIAL RHYTHM & HARDWARE PHYSICS TOKENS
// =============================================================================

val AircheckShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),     // Uniform StudioCard corner radius
    large = RoundedCornerShape(12.dp),     // HardwareKeyButton metallic bezel radius
    extraLarge = RoundedCornerShape(16.dp) // Glossy StudioDockBar pill chassis radius
)

/**
 * Cross-screen structural uniformity tokens from the Aircheck blueprint:
 * 52.dp Top Bar -> weight(1f) Canvas (16.dp gutters) -> 50.dp CTA + Hardware Dock.
 */
@Immutable
data class AircheckDimensions(
    val topBarHeight: Dp = 52.dp,
    val primaryCtaHeight: Dp = 50.dp,
    val screenGutter: Dp = 16.dp,
    val verticalRhythm: Dp = 12.dp,
    val cardCornerRadius: Dp = 8.dp,
    val cardPadding: Dp = 14.dp,
    val hairlineBorder: Dp = 1.dp,
    val minTouchTarget: Dp = 48.dp,
    val hardwareLedSize: Dp = 6.dp,
    val hardwarePressScale: Float = 0.96f
)

// =============================================================================
// 4. EXTENDED SEMANTIC TOKENS & DUAL-CHANNEL COLOR SCHEMES
// =============================================================================

@Immutable
data class AircheckExtendedTokens(
    val channel: BrandChannel = BrandChannel.VOLT_LIME,
    val channelAccent: Color = AircheckColors.AcidVoltLime,
    val channelAccentInk: Color = AircheckColors.PureBlackInk,
    val channelAccentContainer: Color = AircheckColors.AcidVoltLimeDim,
    val twinAccent: Color = AircheckColors.BroadcastCobalt,
    val waveformColor: Color = AircheckColors.TelemetryCyan,
    val waveformCyan: Color = AircheckColors.TelemetryCyan,
    val hazardCut: Color = AircheckColors.HazardSignalOrange,
    val hazardOrange: Color = AircheckColors.HazardSignalOrange,
    val evidenceAmber: Color = AircheckColors.EvidenceAmber,
    val statusLive: Color = AircheckColors.LiveTallyGreen,
    val statusRec: Color = AircheckColors.OnAirCrimson,
    val statusStandby: Color = AircheckColors.StandbyCobalt,
    val hardwareTop: Color = AircheckColors.HardwarePearl,
    val hardwareBase: Color = AircheckColors.HardwareAlloy,
    val hardwareShadow: Color = AircheckColors.HardwareShadow,
    val hardwareWell: Color = AircheckColors.HardwareWell,
    val hairline: Color = AircheckColors.GraphiteHairline,
    val surfaceElevated: Color = AircheckColors.ConsoleElevated
)

val LocalAircheckTokens = staticCompositionLocalOf { AircheckExtendedTokens() }
val LocalAircheckDimensions = staticCompositionLocalOf { AircheckDimensions() }

/**
 * Convenience accessor matching M3 idioms for Hybrid Studio Deck tokens and dimensions.
 */
object AircheckThemeTokens {
    val extended: AircheckExtendedTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalAircheckTokens.current

    val dimensions: AircheckDimensions
        @Composable
        @ReadOnlyComposable
        get() = LocalAircheckDimensions.current
}

/**
 * Builds the Material 3 OLED darkColorScheme for Channel A (Acid Volt Lime)
 * or Channel B (Broadcast Cobalt). Dynamic wallpaper colors are intentionally disabled
 * so the pitch-black #000000 broadcast monitor calibration stays exact on every device.
 */
fun aircheckColorSchemeFor(channel: BrandChannel): ColorScheme {
    val isVolt = channel == BrandChannel.VOLT_LIME
    val primaryColor = if (isVolt) AircheckColors.AcidVoltLime else AircheckColors.BroadcastCobalt
    val onPrimaryColor = if (isVolt) AircheckColors.PureBlackInk else AircheckColors.StudioCrisp
    val primaryContainer = if (isVolt) AircheckColors.AcidVoltLimeDim else AircheckColors.BroadcastCobaltDim
    val secondaryColor = if (isVolt) AircheckColors.BroadcastCobalt else AircheckColors.AcidVoltLime
    val onSecondaryColor = if (isVolt) AircheckColors.StudioCrisp else AircheckColors.PureBlackInk
    val secondaryContainer = if (isVolt) AircheckColors.BroadcastCobaltDim else AircheckColors.AcidVoltLimeDim

    return darkColorScheme(
        primary = primaryColor,
        onPrimary = onPrimaryColor,
        primaryContainer = primaryContainer,
        onPrimaryContainer = primaryColor,
        secondary = secondaryColor,
        onSecondary = onSecondaryColor,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = secondaryColor,
        tertiary = AircheckColors.EvidenceAmber,
        onTertiary = AircheckColors.PureBlackInk,
        background = AircheckColors.OledVoid,
        onBackground = AircheckColors.StudioCrisp,
        surface = AircheckColors.ConsoleObsidian,
        onSurface = AircheckColors.StudioCrisp,
        surfaceVariant = AircheckColors.ConsoleElevated,
        onSurfaceVariant = AircheckColors.TelemetryMuted,
        outline = AircheckColors.GraphiteHairline,
        outlineVariant = AircheckColors.GraphiteSubtle,
        error = AircheckColors.OnAirCrimson,
        onError = AircheckColors.StudioCrisp
    )
}

/**
 * Root Aircheck 'Hybrid Studio Deck' theme wrapper providing M3 colorScheme,
 * Space Grotesk / JetBrains Mono typography, 8.dp studio shapes, extended hardware tokens,
 * and cross-screen structural dimensions.
 */
@Composable
fun AircheckTheme(
    channel: BrandChannel = BrandChannel.VOLT_LIME,
    content: @Composable () -> Unit
) {
    val isVolt = channel == BrandChannel.VOLT_LIME
    val colorScheme = aircheckColorSchemeFor(channel)

    val extendedTokens = AircheckExtendedTokens(
        channel = channel,
        channelAccent = colorScheme.primary,
        channelAccentInk = colorScheme.onPrimary,
        channelAccentContainer = colorScheme.primaryContainer,
        twinAccent = colorScheme.secondary,
        waveformColor = if (isVolt) AircheckColors.TelemetryCyan else AircheckColors.AcidVoltLime,
        waveformCyan = AircheckColors.TelemetryCyan
    )

    CompositionLocalProvider(
        LocalAircheckTokens provides extendedTokens,
        LocalAircheckDimensions provides AircheckDimensions()
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AircheckTypography,
            shapes = AircheckShapes,
            content = content
        )
    }
}
