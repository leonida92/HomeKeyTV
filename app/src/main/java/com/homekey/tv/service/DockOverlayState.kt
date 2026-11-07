package com.homekey.tv.service

sealed interface DockOverlayState {
    data object Unattached : DockOverlayState
    data object Hidden : DockOverlayState
    data object Prewarmed : DockOverlayState
    data object Showing : DockOverlayState
}
