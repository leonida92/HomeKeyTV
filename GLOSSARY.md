# HomeKey TV

Fast, lightweight Jetpack Compose quick-access dock and remote button remapper for Google TV and Android TV, integrated with Home Assistant.

## Language

**Dock**:
The quick-access floating overlay rendered over Android TV applications and video streams.
_Avoid_: Panel, taskbar, sidebar (when referring to the entire overlay)

**DockConfig**:
Configuration governing dock placement geometry (bottom, left, or right), modal popup style, and camera preview dimensions.
_Avoid_: PanelLayout, PopupPreferences

**ServerConfig**:
Configuration defining the Home Assistant server endpoint URL, access token, mobile setup pairing PIN, and integration status.
_Avoid_: ConnectionSettings, AuthConfig

**ThemeConfig**:
Configuration defining the active visual theme preset, user custom domain hex color overrides, and resolved domain color palette.
_Avoid_: ColorScheme, StyleConfig

**Pinned Entity**:
A specific Home Assistant entity selected by the user to appear on the dock with dynamic state and interactive dialogs.
_Avoid_: PinnedItem, FavoriteEntity

**Pinned App**:
An installed Android TV application shortcut pinned directly alongside Home Assistant entities on the dock.
_Avoid_: DockApp, AppShortcut

**Button Remap**:
A physical remote control key code binding mapped to an action (toggle dock, toggle entity, launch app, or pass through).
_Avoid_: KeyMapping, ButtonBinding

**Remap Action**:
A discrete domain operation executed upon remote button activation (such as toggling the dock, toggling an entity, calling a service, or launching an installed application).
_Avoid_: KeyAction, ButtonCommand

**Monitored Entity**:
A Home Assistant entity whose state is actively tracked over the real-time event stream, derived from pinned entities, remote button targets, and active dialog targets.
_Avoid_: SubscribedEntity, WatchedEntity, TrackedEntity

**Camera PiP Alert**:
A transient picture-in-picture video feed overlay triggered automatically by an external Home Assistant notification event.
_Avoid_: NotificationPopup, PipNotification

**Cover Control**:
Dedicated TV overlay controls for motorized blinds, shades, and garage doors, providing open/stop/close buttons and position/tilt sliders.
_Avoid_: BlindPopup, ShadeDialog

**Fan Control**:
Dedicated TV overlay controls for ventilation and ceiling fans, providing speed percentage adjustment, oscillation toggle, and power state.
_Avoid_: SpeedPopup, FanSettings

**Lock Control**:
Dedicated TV overlay controls for security locks, providing dynamic status visuals (locked green / unlocked red) and two-step unlock confirmation.
_Avoid_: SecurityPopup, DoorDialog

**Remote Input Engine**:
Pure Kotlin gesture and timing engine responsible for classifying remote key events, contact bounce filtering, multi-press recognition, and app-scoping resolution.
_Avoid_: KeyClassifier, GestureRecognizer, InputManager

**Dock Overlay State Engine**:
Pure policy engine governing quick-access dock visibility, debounce timing, lifecycle transitions, and authoritative back-navigation hierarchy.
_Avoid_: DockController, OverlayManager, DockStateManager

**Window Overlay Host**:
Presentation host responsible for attaching, positioning, rendering, and tearing down system overlay window hierarchies.
_Avoid_: OverlayViewManager, WindowManagerAdapter, DockViewHost
