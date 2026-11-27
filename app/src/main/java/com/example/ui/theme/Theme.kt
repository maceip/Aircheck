package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.example.ui.design.AcTokens
import com.example.ui.design.AcType

/**
 * Root Aircheck theme (design v2, docs/DESIGN_SYSTEM.md): the light chassis device. App UI is drawn
 * with ui/design components; this Material scheme only colors Material internals that still render
 * (for example the Jetpack A2UI overlay surface) with the same tokens.
 */
@Composable
fun AircheckTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = AcTokens.Red,
            onPrimary = AcTokens.Ui,
            primaryContainer = AcTokens.Ui,
            onPrimaryContainer = AcTokens.Ink,
            secondary = AcTokens.Ink2,
            onSecondary = AcTokens.Ui,
            secondaryContainer = AcTokens.Chassis,
            onSecondaryContainer = AcTokens.Ink,
            tertiary = AcTokens.Ink2,
            onTertiary = AcTokens.Ui,
            background = AcTokens.Chassis,
            onBackground = AcTokens.Ink,
            surface = AcTokens.Ui,
            onSurface = AcTokens.Ink,
            surfaceVariant = AcTokens.Chassis,
            onSurfaceVariant = AcTokens.Ink2,
            outline = AcTokens.Hairline,
            outlineVariant = AcTokens.Hairline,
            error = AcTokens.Red,
            onError = AcTokens.Ui
        ),
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
