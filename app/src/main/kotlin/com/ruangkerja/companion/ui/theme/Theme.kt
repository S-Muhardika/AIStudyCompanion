package com.ruangkerja.companion.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val LocalIsDark    = compositionLocalOf { true }
val LocalAppAccent = compositionLocalOf { AccentChoice.INDIGO }

enum class ThemeMode { LIGHT, DARK, AUTO }

@Composable
fun AppTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    accent: AccentChoice = AccentChoice.INDIGO,
    language: AppLanguage = AppLanguage.ID,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK  -> true
        ThemeMode.AUTO  -> systemDark
    }
    val accentColor     = if (isDark) accent.dark else accent.light
    val accentSoftColor = accentColor.copy(alpha = 0.15f)

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary            = accentColor,
            // [UBAH, 21 Okt] Dulu 0xFF1C1917 (stone-900, warm) — disamain ke neutralisasi
            // DarkBgCard di Color.kt (lihat catatan di sana), dipakai di sini juga biar teks/ikon
            // di atas tombol primary & state error gak ninggalin rona hangat sendirian.
            onPrimary          = Color(0xFF191919),
            primaryContainer   = accentSoftColor,
            background         = DarkBg,
            onBackground       = DarkText,
            surface            = DarkBgCard,
            onSurface          = DarkText,
            surfaceVariant     = DarkBgAccent,
            onSurfaceVariant   = DarkTextMuted,
            outline            = DarkBorder,
            outlineVariant     = DarkBorder.copy(alpha = 0.5f),
            error              = DangerDark,
            onError            = Color(0xFF191919),
            scrim              = Color(0xFF000000).copy(alpha = 0.5f),
        )
    } else {
        lightColorScheme(
            primary            = accentColor,
            onPrimary          = Color.White,
            primaryContainer   = accentSoftColor,
            background         = LightBg,
            onBackground       = LightText,
            surface            = LightBgCard,
            onSurface          = LightText,
            surfaceVariant     = LightBgMuted,
            onSurfaceVariant   = LightTextMuted,
            outline            = LightBorder,
            outlineVariant     = LightBorder.copy(alpha = 0.6f),
            error              = Danger,
            onError            = Color.White,
            scrim              = Color(0xFF000000).copy(alpha = 0.4f),
        )
    }

    val view = LocalView.current
    SideEffect {
        val window = (view.context as Activity).window
        window.statusBarColor       = colorScheme.background.toArgb()
        window.navigationBarColor   = colorScheme.surface.toArgb()
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars     = !isDark
        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !isDark
    }

    CompositionLocalProvider(
        LocalIsDark       provides isDark,
        LocalAppAccent    provides accent,
        LocalAppLanguage  provides language
    ) {
        MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
    }
}

// ── Shorthand aliases — otomatis switch light/dark ────────
val AppBg          @Composable get() = MaterialTheme.colorScheme.background
val AppBgCard      @Composable get() = MaterialTheme.colorScheme.surface
val AppBgMuted     @Composable get() = MaterialTheme.colorScheme.surfaceVariant
val AppText        @Composable get() = MaterialTheme.colorScheme.onBackground
val AppTextMuted   @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val AppTextDim     @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
val AppBorder      @Composable get() = MaterialTheme.colorScheme.outline
val AppAccent      @Composable get() = MaterialTheme.colorScheme.primary
val AppAccentSoft  @Composable get() = MaterialTheme.colorScheme.primaryContainer
val AppIsDark      @Composable get() = LocalIsDark.current
