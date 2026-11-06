# ADR 0002: Isolate Home Assistant Network Layer behind an Anti-Corruption Layer (ACL)

## Context & Decision
`HAWebSocketClient` was a 625-line monolithic god object mixing low-level OkHttp WebSocket transport, connection/reconnection backoff, message ID sequencing, raw JSON parsing/serialization, protocol handshakes, entity diff reconciliation, in-memory state caching, and domain service calls. ViewModels and background services depended directly on this concrete OkHttp singleton, making unit testing impossible without live network mocks and leaving the application vulnerable to Home Assistant protocol drift.

We decided to decompose the network layer into three deep, decoupled modules behind a domain repository interface:
1. **`HomeAssistantRepository`**: Domain contract exposing reactive state (`entities`, `connectionStatus`, `pipAlert`, `errorMessage`) and domain interaction methods (`toggleEntity`, `setBrightness`, `setTargetTemperature`, etc.).
2. **`HATransport`**: Lean transport abstraction managing physical WebSocket connection, raw text frame transmission, and connection lifecycle events (`Connected`, `Disconnected`, `Failure`), with `OkHttpHATransport` for production and `FakeHATransport` for headless testing.
3. **`HAMessageCodec` (Anti-Corruption Layer)**: Pure, stateless translation layer converting raw JSON strings to typed `HAInboundMessage` events (`AuthRequired`, `AuthOk`, `AuthInvalid`, `Result`, `StateChangedEvent`, `EntityDiffEvent`, `PipAlertEvent`) and serializing outbound domain commands without Android or network dependencies.
4. **`DefaultHomeAssistantRepository`**: Stateful session coordinator that owns the authenticated state machine, exponential reconnection backoff, automatic state resynchronization (`get_states`, `subscribe_entities`, `homekey_tv_notify`), reactive monitored entity reconciliation via `PreferencesRepository`, and latency-critical optimistic state updates.

## Considered Options
- **Retaining `HAWebSocketClient` and wrapping it in an interface**: Rejected because it leaves internal coupling intact, keeps network I/O conflated with protocol parsing, and prevents isolated unit testing of the codec and session state machine.
- **Placing reconnection backoff inside `HATransport`**: Rejected to keep `HATransport` as a dumb, swappable byte/frame pipe, centralizing all authentication, backoff, and resynchronization policy in the session manager.
- **Introducing granular per-domain repositories (`LightRepository`, `ClimateRepository`)**: Rejected because Home Assistant delivers all entity states over a unified WebSocket event stream and snapshot, making a single cohesive `HomeAssistantRepository` significantly simpler and lower overhead.
