package com.google.antigravity

import com.google.antigravity.model.ExtensionBindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Этап 8 (EXTENSIONS_INTEGRATION_PLAN.md): сохранность пользовательских
 * состояний расширений при перезапуске Android-клиента — выбранные привязки
 * (MCP-подключения разговора) кодируются в SharedPreferences и восстанавливаются
 * при старте; повреждённый ввод не роняет клиент и не выдумывает привязки.
 */
class ExtensionBindingsTest {
    @Test fun bindingsSurviveRestartRoundTrip() {
        val original = setOf("mcp:ag-workstation-info", "mcp:rea", "skill:workstation-diagnostics")
        // Как при перезапуске: encode в prefs → decode при инициализации ViewModel.
        val restored = ExtensionBindings.decode(ExtensionBindings.encode(original))
        assertEquals(original, restored)
    }

    @Test fun encodeIsStableAndSortedForDeterministicPersistence() {
        val a = ExtensionBindings.encode(setOf("mcp:b", "mcp:a"))
        val b = ExtensionBindings.encode(setOf("mcp:a", "mcp:b"))
        assertEquals(a, b)
        assertEquals(setOf("mcp:a", "mcp:b"), ExtensionBindings.decode(a))
    }

    @Test fun damagedOrEmptyInputYieldsEmptySetWithoutExceptions() {
        assertEquals(emptySet<String>(), ExtensionBindings.decode(null))
        assertEquals(emptySet<String>(), ExtensionBindings.decode(""))
        assertEquals(emptySet<String>(), ExtensionBindings.decode("[]"))
        assertEquals(emptySet<String>(), ExtensionBindings.decode("{ это не JSON"))
        assertEquals(emptySet<String>(), ExtensionBindings.decode("[1, null, true]"))
    }

    @Test fun blankIdentifiersAreFilteredOut() {
        assertEquals(setOf("mcp:real"), ExtensionBindings.decode("""["mcp:real", "", "  "]"""))
        assertEquals("[]", ExtensionBindings.encode(setOf("", "  ")))
    }

    @Test fun clearingSelectionPersistsEmptyState() {
        val cleared = ExtensionBindings.encode(emptySet())
        assertEquals("[]", cleared)
        assertTrue(ExtensionBindings.decode(cleared).isEmpty())
    }
}
