package com.google.antigravity.model

/** A refreshed catalog must not hide the actual saved selection. */
fun modelPickerOptions(
    available: List<AntigravityModelOption>, selectedId: String, selectedDisplay: String
): List<AntigravityModelOption> {
    val models = available.ifEmpty { AVAILABLE_AGY_MODELS }.distinctBy { it.id }
    return if (selectedId.isNotBlank() && models.none { it.id == selectedId }) {
        listOf(AntigravityModelOption(selectedId, selectedDisplay, "Текущая модель")) + models
    } else models
}
