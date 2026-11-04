package com.google.antigravity

import androidx.compose.ui.graphics.Color
import com.google.antigravity.ui.design.DesignColors
import com.google.antigravity.ui.design.DesignType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дизайн-токены «как у них» (DesignSystem.kt): значения равны задокументированным
 * фактам референсов — нейтрали ChatGPT (декомпиляция 1.2026.293: #0D0D0D /
 * #212121 / #303030 / #414141, текст #F3F3F3 / #C4C3CB / #89888D, акценты
 * #3566F0 / #53B559 / #FFCC38 / #FF8066) и тёплая палитра Claude
 * (colors.xml 1.261029.20: #FAF9F5 / #D97757 / #7FA87F). Кегли DesignType —
 * в порядке возрастания с равенством BODY/SECTION (оба 14sp по факту).
 */
class DesignTokensTest {

    /** Сверка токена с ARGB-фактом референса (тот же конструктор Color). */
    private fun assertColorEquals(argb: Long, actual: Color) {
        assertEquals(Color(argb), actual)
    }

    // ---------- Нейтрали ChatGPT (декодированы из декомпиляции) ----------

    @Test
    fun `DesignColors — поверхности объединённого дизайна по живым референсам`() {
        assertColorEquals(0xFF151515, DesignColors.Bg)
        assertColorEquals(0xFF20201E, DesignColors.Surface)
        assertColorEquals(0xFF303030, DesignColors.SurfaceHigh)
        assertColorEquals(0xFF414141, DesignColors.Border)
    }

    @Test
    fun `DesignColors — текстовые токены равны декодированным значениям`() {
        assertColorEquals(0xFFF3F3F3, DesignColors.TextPrimary)
        assertColorEquals(0xFFC4C3CB, DesignColors.TextSecondary)
        assertColorEquals(0xFF89888D, DesignColors.TextSubtle)
    }

    @Test
    fun `DesignColors — основной синий и семантические акценты`() {
        assertColorEquals(0xFF3566EF, DesignColors.AccentBlue)
        assertColorEquals(0xFF53B559, DesignColors.AccentGreen)
        assertColorEquals(0xFFFFCC38, DesignColors.AccentAmber)
        assertColorEquals(0xFFFF8066, DesignColors.AccentCoral)
    }

    @Test
    fun `DesignColors — тёплая палитра Claude равна значениям их colors xml`() {
        assertColorEquals(0xFFFAF9F5, DesignColors.ClaudePaper)
        assertColorEquals(0xFFD97757, DesignColors.ClaudeCoral)
        assertColorEquals(0xFF7FA87F, DesignColors.ClaudeSage)
    }

    // ---------- Честная граница error-red ----------

    @Test
    fun `DesignColors — error-red референса не декодирован — используется их коралл`() {
        // Их коралл #FF8066 честно занят под ошибки/удаления; розово-маджентовый
        // #DD6ADC зафиксирован как отдельный акцент, не как error-red.
        assertColorEquals(0xFFFF8066, DesignColors.AccentCoral)
        assertColorEquals(0xFFDD6ADC, DesignColors.AccentRed)
    }

    // ---------- Кегли DesignType ----------

    @Test
    fun `DesignType — значения кеглей равны задокументированным фактам`() {
        assertEquals(18f, DesignType.TITLE_SP, 0f)
        assertEquals(14f, DesignType.SECTION_SP, 0f)
        assertEquals(14f, DesignType.BODY_SP, 0f)
        assertEquals(12f, DesignType.CAPTION_SP, 0f)
        assertEquals(11f, DesignType.MICRO_SP, 0f)
    }

    @Test
    fun `DesignType — кегли в порядке возрастания от микро к заголовку`() {
        assertTrue(DesignType.MICRO_SP < DesignType.CAPTION_SP)
        assertTrue(DesignType.CAPTION_SP < DesignType.BODY_SP)
        // BODY и SECTION равны (оба 14sp по факту референсов) — не дробим зря.
        assertEquals(DesignType.BODY_SP, DesignType.SECTION_SP, 0f)
        assertTrue(DesignType.SECTION_SP < DesignType.TITLE_SP)
    }
}
