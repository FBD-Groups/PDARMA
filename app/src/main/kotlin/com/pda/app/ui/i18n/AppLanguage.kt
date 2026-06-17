package com.pda.app.ui.i18n

import java.util.Locale

/** Supported in-app languages. [persistedName] is stored in DataStore; [displayName] is the selector label. */
enum class AppLanguage(val persistedName: String, val displayName: String) {
    Chinese("Chinese", "中文"),
    English("English", "English"),
    Spanish("Spanish", "Español");

    /** Computed (not a constructor arg) to avoid enum/object init-order concerns. */
    val strings: AppStrings
        get() = when (this) {
            Chinese -> ChineseStrings
            English -> EnglishStrings
            Spanish -> SpanishStrings
        }

    /** Locale for date/number formatting (e.g. Receive Report day headers). */
    val locale: Locale
        get() = when (this) {
            Chinese -> Locale.CHINESE
            English -> Locale.ENGLISH
            Spanish -> Locale("es")
        }

    companion object {
        /** null/unknown → [Chinese] (current app default). */
        fun fromName(name: String?): AppLanguage =
            entries.firstOrNull { it.persistedName == name } ?: Chinese
    }
}
