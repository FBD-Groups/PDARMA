package com.pda.app.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Provides the active [AppStrings] down the Compose tree. `static` because the value changes
 * rarely; when it does, the whole subtree recomposes — exactly the desired language-switch behavior.
 * Default [ChineseStrings] matches the app's default language.
 */
val LocalAppStrings = staticCompositionLocalOf<AppStrings> { ChineseStrings }

/** The active language, so screens can drive locale-aware formatting and selector highlight. */
val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.Chinese }
