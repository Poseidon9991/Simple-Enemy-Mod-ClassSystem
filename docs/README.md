# [SEM] Class System — Developer Wiki

Local architecture wiki for the mod. Generated as a code map, not a design
spec — check `developmentflow.md` for the update log.

## Pages

| Page | Covers |
|---|---|
| [architecture.md](architecture.md) | Module map, runtime dependencies, source layout |
| [units.md](units.md) | Factions, entity classes, spawn/recruit paths, VDF militia |
| [combat-ai.md](combat-ai.md) | Goal tables, targeting, gun pipeline, hostile-mob retargeting, shotgun/bolt fix |
| [loadouts.md](loadouts.md) | Weapon classes, `class_loadouts` datapack, equippers, recruit tables |
| [inventory-ammo.md](inventory-ammo.md) | Unit inventory layout, `UnitAmmoService`, oversized-stack bug |
| [needs.md](needs.md) | Hunger/energy/morale, sleep/eat/medic goals, veterancy |
| [rendering.md](rendering.md) | VDF rig, procedural pose pipeline, armor layers, curios, particles |
| [mixins.md](mixins.md) | Full mixin catalog by target class |
| [commands-net.md](commands-net.md) | Packets, commands, SavedData, chunk claims, unit markers |
| [config-debug.md](config-debug.md) | Config keys, debug log markers, common failure modes |

## Quick orientation

- **Mod id:** `sem_classsystem`, entry `SemClassSystem.java`
- **Platform:** Minecraft 1.20.1 / Forge 47.4.0 / Java 17
- **Extends:** Simple Enemy Mod (`simpleenemymod`) via Mixin — SEM unit
  classes (`AbstractUnit`, `PmcUnitEntity`, equipper classes) are patched
  directly rather than subclassed
- **Depends on:** TaCZ (guns/ammo), GeckoLib (armor), optional: Curios,
  Xaero's maps, WTHIT, Superb Warfare
- **Build:** `gradlew compileJava` / `gradlew build` →
  `build/libs/sem_classsystem-1.20.1-0.4.0.jar`
