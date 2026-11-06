package com.google.antigravity.ui.design

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration

/** The product currently has a fixed dark theme. Material3 1.3.0's modal dialog
 * derives system-bar contrast from isSystemInDarkTheme(), independently of the
 * supplied darkColorScheme. Keep that signal consistent without changing the
 * device's theme, density, locale or accessibility font scale.
 */
@Composable
internal fun FixedDarkAppearance(content: @Composable () -> Unit) {
    val configuration = Configuration(LocalConfiguration.current).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
    }
    CompositionLocalProvider(LocalConfiguration provides configuration, content = content)
}
