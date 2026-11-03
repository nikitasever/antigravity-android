package com.google.antigravity

import com.google.antigravity.model.ComposerToken
import com.google.antigravity.model.ComposerTokensState
import com.google.antigravity.model.ComposerTokenType
import com.google.antigravity.model.SlashMentionSuggestionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Фишки упоминаний композитора «как у них» (их composer-токены): INSERTED при
 * добавлении без дубликатов, REMOVED при удалении, очистка после отправки и
 * композиция промпта (тексты фишек ДО текста черновика). Типы фишек — наши
 * честные категории по categoryBadge предложений (их enum
 * SYSTEM_HINT/GIZMO/SKILL/TARGETED_REPLY не воспроизводится дословно —
 * «требует проверки»).
 */
class ComposerTokensTest {

    private fun token(id: String, insertText: String = "@$id ") = ComposerToken(
        id = id,
        label = id,
        insertText = insertText,
        typeLabel = "Прочее"
    )

    // ---------- Добавление / удаление / очистка ----------

    @Test
    fun `добавление фишки — INSERTED и порядок сохраняется`() {
        var state = ComposerTokensState()
        state = state.onAdded(token("a"))
        state = state.onAdded(token("b"))
        assertEquals(2, state.tokens.size)
        assertEquals("a", state.tokens[0].id)
        assertEquals("b", state.tokens[1].id)
    }

    @Test
    fun `дубликат по id — фишка не добавляется`() {
        var state = ComposerTokensState().onAdded(token("a"))
        state = state.onAdded(token("a"))
        assertEquals(1, state.tokens.size)
    }

    @Test
    fun `удаление — REMOVED и остальные фишки остаются`() {
        var state = ComposerTokensState().onAdded(token("a")).onAdded(token("b")).onAdded(token("c"))
        state = state.onRemoved("b")
        assertEquals(2, state.tokens.size)
        assertEquals("a", state.tokens[0].id)
        assertEquals("c", state.tokens[1].id)
    }

    @Test
    fun `очистка — все фишки удаляются`() {
        val state = ComposerTokensState().onAdded(token("a")).onAdded(token("b")).onCleared()
        assertTrue(state.tokens.isEmpty())
    }

    // ---------- Типы по categoryBadge ----------

    @Test
    fun `типы по categoryBadge — все восемь категорий`() {
        assertEquals(ComposerTokenType.SUBAGENT, ComposerTokenType.fromCategoryBadge("Субагент"))
        assertEquals(ComposerTokenType.SUBAGENT, ComposerTokenType.fromCategoryBadge("ЛИДЕР"))
        assertEquals(ComposerTokenType.SKILL, ComposerTokenType.fromCategoryBadge("Скилл"))
        assertEquals(ComposerTokenType.SKILL, ComposerTokenType.fromCategoryBadge("Скилл ядра"))
        assertEquals(ComposerTokenType.MCP, ComposerTokenType.fromCategoryBadge("MCP"))
        assertEquals(ComposerTokenType.FILE, ComposerTokenType.fromCategoryBadge("Файл"))
        assertEquals(ComposerTokenType.DRIVE, ComposerTokenType.fromCategoryBadge("Google Диск"))
        assertEquals(ComposerTokenType.PLUGIN, ComposerTokenType.fromCategoryBadge("Плагин"))
        assertEquals(ComposerTokenType.CONTEXT, ComposerTokenType.fromCategoryBadge("Контекст"))
        // Неизвестная категория — «Прочее», ничего не выдумывается.
        assertEquals(ComposerTokenType.OTHER, ComposerTokenType.fromCategoryBadge("Песочница"))
        assertEquals(ComposerTokenType.OTHER, ComposerTokenType.fromCategoryBadge(""))
        // Честные подписи типов.
        assertEquals("Субагент", ComposerTokenType.SUBAGENT.label)
        assertEquals("Навык", ComposerTokenType.SKILL.label)
        assertEquals("MCP", ComposerTokenType.MCP.label)
        assertEquals("Файл", ComposerTokenType.FILE.label)
        assertEquals("Google Диск", ComposerTokenType.DRIVE.label)
        assertEquals("Плагин", ComposerTokenType.PLUGIN.label)
        assertEquals("Контекст", ComposerTokenType.CONTEXT.label)
        assertEquals("Прочее", ComposerTokenType.OTHER.label)
    }

    @Test
    fun `фишка из предложения — строится из SlashMentionSuggestionItem`() {
        val item = SlashMentionSuggestionItem(
            id = "swarm_a1",
            trigger = "@agent",
            insertText = "@agent ",
            subtitle = "Субагент • Разработчик",
            categoryBadge = "Субагент",
            iconName = ""
        )
        val token = ComposerToken.fromSuggestion(item)
        assertEquals("swarm_a1", token.id)
        assertEquals("@agent", token.label)
        assertEquals("@agent ", token.insertText)
        assertEquals("Субагент", token.typeLabel)
    }

    // ---------- Композиция промпта ----------

    @Test
    fun `композиция промпта — токены идут перед черновиком`() {
        val state = ComposerTokensState()
            .onAdded(token("a", insertText = "@a "))
            .onAdded(token("b", insertText = "@b "))
        assertEquals("@a @b черновик текст", state.composePrompt("черновик текст"))
    }

    @Test
    fun `пустой черновик и только токены — сообщение уходит`() {
        val state = ComposerTokensState().onAdded(token("a")).onAdded(token("b"))
        assertEquals("@a @b", state.composePrompt(""))
        assertTrue(state.hasContent(""))
        assertTrue(state.hasContent("   "))
    }

    @Test
    fun `порядок фишек — insertText не переставляется в промпте`() {
        val state = ComposerTokensState()
            .onAdded(token("z", insertText = "@z "))
            .onAdded(token("m", insertText = "@m "))
            .onAdded(token("a", insertText = "@a "))
        assertEquals("@z @m @a текст", state.composePrompt("текст"))
    }

    @Test
    fun `пустой промпт без фишек — отправлять нечего`() {
        val state = ComposerTokensState()
        assertEquals("", state.composePrompt(""))
        assertEquals("", state.composePrompt("   "))
        assertFalse(state.hasContent(""))
    }
}
