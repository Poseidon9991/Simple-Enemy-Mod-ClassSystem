# Units & Factions

## Unit classes

| Entity | Extends | Faction | Notes |
|---|---|---|---|
| `simpleenemymod:pmcunit` | SEM `AbstractUnit` → `Monster` | PMC (player's army) | Recruited via recruit table; owns the 18-slot inventory |
| `sem_classsystem:bhqunit` (`BhqUnitEntity`) | `PmcUnitEntity` | BHQ (player's army, heavy) | Same pipeline as PMC; bigger stats via `UnitClassAttributes` |
| `simpleenemymod:ruunit`, `usunit` | `AbstractUnit` | RU / US enemy factions | Spawn in the world; diplomacy via SEM `FactionsConfig` |
| `sem_classsystem:vdfunit` (`VdfUnitEntity`) | `PathfinderMob` | Village Defense Force | Own model/goals — see below |

`UnitFactionTags` provides tag-based faction checks (`isRuUnit`, `isUsUnit`)
so targeting doesn't depend on class hierarchy.

## VDF militia — the odd one out

VDF deliberately extends `PathfinderMob`, not `Monster`/`AbstractUnit`:
- Not an `Enemy` → iron golems don't attack it, no Peaceful despawn
- Doesn't inherit SEM's hostile marker → excluded from `UnitHostilityEvents`
  unit-hunt rewrite (it's an ally, not a target)
- Can't use SEM classes that hard-cast to `AbstractUnit`
  (`CombatStateLayer` had to be ported, not reused — `UnitProceduralPoses`)

Own two-goal combat package (`VdfGunAttackGoal`, plus patrol/night-watch/
interact/raid-response duty goals). Melee was removed — every militia member
spawns armed via `rollMilitiaGun()` → `ClassLoadouts` `vdf_units` table →
inline `MILITIA_GUNS` fallback; `readAdditionalSaveData` re-arms legacy
sword saves on load.

Two-way ally contract: VDF never targets players/villagers/golems/PMC/BHQ
(`isProtectedAlly` + `setTarget` chokepoint); friendly fire between PMC/BHQ
and VDF is vetoed in `VdfFriendlyFireEvents`/`VdfUnitEntity`.

## Spawn paths

- **PMC** — recruit table (`BhqRecruitTableBlock`/`RecruitTableMenu`), the
  SEM `SpawnGroupData`/`finalizeSpawn` path → `equipRandomGun()` →
  `PmcUnitWeaponEquipper` (mixin-injected ammo/sidearm at TAIL)
- **BHQ** — same entity path as PMC via `BhqUnitEntity.finalizeSpawn`
- **RU/US** — SEM natural spawning; loadout via `Ru/UsWeaponEquipper`
- **VDF** — `VdfGarrisonEvents` places militia on village guard posts
  (`GuardPostBlock`/`GuardPostBlockEntity`); patrol behavior in
  `VdfPatrolGoal`/`VdfNightWatchGoal`

## Survival rules

- PMC/BHQ are `Monster`s — `MixinPmcUnitPeaceful` suppresses peaceful
  despawn for owned units (superclass is external, needed a dedicated mixin)
- `defendersIgnoreVDF` config gates the golem→VDF veto
  (`UnitDefenseCompat`); PMC→VDF pact is unconditional
- `UnitOwnershipCap`/`MixinPmcUnitOwnershipCap` — recruit cap per player
