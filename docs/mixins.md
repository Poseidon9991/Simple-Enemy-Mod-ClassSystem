# Mixin Catalog

Config: `sem_classsystem.mixins.json` (server section + `client` section).
Soft-dep classes are gated by `AbstractSoftDepMixinPlugin` +
`XaeroSemcsMixinPlugin`. Injected members use the `semcs$` prefix.

## SEM `AbstractUnit` / units

| Mixin | Purpose |
|---|---|
| `MixinAbstractUnit` | `readAdditionalSaveData` TAIL → `UnitAmmoService.ensurePrimaryAmmo` (load-time ammo repair) |
| `MixinAbstractUnitGoals` | Injects the SEMC goal package into SEM units |
| `MixinUnitAmmoReserve` | Equipper TAIL (PMC/RU/US) → armor + `ensurePrimaryAmmo` + sidearm/food |
| `MixinPmcAmmoPlacement` | PMC ammo placement pass (pre-service path) |
| `MixinPmcUnitPeaceful` | Stops peaceful despawn of recruited units |
| `MixinPmcUnitOwnershipCap` / `UnitOwnershipCap` | Recruit cap |
| `MixinPmcUnitNaming` | Persistent unit names via `UnitNameRegistry` |
| `MixinPmcUnitDutyClear` | Duty cleanup on dismiss |
| `MixinPmcUnitMenuTitle` | Menu title shows unit name/class |
| `MixinWeaponEquippers` | Weapon-class routing through SEMC tables |
| `MixinSpawnHelper` | Spawn/loadout assistance |
| `MixinUnitInventoryHandler` | Slot-layout docs + helpers |
| `MixinRecruitTable` | Recruit menu integration |

## SEM AI / combat

| Mixin | Purpose |
|---|---|
| `MixinRangedGunAttackGoal` | Aim-correction: `semcs$leadTargetPosition`, `semcs$groundAimPoint` (launchers aim under feet), cluster centroids, `observeFire` learning |
| `MixinTacticalManagerGoal` | Per-tick conductor — cadence-gated subsystems |
| `MixinMoveToAttackRange` | Move-to-range behavior tweak |
| `MixinMeleeAttackGoal`/`UnitMeleeAttackGoal` | Melee interplay |
| `MixinCommanderOrderFollow` | Squad/commander orders |

## SEM client/rendering

| Mixin | Purpose |
|---|---|
| `MixinUnitModelCrouch` | Procedural pose injection on SEM models — delegates to `UnitProceduralPoses` |
| `MixinUnitModelCrouch` tail | Recoil/reload/bolt/crouch/prone/use-item |
| `MixinCombatStateSmooth` | Smooths `CombatStateLayer` blend (helps VDF too) |
| `MixinHeadTrackingSmooth` | Smooths `HeadTrackingLayer` |
| `MixinUnitMeleeWeaponPose` | Melee item pose on gun-holding units |
| `MixinGunLayerMeleeItem` | Held-item stance blend math (mirrored in `VdfHeldItemLayer`) |
| `MixinGunTracer` | Tracer visuals |
| `MixinUniversalArmorLayer` | Armor layer fixes incl. marker bones |
| `MixinGeckoArmorTexture` | Gecko armor texture overrides |
| `MixinUnitRenderProne` / `MixinUnitProneDimensions` | Prone render + hitbox |

## Third-party

| Mixin | Purpose |
|---|---|
| `MixinNearestAttackableTargetGoal` | `@Accessor` for `NATG.targetType` — drives `UnitHostilityEvents` merged goals |
| `MixinBulletImpactSuppression` | Suppression state on bullet impact |
| `MixinCuriosHelper` (soft-dep) | Curios item lookups for unit effects |
| `client/MixinSemcsGuiMap` (client section) | Xaero worldmap integration hooks |
