package com.homeassistant.tv.ui.panel

import androidx.compose.runtime.*
import com.homeassistant.tv.viewmodel.PanelViewModel

@Composable
fun PanelOverlayScreen(
    viewModel: PanelViewModel,
    onOpenSettings: () -> Unit
) {
    val panelLayout by viewModel.panelLayout.collectAsState()

    DockOverlayScreen(
        viewModel = viewModel,
        layoutPosition = panelLayout,
        onOpenSettings = onOpenSettings
    )
}
