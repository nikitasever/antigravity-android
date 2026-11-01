package com.google.antigravity.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Separate reading surface for code, following Claude's artifact/detail separation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeArtifactViewer(code: String, language: String, fileName: String,
    onDismiss: () -> Unit, onSave: ((String, String) -> Unit)?) {
    val clipboard = LocalClipboardManager.current
    var wrap by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color(0xFF171717), contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                TopAppBar(title = { Text(language.ifBlank { "Код" }, maxLines = 1) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Закрыть код") } },
                    actions = {
                        IconButton(onClick = { wrap = !wrap }) { Icon(Icons.Default.WrapText,
                            if (wrap) "Отключить перенос строк" else "Переносить строки", tint = if (wrap) Color.White else Color.Gray) }
                        IconButton(onClick = { clipboard.setText(AnnotatedString(code)); copied = true }) {
                            Icon(if (copied) Icons.Default.Check else Icons.Default.ContentCopy, if (copied) "Код скопирован" else "Копировать код")
                        }
                        if (onSave != null) IconButton(onClick = { onSave(fileName, code) }) { Icon(Icons.Default.SaveAlt, "Сохранить код") }
                    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF171717),
                        titleContentColor = Color.White, actionIconContentColor = Color.White, navigationIconContentColor = Color.White))
                HorizontalDivider(color = Color(0xFF303030))
                SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(code, fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 21.sp,
                        softWrap = wrap, modifier = Modifier.padding(16.dp).let {
                            if (wrap) it.fillMaxWidth() else it.horizontalScroll(rememberScrollState())
                        })
                }
            }
        }
    }
}
