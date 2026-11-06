package com.google.antigravity

import com.google.antigravity.model.*
import org.junit.Assert.*
import org.junit.Test

class ModelPickerOptionsTest {
    @Test fun preservesSavedOpenRouterSelectionMissingFromBridgeCatalog() {
        val id = "openrouter/vendor/custom-model"
        val result = modelPickerOptions(AVAILABLE_AGY_MODELS, id, "custom-model")
        assertEquals(id, result.first().id)
        assertEquals("custom-model", result.first().displayName)
        assertEquals(1, result.count { it.id == id })
    }

    @Test fun removesDuplicateCatalogIdsWithoutReplacingTheirMetadata() {
        val first = AntigravityModelOption("same", "First", "Description")
        val duplicate = first.copy(displayName = "Duplicate")
        assertEquals(listOf(first), modelPickerOptions(listOf(first, duplicate), "same", "First"))
    }

    @Test fun unavailableCatalogFallsBackWithoutHidingActualSavedSelection() {
        val result = modelPickerOptions(emptyList(), "custom", "Custom")
        assertEquals("custom", result.first().id)
        assertTrue(result.containsAll(AVAILABLE_AGY_MODELS))
    }
}
