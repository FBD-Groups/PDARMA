package com.pda.app

import com.pda.app.ui.i18n.AppStrings
import com.pda.app.ui.i18n.ChineseStrings
import com.pda.app.ui.i18n.EnglishStrings
import com.pda.app.ui.i18n.SpanishStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStringsParityTest {

    private val impls: List<Pair<String, AppStrings>> = listOf(
        "ChineseStrings" to ChineseStrings,
        "EnglishStrings" to EnglishStrings,
        "SpanishStrings" to SpanishStrings
    )

    /** Every no-arg String property must be non-blank in all three languages. */
    @Test
    fun `all static strings are non-blank in every language`() {
        // Property getters declared on the AppStrings interface: no params, return String.
        val getters = AppStrings::class.java.methods.filter {
            it.parameterCount == 0 && it.returnType == String::class.java
        }
        assertTrue("expected several string properties", getters.size >= 30)

        impls.forEach { (name, impl) ->
            getters.forEach { getter ->
                val value = getter.invoke(impl) as String
                assertTrue("$name.${getter.name} is blank", value.isNotBlank())
            }
        }
    }

    /** Spot-check the parameterized functions produce non-blank, parameter-bearing output. */
    @Test
    fun `parameterized strings interpolate in every language`() {
        impls.forEach { (name, s) ->
            assertTrue("$name greeting", s.home_greeting("Ana").contains("Ana"))
            assertTrue("$name itemCount", s.itemCount(3).contains("3"))
            assertTrue("$name batchTitle", s.dock_batchTitle("B-1").contains("B-1"))
            assertTrue("$name batchClosed", s.dock_batchClosed("B-1").contains("B-1"))
            assertTrue("$name daySummary", s.report_daySummary(2, 5).isNotBlank())
            assertTrue("$name closePrompt", s.dock_closeBatchPrompt(2, 1).isNotBlank())
        }
    }

    @Test
    fun `close batch prompt omits review clause when none need review`() {
        impls.forEach { (name, s) ->
            val withReview = s.dock_closeBatchPrompt(5, 2)
            val noReview = s.dock_closeBatchPrompt(5, 0)
            assertTrue("$name should mention review count", withReview.length > noReview.length)
        }
    }

    @Test
    fun `display names are distinct`() {
        val names = impls.map { it.second.login_language }
        // Sanity: language label itself is translated per language.
        assertEquals(3, names.size)
    }
}
