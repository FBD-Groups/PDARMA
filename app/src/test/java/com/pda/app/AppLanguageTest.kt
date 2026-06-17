package com.pda.app

import com.pda.app.ui.i18n.AppLanguage
import com.pda.app.ui.i18n.ChineseStrings
import com.pda.app.ui.i18n.EnglishStrings
import com.pda.app.ui.i18n.SpanishStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppLanguageTest {

    @Test
    fun `fromName null falls back to Chinese`() {
        assertEquals(AppLanguage.Chinese, AppLanguage.fromName(null))
    }

    @Test
    fun `fromName unknown falls back to Chinese`() {
        assertEquals(AppLanguage.Chinese, AppLanguage.fromName("garbage"))
    }

    @Test
    fun `fromName resolves persisted names`() {
        assertEquals(AppLanguage.Chinese, AppLanguage.fromName("Chinese"))
        assertEquals(AppLanguage.English, AppLanguage.fromName("English"))
        assertEquals(AppLanguage.Spanish, AppLanguage.fromName("Spanish"))
    }

    @Test
    fun `strings returns the matching implementation`() {
        assertSame(ChineseStrings, AppLanguage.Chinese.strings)
        assertSame(EnglishStrings, AppLanguage.English.strings)
        assertSame(SpanishStrings, AppLanguage.Spanish.strings)
    }
}
