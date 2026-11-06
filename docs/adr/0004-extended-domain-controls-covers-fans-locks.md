# ADR 0004: Extended Domain Controls for Covers, Fans, and Locks

## Context & Decision
HomeKey TV originally supported rich modal dialogs only for lights (brightness/color), climate (thermostats), media players, and cameras. Covers, fans, and locks were limited to binary on/off optimistic toggles:
- Motorized covers (blinds, shades, garage doors) had no controls for intermediate positioning, tilt adjustment, or command stopping.
- Fans had no percentage speed control or oscillation switching.
- Security locks lacked safeguards against accidental remote-control unlocking.

We decided to introduce dedicated domain controls for `cover`, `fan`, and `lock`:
1. **Typed Repository Operations**: `HomeAssistantRepository` declares explicit domain methods (`openCover`, `closeCover`, `stopCover`, `setCoverPosition`, `setCoverTiltPosition`, `setFanPercentage`, `setFanOscillating`, `lock`, `unlock`), isolating JSON payload creation inside the repository layer.
2. **Modular Dialog Architecture**: Each domain is implemented in its own modular composable file (`CoverDialog.kt`, `FanDialog.kt`, `LockDialog.kt`) supporting both Centered Modal and Minimal Popup styles without inflating `MinimalEntityPopups.kt`.
3. **Optimistic 60fps Slider Debouncing**: Cover position and Fan speed percentage sliders provide instant local UI feedback on remote D-pad input while debouncing WebSocket service dispatch by 300ms.
4. **Two-Step Unlock Confirmation**: Door unlocking requires an in-place confirmation ("Unlock Door" -> "Confirm Unlock (Press again)") with a 4-second timeout to safeguard against accidental clicks, while locking remains immediate.
5. **High-Contrast Lock Security Visuals**: Locks dynamically render `#10B981` (Secure Green) when locked and `#EF4444` (Alert Red) when unlocked.

## Considered Options
- **Appending all new controls to `MinimalEntityPopups.kt`**: Rejected because `MinimalEntityPopups.kt` is already ~1,500 lines; adding 3 domains would create a 2,500-line monolith with high merge contention and slow preview rendering.
- **Untyped `callService` calls from Composable UI**: Rejected because `CODING_STANDARDS.md` § 3 requires typed repository interfaces reflecting genuine caller intents, preserving offline unit testability.
- **Modal Popups for Unlock Confirmation**: Rejected because nested dialog overlays on Android TV cause focus trapping and remote navigation friction.
