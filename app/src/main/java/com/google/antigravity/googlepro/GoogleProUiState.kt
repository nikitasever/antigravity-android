package com.google.antigravity.googlepro

import com.google.antigravity.model.AntigravityModelOption

/**
 * Состояние окна Google AI Pro.
 *
 * `route` и `diagnostics` — не украшение: маршрут, по которому пошёл запрос, и
 * причина отказа видны пользователю, а не прячутся в лог. Так же поступает окно
 * анлокера на ПК.
 */
data class GoogleProUiState(
    val signedIn: Boolean = false,
    val email: String = "",
    val tier: String = "",
    val models: List<AntigravityModelOption> = emptyList(),
    val ineligible: String = "",
    val route: String = "",
    val diagnostics: String = "",
    val busy: Boolean = false,
    val error: String = ""
)
