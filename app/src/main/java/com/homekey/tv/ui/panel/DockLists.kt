package com.homekey.tv.ui.panel

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homekey.tv.ui.theme.TV_Text_Secondary
import com.homekey.tv.viewmodel.DockItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DockList(
    isVertical: Boolean,
    items: List<DockItem>,
    isReorderMode: Boolean,
    isDialogActive: Boolean,
    activeDialogEntityId: String? = null,
    selectedReorderId: String?,
    overlayOpenEpoch: Long = 0L,
    firstFocusRequester: FocusRequester,
    settingsFocusRequester: FocusRequester,
    reorderFocusRequester: FocusRequester,
    itemFocusRequesters: Map<String, FocusRequester>,
    onDirectionToRecent: ((Int) -> Unit)? = null,
    directionToRecentKeyCode: Int = if (isVertical) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_UP,
    onItemFocusedId: (String) -> Unit = {},
    onFocused: (String, String, Float?) -> Unit = { _, _, _ -> },
    onUnfocused: (String) -> Unit,
    onItemClick: (DockItem) -> Unit,
    onItemLongPress: (DockItem) -> Unit,
    onSelectForReorder: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onToggleReorderMode: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var lastFocusedItemId by remember { mutableStateOf<String?>(null) }
    var wasDialogActive by remember { mutableStateOf(false) }

    LaunchedEffect(overlayOpenEpoch) {
        if (overlayOpenEpoch > 0) {
            lastFocusedItemId = null
            if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
                try {
                    listState.scrollToItem(0)
                } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(isDialogActive) {
        if (isDialogActive) {
            wasDialogActive = true
        } else if (wasDialogActive) {
            wasDialogActive = false
            delay(50)
            try {
                val target = when (lastFocusedItemId) {
                    "dock_settings_tile" -> settingsFocusRequester
                    "dock_reorder_tile" -> if (items.size > 1) reorderFocusRequester else firstFocusRequester
                    else -> lastFocusedItemId?.let { itemFocusRequesters[it] } ?: firstFocusRequester
                }
                target.requestFocus()
            } catch (_: Exception) {}
        }
    }

    if (items.isEmpty()) {
        val emptyContainerModifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xEE161E2E))
            .let {
                if (isVertical) it.padding(16.dp) else it.padding(horizontal = 16.dp, vertical = 10.dp)
            }
        val emptyContent: @Composable () -> Unit = {
            Text(
                text = "Configure from settings",
                color = TV_Text_Secondary,
                fontSize = if (isVertical) 13.sp else 14.sp,
                fontWeight = FontWeight.Medium
            )
            FocusableButton(
                text = "Settings",
                icon = Icons.Default.Settings,
                modifier = Modifier.focusRequester(firstFocusRequester),
                onClick = onOpenSettings
            )
        }
        if (isVertical) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = emptyContainerModifier
            ) {
                emptyContent()
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = emptyContainerModifier
            ) {
                emptyContent()
            }
        }
        return
    }

    val listContent: LazyListScope.() -> Unit = {
        itemsIndexed(
            items = items,
            key = { _, item -> item.id }
        ) { index, item ->
            DockTile(
                item = item,
                index = index,
                isReorderMode = isReorderMode,
                isDialogActive = isDialogActive,
                activeDialogEntityId = activeDialogEntityId,
                isSelectedForReorder = selectedReorderId == item.id,
                isVertical = isVertical,
                customFocusRequester = if (index == 0) firstFocusRequester else itemFocusRequesters[item.id],
                onDirectionToRecent = onDirectionToRecent?.let { fn -> { fn(index) } },
                directionToRecentKeyCode = directionToRecentKeyCode,
                onWrapToLast = {
                    scope.launch {
                        val lastIndex = if (items.size > 1) items.size + 1 else items.size
                        try {
                            listState.scrollToItem(lastIndex)
                        } catch (_: Exception) {}
                        delay(16)
                        try {
                            settingsFocusRequester.requestFocus()
                        } catch (_: Exception) {}
                    }
                },
                onFocused = { name, state, hum ->
                    lastFocusedItemId = item.id
                    onItemFocusedId(item.id)
                    onFocused(name, state, hum)
                },
                onUnfocused = onUnfocused,
                onClick = { onItemClick(item) },
                onLongPress = if (!item.isApp && item.entity?.supportsDetailDialog == true) {
                    { onItemLongPress(item) }
                } else null,
                onSelectForReorder = { onSelectForReorder(item.id) },
                onMove = { dir -> onMove(item.id, dir) }
            )
        }

        // Reorder Mode Toggle Tile (only shown when more than 1 item pinned)
        if (items.size > 1) {
            item(key = "dock_reorder_tile") {
                DockReorderTile(
                    focusRequester = reorderFocusRequester,
                    isReorderMode = isReorderMode,
                    isDialogActive = isDialogActive,
                    activeDialogEntityId = activeDialogEntityId,
                    onDirectionToRecent = onDirectionToRecent?.let { fn -> { fn(items.size) } },
                    directionToRecentKeyCode = directionToRecentKeyCode,
                    onFocused = {
                        lastFocusedItemId = "dock_reorder_tile"
                        onItemFocusedId("dock_reorder_tile")
                        onFocused("Reorder Mode", if (isReorderMode) "Active (Click to Exit)" else "Click to Enter", null)
                    },
                    onUnfocused = { onUnfocused("Reorder Mode") },
                    onClick = onToggleReorderMode
                )
            }
        }

        // Settings Tile at the end
        item(key = "dock_settings_tile") {
            DockSettingsTile(
                focusRequester = settingsFocusRequester,
                isVertical = isVertical,
                isDialogActive = isDialogActive,
                activeDialogEntityId = activeDialogEntityId,
                onDirectionToRecent = onDirectionToRecent?.let { fn -> { fn(if (items.size > 1) items.size + 1 else items.size) } },
                directionToRecentKeyCode = directionToRecentKeyCode,
                onWrapToFirst = {
                    scope.launch {
                        try {
                            listState.scrollToItem(0)
                        } catch (_: Exception) {}
                        delay(16)
                        try {
                            firstFocusRequester.requestFocus()
                        } catch (_: Exception) {}
                    }
                },
                onFocused = {
                    lastFocusedItemId = "dock_settings_tile"
                    onItemFocusedId("dock_settings_tile")
                    onFocused("Settings", "Configure", null)
                },
                onUnfocused = { onUnfocused("Settings") },
                onClick = onOpenSettings
            )
        }
    }

    if (isVertical) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
            modifier = Modifier.wrapContentHeight(),
            content = listContent
        )
    } else {
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = Modifier.wrapContentWidth(),
            content = listContent
        )
    }
}
