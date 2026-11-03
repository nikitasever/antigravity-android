package com.google.antigravity

import com.google.antigravity.model.SkillDetailInfo
import com.google.antigravity.model.SkillEditState
import com.google.antigravity.model.skillEditErrorLabel
import com.google.antigravity.network.parseSkillEditResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Редактирование и удаление навыков «как у них» (Claude: Edit skill / Skill
 * name / Delete skill): разбор ответов update/remove моста и переходы
 * состояния редактора. Данные только из ответов моста, отсутствующие поля —
 * «не записано», отказ никогда не превращается в выдуманный успех, а
 * skill_name_taken не закрывает редактор.
 */
class SkillEditTest {

    // ---------- Разбор ответов update/remove моста ----------

    @Test
    fun `update — разбор успешного ответа со свежим списком навыков`() {
        val result = parseSkillEditResult(
            JSONObject(
                """{"ok":true,"message":"Навык обновлён",
                   "skill":{"id":"kotlin-check","name":"Новое имя","path":"/skills/kotlin-check"},
                   "skills":[{"id":"kotlin-check","name":"Новое имя","description":"Проверка",
                              "enabled":true,"path":"/skills/kotlin-check"}]}"""
            )
        )
        assertTrue(result.ok)
        assertEquals("Навык обновлён", result.message)
        assertNull(result.error)
        assertNull(result.errorCode)
        assertEquals(1, result.skills?.size ?: -1)
        assertEquals("Новое имя", result.skills?.get(0)?.name)
    }

    @Test
    fun `update — отказ skill_name_taken читается с кодом и текстом`() {
        val result = parseSkillEditResult(
            JSONObject(
                """{"ok":false,"error":"Скилл с именем «Занятое имя» уже существует",
                   "code":"skill_name_taken"}"""
            )
        )
        assertFalse(result.ok)
        assertEquals("skill_name_taken", result.errorCode)
        assertEquals("Скилл с именем «Занятое имя» уже существует", result.error)
        // Ярлык по конвенции проекта — «Навык с именем «X» уже существует.».
        assertEquals(
            "Навык с именем «Занятое имя» уже существует.",
            skillEditErrorLabel(result, "Занятое имя")
        )
        assertNull(result.skills)
    }

    @Test
    fun `remove — отказ skill_read_only для встроенного навыка`() {
        val result = parseSkillEditResult(
            JSONObject(
                """{"ok":false,"code":"skill_read_only",
                   "error":"Встроенные навыки доступны только для чтения — редактирование выполняется в источнике."}"""
            )
        )
        assertFalse(result.ok)
        assertEquals("skill_read_only", result.errorCode)
        // Для неизвестных кодов текст моста показывается как есть, без маскировки.
        assertEquals(
            "Встроенные навыки доступны только для чтения — редактирование выполняется в источнике.",
            skillEditErrorLabel(result, "core-review")
        )
    }

    @Test
    fun `разбор — отсутствующие поля не выдумываются`() {
        val result = parseSkillEditResult(JSONObject("""{"error":"Скилл не найден: x"}"""))
        // ok без поля не выдумывается — это не успех.
        assertFalse(result.ok)
        assertEquals("", result.message)
        assertNull(result.errorCode)
        assertEquals("Скилл не найден: x", result.error)
        assertNull(result.skills)
    }

    @Test
    fun `разбор — пустой список skills остаётся пустым и без списка остаётся null`() {
        val withList = parseSkillEditResult(
            JSONObject("""{"ok":true,"message":"Навык удалён","skills":[]}""")
        )
        assertTrue(withList.skills?.isEmpty() == true)
        val withoutList = parseSkillEditResult(
            JSONObject("""{"ok":true,"message":"Навык удалён"}""")
        )
        assertNull(withoutList.skills)
    }

    // ---------- Переходы состояния редактора (как выполняет AntigravityViewModel) ----------

