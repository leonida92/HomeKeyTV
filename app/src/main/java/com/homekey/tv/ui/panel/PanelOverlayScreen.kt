package com.homekey.tv.ui.panel

import androidx.compose.runtime.*
import com.homekey.tv.viewmodel.PanelViewModel

@Composable
fun PanelOverlayScreen(
    viewModel: PanelViewModel,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit = {}
) {
    val panelLayout by viewModel.panelLayout.collectAsState()

    DockOverlayScreen(
        viewModel = viewModel,
        layoutPosition = panelLayout,
        onOpenSettings = onOpenSettings,
        onDismiss = onDismiss
    )
}
