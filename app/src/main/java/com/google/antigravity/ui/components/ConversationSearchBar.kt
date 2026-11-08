package com.google.antigravity.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.antigravity.ui.design.DesignColors

/** Conversation search owns two rows so navigation never squeezes the query on phones. */
@Composable
internal fun ConversationSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    matchPosition: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val navigationColors = IconButtonDefaults.iconButtonColors(
        contentColor = DesignColors.TextSecondary,
        disabledContentColor = DesignColors.TextSubtle.copy(alpha = 0.45f)
    )
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                .semantics { contentDescription = "Поиск в чате" },
            placeholder = { Text("Поиск в чате", maxLines = 1) },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            leadingIcon = { Icon(Icons.Default.Search, null, tint = DesignColors.TextSecondary) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = {
                    onQueryChange("")
                    focusRequester.requestFocus()
                }) { Icon(Icons.Default.Close, "Очистить поиск", tint = DesignColors.TextSecondary) }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = DesignColors.TextPrimary,
                unfocusedTextColor = DesignColors.TextPrimary,
                cursorColor = DesignColors.TextPrimary,
                focusedBorderColor = DesignColors.TextSecondary,
                unfocusedBorderColor = DesignColors.Border,
                focusedContainerColor = DesignColors.Surface,
                unfocusedContainerColor = DesignColors.Surface,
                focusedPlaceholderColor = DesignColors.TextSecondary,
                unfocusedPlaceholderColor = DesignColors.TextSecondary
            )
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    query.isBlank() -> "Введите запрос"
                    matchCount == 0 -> "Ничего не найдено"
                    else -> "${matchPosition.coerceIn(0, matchCount - 1) + 1} из $matchCount"
                },
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                color = DesignColors.TextSecondary,
                style = MaterialTheme.typography.labelMedium
            )
            IconButton(onClick = onPrevious, enabled = matchCount > 0, colors = navigationColors) {
                Icon(Icons.Default.ExpandLess, "Предыдущее совпадение")
            }
            IconButton(onClick = onNext, enabled = matchCount > 0, colors = navigationColors) {
                Icon(Icons.Default.ExpandMore, "Следующее совпадение")
            }
            IconButton(onClick = onClose, colors = navigationColors) {
                Icon(Icons.Default.Close, "Закрыть поиск")
            }
        }
    }
}
