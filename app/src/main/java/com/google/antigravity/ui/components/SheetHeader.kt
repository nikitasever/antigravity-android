package com.google.antigravity.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.antigravity.ui.design.DesignColors

/** Symmetric title space keeps the heading centered independently of the close button. */
@Composable
fun SheetHeader(title: String, onClose: () -> Unit, modifier: Modifier = Modifier,
    trailingContent: @Composable (() -> Unit)? = null) {
    Box(modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
        Text(title, color = DesignColors.TextPrimary, fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 56.dp, vertical = 12.dp))
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.Default.Close, "Закрыть", tint = DesignColors.TextPrimary)
        }
        if (trailingContent != null) Box(Modifier.align(Alignment.CenterEnd)) { trailingContent() }
    }
}
