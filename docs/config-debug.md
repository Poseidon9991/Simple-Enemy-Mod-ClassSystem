# Config & Debugging

## Config — `ClassSystemConfig` (`config/`)

Forge COMMON config, registered in `SemClassSystem`. Sections (TOML):

| Section | Keys (examples) |
|---|---|
| `[needs]` | `enabled` — master switch for hunger/energy/morale |
| `[vdf_unit]` | spread multiplier, patrol/watch tuning |
| faction sections (`[pmc]`/`[bhq]`/`[ru]`/`[us]`) | spread, attributes, ownership cap |
| `[diplomacy]` | `defendersIgnoreVDF` (golem veto), SEM `FactionsConfig` friendship flags |
| weapon-class / loadout tuning | per-class multipliers |

Removed keys: `vdfGunnerChance` (VDF is all-gun now),
`cycleRecruitPreference` (replaced by the recruit menu buttons).

## Debug log markers

| Marker | Source | Meaning |
|---|---|---|
| `[SEMCAmmo]` | `UnitAmmoService` | WARN — names the exact gate that skipped ammo: no gun / no handler / unresolvable ammoId / unbuildable stack |
| `[SEMCVdf]` | `VdfGunAttackGoal` | DEBUG — every non-SUCCESS `ShootResult` + periodic SUCCESS heartbeat (m870 NEED_BOLT diagnosis) |
| `[UnitAnimationConfig]` | SEM itself | Animation layer creation dump |

## Common failure modes & where to look

| Symptom | First suspect |
|---|---|
| Unit ammo slot empty after relog | oversized-stack corruption → check `[SEMCAmmo]` warns; `ensurePrimaryAmmo` gates |
| VDF sees target, never shoots | `ShootResult` not handled → `[SEMCVdf]` debug lines name the stuck state |
| Hostiles walk past units | `UnitHostilityEvents` — is the mob an `Enemy`? does it use a player NATG? |
| Units vanish on Peaceful | `MixinPmcUnitPeaceful` applied? |
| Golems fight VDF | `defendersIgnoreVDF` config + `UnitDefenseCompat` |
| Curios item invisible on unit | `UnitCuriosRenderLayer` + `bhqunit.json` slot grants |
| Mixin target missing | SEM jar version drift — `javap` the runtime jar vs `libs/simpleenemymod-mapped.jar` |

## Build & verify

- `gradlew compileJava` / `gradlew build` (Java 17, ForgeGradle 6,
  `-Xmx3G`, no daemon)
- No test sources — `test` is `NO-SOURCE`
- jar: `build/libs/sem_classsystem-1.20.1-0.4.0.jar`
