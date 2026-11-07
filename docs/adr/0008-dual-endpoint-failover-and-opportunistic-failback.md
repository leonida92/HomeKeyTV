# ADR 0008: Dual-Endpoint Failover and Opportunistic Failback Architecture

## Context & Decision
HomeKey TV originally relied on a single `serverUrl` in `ServerConfig`. Users faced an undesirable trade-off: configuring a local LAN IP (`http://192.168.1.50:8123`) provided 0ms low-latency UI responsiveness, but failed completely if the TV was moved to an isolated guest Wi-Fi subnet or IoT VLAN; configuring an external Home Assistant Cloud or Nabu Casa URL (`https://xxx.ui.nabu.casa`) guaranteed cross-network access, but routed all remote button clicks and overlay toggles through external internet hops, adding 150–400ms latency.

We decided to implement a dual-endpoint failover and opportunistic failback architecture across `ServerConfig`, `HomeAssistantRepository`, and the presentation layer:
1. **Dual-Endpoint Configuration**: `ServerConfig` holds a primary `serverUrl` (local LAN IP) and an optional `fallbackServerUrl` (cloud / Nabu Casa URL), authenticated with the same Long-Lived Access Token.
2. **Fast 3-Second Failover**: Connection attempts prioritize the primary LAN endpoint. If the primary socket fails to connect or handshake within 3 seconds (or after 2 consecutive connection failures), `DefaultHomeAssistantRepository` immediately transitions transport to `fallbackServerUrl` before initiating exponential backoff.
3. **Opportunistic Local Failback**: While connected via the fallback endpoint, the repository probes the primary local LAN endpoint upon dock overlay open (`show()`) and on an idle 60-second background cadence. Once the local network responds, transport silently fails back to the primary LAN URL to restore sub-millisecond responsiveness.
4. **Active Route Synchronization for Media**: `HomeAssistantRepository` exposes `activeServerUrl: StateFlow<String>`. Camera streaming (`CameraDialog` and `CameraPipAlert`) binds to `activeServerUrl`, guaranteeing that camera MJPEG frames follow the currently verified and reachable network route.
5. **Bidirectional Backup Compatibility**: `HomeKeyBackup` includes `fallbackServerUrl: String = ""` with a default value, ensuring full backward and forward compatibility with prior backup JSON files.

## Considered Options
- **Parallel Simultaneous Sockets (Happy Eyeballs)**: Rejected because maintaining two concurrent WebSocket subscriptions to Home Assistant consumes excess memory, doubles entity event traffic, and causes duplicate state diff processing on low-power TV chipsets.
- **Permanent Cloud-Only Configuration**: Rejected because routing all smart home actions through external internet hops adds noticeable 150–400ms latency to TV remote button presses.
- **Manual Endpoint Switching via UI Settings**: Rejected because requiring manual user intervention when network conditions change disrupts the seamless TV viewing experience.
