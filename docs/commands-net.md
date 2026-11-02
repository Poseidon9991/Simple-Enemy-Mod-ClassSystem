# Commands, Networking & World State

## Commands — `commands/SemClassCommands.java`

`/semcs` family — list/name/assign units, claim chunks, trigger test
raids, toggle debug. Command callbacks look "unused" to static scans —
they're registered via `Commands.literal`/`argument` builders.

## Packets (`network/`, Forge `SimpleChannel` → `SemcsNetwork`)

| Packet | Direction | Purpose |
|---|---|---|
| `PacketAssignDuty` | C→S | Assign duty post |
| `PacketAssignRoute` | C→S | Patrol route — `MAX_UNITS` cap on client-supplied counts |
| `PacketClaimChunk` | C→S | Claim a chunk for unit duty |
| `PacketGuardPostAction` | C→S | Guard-post UI actions |
| `PacketOpenGuardPost` | S→C | Open the post GUI |
| `PacketRaidPing` | S→C | Night-raid warning ping |
| `PacketReturnToPost` | C→S | Recall units to post |
| `PacketSyncClaims` | S→C | Chunk claim sync |
| `PacketSyncIntel` | S→C | Scout/target intel sync |
| `PacketSyncUnitMarkers` | S→C | Map marker sync |
| `PacketTeleportUnits` | C→S | Teleport squad to guard post |

## SavedData (`world/`)

| Store | Contents |
|---|---|
| `PlayerChunkClaims` | Which chunks each player claimed |
| `UnitNameRegistry` | Persistent unit names |
| `UnitEventLog` | Combat/raid event history (feeding `UnitMarker`) |
| `VillageDefenseSavedData` | VDF garrison state |

`SavedData::load` method references look unused to static scans but are
live — Forge calls them on world load.

## Map markers

`map/UnitMarker` + `event/UnitMarkerTracker` + `UnitMarkerStore` (client) —
SEM-unit positions/raids pushed via `PacketSyncUnitMarkers` →
`client/xaero/` renderers (`UnitMarkerElements`, `UnitMinimapElements`,
`RoutePlot`, `SemcsWaypoints`, `SemcsMinimapCompat`, `SemcsXaeroCompat` —
all behind `XaeroSemcsMixinPlugin` + `ModList.isLoaded`).
