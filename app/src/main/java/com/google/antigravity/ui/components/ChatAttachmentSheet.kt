package com.google.antigravity.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.antigravity.ui.design.DesignColors

/** Compact action list observed in ChatGPT Android, live capture 091526. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatAttachmentSheet(
    onTakePhoto: () -> Unit,
    onPickPhotos: () -> Unit,
    onPickAnyFiles: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenPhoneStorage: () -> Unit,
    onMention: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
        containerColor = DesignColors.SurfaceHigh,
        contentColor = DesignColors.TextPrimary,
        shape = RoundedCornerShape(28.dp),
        dragHandle = null
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
            AttachmentAction(Icons.Outlined.CameraAlt, "Камера", onTakePhoto)
            AttachmentAction(Icons.Outlined.Image, "Фото", onPickPhotos)
            AttachmentAction(Icons.Outlined.AttachFile, "Файлы", onPickAnyFiles)
            AttachmentAction(Icons.Outlined.Extension, "Плагины", onOpenPlugins)
            HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = DesignColors.Border)
            AttachmentAction(Icons.Default.AlternateEmail, "Упомянуть…", onMention)
            AttachmentAction(Icons.Outlined.Folder, "Файлы телефона", onOpenPhoneStorage)
        }
    }
}

@Composable
private fun AttachmentAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 64.dp).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Surface(color = DesignColors.Border, shape = CircleShape) {
            Icon(icon, null, modifier = Modifier.padding(11.dp).size(24.dp))
        }
        Text(label, modifier = Modifier.weight(1f).padding(start = 14.dp, end = 4.dp),
            fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}
