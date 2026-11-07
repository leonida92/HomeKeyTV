## Codebase navigation

- **UI / Composables**: `app/src/main/java/com/homekey/tv/ui/{panel,settings}/`
  - `panel/`: Modular dock UI (`DockTile.kt`, `DockLists.kt`, `DockIcons.kt`, `FloatingLabelBadge.kt`, `MinimalEntityPopupContent.kt`, `RecentAppsSatellite.kt`, `DockOverlayScreen.kt`).
  - `settings/`: `SettingsScreen.kt`.
- **ViewModels**: `app/src/main/java/com/homekey/tv/viewmodel/`
- **Data layer**: `app/src/main/java/com/homekey/tv/data/{local,api,models,server}/`
- **Services & Overlays**: `app/src/main/java/com/homekey/tv/service/`
- **Unit tests**: `app/src/test/java/com/homekey/tv/`

## Windows environment & shell tooling

- **Shell Execution**: Commands execute directly in PowerShell. Never wrap commands in `powershell -Command "..."` with double quotes (prevents premature `$variable` expansion).
- **Line Endings & File Edits**: Windows checkouts use CRLF (`\r\n`). Multi-line `edit_file` targets containing bare LF will fail; use single-line anchor replacements or direct PowerShell script blocks for large multi-line substitutions.
- **ADB Executable**: When calling ADB directly in scripts, use `& "C:\Users\user\AppData\Local\Android\Sdk\platform-tools\adb.exe"`.

## Coding standards

Coding standards and architectural rules live in `CODING_STANDARDS.md`. Review agents must enforce them.

## Hardware verification

- **Target Device**: Google Streamer Android TV at `192.168.1.50:5555`.
- **ADB Executable**: `C:\Users\user\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
- **Deployment**: Always chain connection with commands (`adb connect 192.168.1.50:5555; adb install ...`). Always install release APK (`./gradlew assembleRelease` -> `HomeKeyTV-v*.apk`) to avoid debug overlay UI stutter.
- **Verification**: After deployment, launch the app (`adb shell monkey -p com.homekey.tv -c android.intent.category.LAUNCHER 1`), obtain PID via `$appPid = (adb shell pidof com.homekey.tv).Trim()` (avoid `$pid` which is PowerShell's read-only variable), broadcast explicit intent with `-p com.homekey.tv` (`adb shell am broadcast -a com.homekey.tv.ACTION_TOGGLE_DOCK -p com.homekey.tv`), and inspect logcat with `adb logcat --pid=$appPid -d` to verify startup and toggle traces immediately.

## Agent skills

### Issue tracker

Issues and specs live as local Markdown files in `.scratch/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Standard triage label vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout (`GLOSSARY.md` and `docs/adr/` at the repo root). See `docs/agents/domain.md`.
