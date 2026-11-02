# Combat & AI

## Unit goal table

SEM units (PMC/BHQ/RU/US) get goals injected by `MixinAbstractUnitGoals`:

| Goal | Role |
|---|---|
| `UnitMeleeAttackGoal` | Close-quarters fallback |
| `UnitWeaponFallbackGoal` | Broken/empty gun → melee or sidearm |
| `UnitSelfHealGoal` / `UnitMedicHealGoal` | Bandage self / heal allies |
| `UnitWoundedGoal` | Heavy-damage retreat behavior |
| `UnitEatGoal` / `UnitSleepGoal` | Needs system upkeep |
| `UnitAmmoBoxGoal` | Resupply from TaCZ ammo boxes (incl. Curios) |
| `UnitLootGoal` | Pick up useful drops |
| `UnitResupplyGoal` / `StockpileSearch` | Guard-post stockpile runs |
| `UnitChunkDutyGoal` | Route/chunk patrol duties |

`MixinTacticalManagerGoal` is the per-tick conductor — every sub-system it
calls self-gates on `tickCount % N` staggered by entity id (retarget 15,
weapon-swap 10, relocate 10, stuck-watch 40, teamwork 8, strafe 12), so the
HEAD calls cost only a modulo.

VDF runs its own package: `VdfGunAttackGoal` (combat), `VdfMeleeAttackGoal`
(removed), `VdfPatrolGoal`, `VdfNightWatchGoal`, `VdfInteractGoal`,
`VdfRaidResponseGoal` + vanilla `HurtByTargetGoal` (protected-ally
exclusions: villagers, golems, VDF, `PmcUnitEntity`).

## Gun firing pipeline (all shooters)

`IGunOperator` drives TaCZ's state machine. The contract discovered while
fixing the VDF shotgun stall — **always inspect `ShootResult`:**

| Result | Action |
|---|---|
| `NEED_BOLT` | `operator.bolt()` — m870 pump, bolt snipers jam without it |
| `NO_AMMO` | `operator.reload()` |
| `NOT_DRAW` | `operator.draw(::getMainHandItem)` |
| `IS_RELOADING` / `IS_BOLTING` / `IS_DRAWING` / `COOL_DOWN` | wait — re-calling reload is a no-op anyway |
| `SUCCESS` | fired |

`VdfGunAttackGoal.tick` additionally waits out `isReloading()` and
`cancelReload()`s tube-fed shotguns once a shell is chambered while the
target is visible (partial-tube fighting). SEM's `RangedGunAttackGoal`
follows the same contract — `MixinRangedGunAttackGoal` layers aim-correction
on top (launcher ground-aim via `semcs$groundAimPoint`, shell-impact
learning, cluster centroids, blast standoff).

## Hostile mobs hunt units (`UnitHostilityEvents`)

`EntityJoinLevelEvent`, server-side. For each `Enemy` mob (not
`AbstractUnit`/`VdfUnitEntity`), find its `NearestAttackableTargetGoal`
whose `targetType == Player.class` (read via `MixinNearestAttackableTargetGoal`
accessor) and swap it for a merged `Player|AbstractUnit|VdfUnitEntity` goal
at the same priority. Covers zombies, skeletons, creepers, spiders, slimes,
witches, illagers, blazes, modded hostiles with the same pattern; bespoke
goals (phantom, ghast, enderman) untouched.

## Night raids (`NightRaidEvents`)

Roll → warn → strike. Honors SEM `FactionsConfig` diplomacy (friendly
factions never raid, both friendly = no raids) but always warns:
guard post = 60s, no post = 15s "movement in the dark".

## Firing discipline

- `spreadMultiplier` per faction config × veterancy × needs state
- `UnitBackupCall` — alert/sharing between units
- `VdfFriendlyFireEvents` — the PMC↔VDF no-war pact
- `MixinBulletImpactSuppression` — bullet impacts apply suppression state
