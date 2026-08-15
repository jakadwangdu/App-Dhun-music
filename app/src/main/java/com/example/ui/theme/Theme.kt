package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = MusicVioletPrimary,
    onPrimary = Color.White,
    secondary = MusicCyanSecondary,
    onSecondary = Color.Black,
    tertiary = MusicPinkTertiary,
    onTertiary = Color.White,
    background = MusicDarkBackground,
    onBackground = Color(0xFFF1F5F9),
    surface = MusicDarkSurface,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = MusicDarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFCBD5E1),
    surfaceContainer = MusicDarkSurfaceContainer
  )

private val LightColorScheme =
  lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = MusicLightBackground,
    surface = MusicLightSurface,
    surfaceVariant = MusicLightSurfaceVariant
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
