package com.google.antigravity.model

/**
 * Состав выбора модели. Подписочные модели стоят первыми: они работают без ключа
 * и без ПК, поэтому их не должно скрывать обновление каталога моста.
 */
fun modelPickerOptions(
    available: List<AntigravityModelOption>,
    selectedId: String,
    selectedDisplay: String,
    proModels: List<AntigravityModelOption> = emptyList()
): List<AntigravityModelOption> {
    val models = (proModels + available.ifEmpty { AVAILABLE_AGY_MODELS }
        .filterNot { mapToDirectGeminiModelId(it.id) != it.id } + DIRECT_GEMINI_MODELS).distinctBy { it.id }
    return if (selectedId.isNotBlank() && models.none { it.id == selectedId }) {
        listOf(AntigravityModelOption(selectedId, selectedDisplay, "Текущая модель")) + models
    } else models
}