    @Test
    fun `редактор — загрузка и открытие с черновиками имени и полного текста`() {
        var state = SkillEditState(skillId = "kotlin-check")
        state = state.onLoading()
        assertTrue(state.loading)
        assertFalse(state.editorOpen)
        state = state.onLoaded(
            SkillDetailInfo(
                id = "kotlin-check",
                name = "kotlin-check",
                source = "~/.config/skills",
                content = "---\nname: kotlin-check\n---\nИнструкции"
            )
        )
        assertFalse(state.loading)
        assertTrue(state.editorOpen)
        assertEquals("kotlin-check", state.nameDraft)
        assertEquals("---\nname: kotlin-check\n---\nИнструкции", state.contentDraft)
        assertEquals("~/.config/skills", state.source)
    }

    @Test
    fun `сохранение — имя и содержимое правятся в черновике и подтверждаются`() {
        var state = SkillEditState(skillId = "kotlin-check").onLoading().onLoaded(
            SkillDetailInfo(id = "kotlin-check", name = "kotlin-check", content = "старый текст")
        )
        state = state.onNameChanged("Новое имя")
        state = state.onContentChanged("Новый текст")
        assertEquals("Новое имя", state.nameDraft)
        assertEquals("Новый текст", state.contentDraft)
        state = state.onSaving()
        assertTrue(state.saving)
        // На время сохранения кнопки заблокированы.
        assertFalse(state.canSave)
        state = state.onSaved()
        assertFalse(state.editorOpen)
        assertFalse(state.saving)
        assertEquals("", state.error)
    }

    @Test
    fun `отказ skill_name_taken — редактор не закрывается и имя уходит в ярлык`() {
        var state = SkillEditState(skillId = "kotlin-check").onLoading().onLoaded(
            SkillDetailInfo(id = "kotlin-check", name = "kotlin-check", content = "текст")
        )
        state = state.onNameChanged("Занятое имя")
        state = state.onSaving()
        val result = parseSkillEditResult(
            JSONObject(
                """{"ok":false,"error":"Скилл с именем «Занятое имя» уже существует",
                   "code":"skill_name_taken"}"""
            )
        )
        state = state.onSaveError(result, attemptedName = "Занятое имя")
        // Редактор НЕ закрывается — можно поправить имя и повторить.
        assertTrue(state.editorOpen)
        assertFalse(state.saving)
        assertEquals("Навык с именем «Занятое имя» уже существует.", state.error)
        assertEquals("skill_name_taken", state.errorCode)
        assertEquals("Занятое имя", state.nameDraft)
    }

    @Test
    fun `builtin — только чтение и сохранение недоступно`() {
        val state = SkillEditState(skillId = "core-review").onLoading().onLoaded(
            SkillDetailInfo(
                id = "core-review",
                name = "core-review",
                content = "текст",
                source = "builtin"
            )
        )
        assertTrue(state.readOnly)
        assertFalse(state.canSave)
        assertEquals("встроенный навык — только чтение", state.saveBlockReason)
    }

    @Test
    fun `пустое содержимое — сохранение отклоняется с честной причиной`() {
        var state = SkillEditState(skillId = "kotlin-check").onLoading().onLoaded(
            SkillDetailInfo(id = "kotlin-check", name = "kotlin-check", content = "   ")
        )
        assertTrue(state.contentDraft.isBlank())
        assertFalse(state.canSave)
        assertEquals("Содержимое навыка не может быть пустым.", state.saveBlockReason)
        state = state.onContentChanged("текст")
        assertTrue(state.canSave)
    }

    @Test
    fun `отмена — редактор закрывается без ошибки`() {
        var state = SkillEditState(skillId = "kotlin-check").onLoading().onLoaded(
            SkillDetailInfo(id = "kotlin-check", name = "kotlin-check", content = "текст")
        )
        state = state.onClose()
        assertFalse(state.editorOpen)
        assertFalse(state.saving)
        assertEquals("", state.error)
    }
}
