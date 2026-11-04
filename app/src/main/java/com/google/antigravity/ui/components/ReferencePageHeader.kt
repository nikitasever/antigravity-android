package com.google.antigravity.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.google.antigravity.ui.design.DesignColors

/** Shared Claude-style header for settings, projects and saved documents. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReferencePageHeader(title: String, navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}) {
    CenterAlignedTopAppBar(title = {
        Text(title, fontSize = 22.sp, fontFamily = FontFamily.Serif,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }, navigationIcon = navigationIcon, actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = DesignColors.Bg,
            titleContentColor = DesignColors.TextPrimary,
            navigationIconContentColor = DesignColors.TextPrimary,
            actionIconContentColor = DesignColors.TextPrimary))
}
