package com.google.antigravity.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.antigravity.model.extensionErrorCopy
import com.google.antigravity.model.extensionEmptyCopy
import com.google.antigravity.ui.design.DesignColors

@Composable
fun ExtensionErrorBanner(error: String, loading: Boolean, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    val copy = extensionErrorCopy(error)
    Surface(color = DesignColors.Surface, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(copy.title, color = DesignColors.TextPrimary, fontWeight = FontWeight.Medium,
                    fontSize = 16.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Скрыть сообщение об ошибке", tint = DesignColors.TextSecondary)
                }
            }
            Text(copy.detail, color = DesignColors.TextSecondary, fontSize = 14.sp,
                modifier = Modifier.padding(end = 8.dp))
            TextButton(onClick = onRefresh, enabled = !loading) {
                Text(if (loading) "Обновление…" else "Обновить список")
            }
        }
    }
}

@Composable
fun ExtensionEmptyState(defaultTitle: String, defaultDetail: String, query: String,
    loading: Boolean, failed: Boolean, hasItems: Boolean) {
    val copy = extensionEmptyCopy(defaultTitle, defaultDetail, query, loading, failed, hasItems)
    EmptyExtensionsCard(copy.title, copy.detail)
}
