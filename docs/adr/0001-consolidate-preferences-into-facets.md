# ADR 0001: Consolidate Preferences into Facets and Decouple WebSocket Subscriptions

## Context & Decision
`PreferencesManager` was a monolithic god object exposing 19 primitive `StateFlow`s, 26 granular setters, and triggering remote Home Assistant `subscribe_entities` WebSocket calls directly inside local disk storage. 

We decided to deepen local storage behind an `interface PreferencesRepository` exposing cohesive facet value objects (`ServerConfig`, `DockConfig`, `ThemeConfig`, `RecentAppsConfig`) with atomic transform mutations. Remote WebSocket subscription orchestration is severed from storage and moved into the Home Assistant network layer. Synchronous IO snapshot pre-warming in repository initialization is deliberately preserved to guarantee 0ms cold-start latency and prevent visual overlay flicker on Android TV.

## Considered Options
- **Separate repositories per domain concern (`ServerRepository`, `DockRepository`, etc.)**: Rejected to avoid shallow wrapper proliferation around a single underlying DataStore file and maintain atomic multi-setting transactions.
- **Purely asynchronous flow collection without synchronous IO seeding**: Rejected because asynchronous initialization introduces race conditions and input latency during TV cold boots and rapid remote button remap lookups.
- **Retaining a legacy `PreferencesManager` wrapper**: Rejected in favor of a clean cutover to eliminate technical debt.
