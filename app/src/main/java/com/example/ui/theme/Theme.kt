package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.example.data.HardwareTheme
import com.example.ui.design.AcTokens
import com.example.ui.design.AcType

/**
 * Root Aircheck theme (design v2, docs/DESIGN_SYSTEM.md): supports both the Light brushed-aluminum
 * finish and the Stealth Black anodized finish. App UI is drawn with ui/design components; this
 * Material scheme colors Material internals with the active [AcTokens] palette.
 */
@Composable
fun AircheckTheme(
    hardwareTheme: HardwareTheme = HardwareTheme.LIGHT,
    content: @Composable () -> Unit
) {
    val isDark = when (hardwareTheme) {
        HardwareTheme.LIGHT -> false
        HardwareTheme.DARK -> true
        HardwareTheme.SYSTEM -> isSystemInDarkTheme()
    }
    AcTokens.setDark(isDark)

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = AcTokens.Red,
            onPrimary = AcTokens.DisplayInk,
            primaryContainer = AcTokens.Ui,
            onPrimaryContainer = AcTokens.Ink,
            secondary = AcTokens.Ink2,
            onSecondary = AcTokens.DisplayInk,
            secondaryContainer = AcTokens.Chassis,
            onSecondaryContainer = AcTokens.Ink,
            tertiary = AcTokens.Ink2,
            onTertiary = AcTokens.DisplayInk,
            background = AcTokens.Chassis,
            onBackground = AcTokens.Ink,
            surface = AcTokens.Ui,
            onSurface = AcTokens.Ink,
            surfaceVariant = AcTokens.Chassis,
            onSurfaceVariant = AcTokens.Ink2,
            outline = AcTokens.Hairline,
            outlineVariant = AcTokens.Hairline,
            error = AcTokens.Red,
            onError = AcTokens.DisplayInk
        )
    } else {
        lightColorScheme(
            primary = AcTokens.Red,
            onPrimary = AcTokens.DisplayInk,
            primaryContainer = AcTokens.Ui,
            onPrimaryContainer = AcTokens.Ink,
            secondary = AcTokens.Ink2,
            onSecondary = AcTokens.DisplayInk,
            secondaryContainer = AcTokens.Chassis,
            onSecondaryContainer = AcTokens.Ink,
            tertiary = AcTokens.Ink2,
            onTertiary = AcTokens.DisplayInk,
            background = AcTokens.Chassis,
            onBackground = AcTokens.Ink,
            surface = AcTokens.Ui,
            onSurface = AcTokens.Ink,
            surfaceVariant = AcTokens.Chassis,
            onSurfaceVariant = AcTokens.Ink2,
            outline = AcTokens.Hairline,
            outlineVariant = AcTokens.Hairline,
            error = AcTokens.Red,
            onError = AcTokens.DisplayInk
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(
            headlineSmall = AcType.Headline,
            titleLarge = AcType.Headline,
            titleMedium = AcType.Title,
            titleSmall = AcType.Title,
            bodyLarge = AcType.Body,
            bodyMedium = AcType.BodySmall,
            bodySmall = AcType.BodySmall,
            labelLarge = AcType.Label,
            labelMedium = AcType.Label,
            labelSmall = AcType.Caption
        ),
        content = content
    )
}
