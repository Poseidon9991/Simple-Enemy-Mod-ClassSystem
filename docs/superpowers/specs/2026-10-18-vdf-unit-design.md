# VDF — Village Defense Force (design spec)

Date: 2026-10-18 · Status: approved design, pre-implementation

## Intent

Add a new factionless militia unit — **VDF (Village Defense Force)** — that
lives in villages, patrols and protects them, responds to pillager raids,
and has a real villager head. It is **unrecruitable**, **neutral to the
player/PMC/BHQ**, and **hostile to monsters, raiders, and RU/US units**.

## Entity

`VdfUnitEntity extends net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity`

Rationale: RU/US are SEM's *enemy-line* units. Compared to `PmcUnitEntity`
(the BHQ base), RU gives exactly the traits the user asked for:

- **Unlimited ammo** — `equipRandomGun()` delegates to
  `RuWeaponEquipper.equipRandomGun(living, random)`; RU units never consume
  inventory ammo. Our `MixinUnitAmmoReserve` patches `RuWeaponEquipper`
  already, so reserve-ammo behavior carries over.
- **No inventory GUI / no recruit / no owner** — `RUunitEntity` declares no
  `mobInteract`, no `OWNER_UUID`, no menu. Unopenable *by nature*, not by
  a flag we have to defend.
- **Combat core** — `role.getGoals().addGoals(this)` yields
  `RangedGunAttackGoal`, `SeekCoverGoal`, `PeekFromCoverGoal`,
  `TacticalManeuverGoal`, `SmartSquadDoorGoal`, `MoveToAttackRangeGoal`,
  `FloatGoal` — identical between `AbstractUnitGoals` and
  `AbstractFriendlyGoals` (verified via bytecode: both trees register the
  same movement/combat goals; targeting is per-entity, not per-role).
- `DATA_VARIANT_ID` + `variant` inherited → militia skin variants.
- Our `MixinAbstractUnitGoals` already injects `UnitMeleeAttackGoal`,
  `UnitSelfHealGoal` etc. into `AbstractUnitGoals` — VDF gets melee for free
  (used by the melee militia subset).

Registered as `sem_classsystem:vdfunit`, `MobCategory.MONSTER`,
`sized(0.6F, 1.95F)`, `clientTrackingRange(256)` — same box as BHQ/PMC.
Attribute table via `AbstractUnit.createAttributes()` + a `[vdf_unit]`
config profile like `[bhq_unit]`.

**Not** added to `simpleenemymod:pmc_units` (nor ru/us) entity-type tags —
VDF stays off every SEM faction roster.

### `setupRoleGoals()` — overridden

RU's own impl registers `HurtByTargetGoal` + ~8 `VerticalAwareTargetGoal`s
against its enemy list, then `role.getGoals().addGoals(this)`. VDF replaces
the target list:

| Target goals (VDF)                       | Never targets                |
|------------------------------------------|------------------------------|
| `Monster` (zombies, skeletons, creepers) | `Player`                     |
| `Raider` (pillagers, vindicators, witch, ravager, evoker, vex) | `AbstractVillager`, `IronGolem` |
| `UnitFactionTags.isRuUnit` / `isUsUnit`  | PMC + BHQ (`isPmcUnit`), other VDF |
| `HurtByTargetGoal` retaliation + `setAlertOthers` on VDF kind | players' owned pets |

### Friendly-fire shield ("don't hit the villager")

Two layers:

1. **Target selection** — VDF never *aims* at villagers/golems.
2. **Bullet veto** — `VdfFriendlyFireEvents` subscribes to TaCZ's
   `@Cancelable EntityHurtByGunEvent.Pre` (verified cancelable in tacz.jar):
   `attacker instanceof VdfUnitEntity && hurtEntity instanceof
   (AbstractVillager | IronGolem | VdfUnitEntity)` → `setCanceled(true)`.
   A villager straying into the line of fire takes zero damage.
   (Melee militia can't stray-hit: melee only connects with the current
   target, which is already filtered.)

## Rendering — villager head overlay layer

`VdfUnitRenderer extends net.nekoyuni.SimpleEnemyMod.entity.client.pmc_unit.PmcUnitRenderer`
(same reuse pattern as `BhqUnitRenderer`) + `VdfHeadLayer extends RenderLayer`:

- Renders vanilla's `VillagerHeadModel` (real 3D nose + unibrow geometry)
  with the vanilla villager texture, parented to the unit head bone —
  hides the humanoid face, shows a genuine villager face.
- All SEM layers survive: gun layer, armor layers, hurt/walk/death/
  combat animations.
- Body texture: militia skin variants (placeholder repaint of an existing
  faction texture is acceptable; user repaints later).

## Armament — mixed militia

Per-unit roll at spawn (`gunnerChance` config, default 0.6):

- **Gunner** — `equipRandomGun()` → pistol/rifle pool via
  `RuWeaponEquipper`, unlimited ammo, `RangedGunAttackGoal`.
- **Melee** — iron sword or axe equipped to main hand; fights via the
  existing `UnitMeleeAttackGoal` (mixin-injected on all SEM units).

Loadout table `data/sem_classsystem/class_loadouts/vdf_units.json` optional —
gunners default to the RU gun pool if absent.

## Behavior goals (added over the combat core)

| Goal | Priority | Behavior |
|------|----------|----------|
| `VdfRaidResponseGoal` | 1 | `ServerLevel.getRaidAt(villageCenter)` → on raid start play a horn/villager alarm hum, converge on raid center, prioritize `Raider` targets. Ends → resume patrol. |
| `VdfPatrolGoal` | 4 | Walks a waypoint loop between village POIs (bells/beds/workstations via `PoiManager`). Leashed to `patrolRadius` (default 64) from village center — breaks off chases beyond the leash. |
| `VdfInteractGoal` | 7 | Idle within ~3 blocks of `Villager`/`IronGolem` → face it, pause 40–60 ticks, play `SoundEvents.VILLAGER_YES`. "Checking on them" beat. |

Voice: `getCustomHurtSound/DeathSound/AlertSound` overridden →
`SoundEvents.VILLAGER_HURT` / `VILLAGER_DEATH` / `VILLAGER_AMBIENT` variants.
No SEM voice clips — villager hums are the flavor.

## Village spawning — one-time garrison

- `EntityJoinLevelEvent` on `Villager` (natural joins, not mod spawns):
  if **no living VDF within 96 blocks** of the villager's position AND the
  village anchor isn't recorded → spawn `garrisonSize` VDF (default 3)
  spread near the village bell/meeting point.
- `VillageDefenseSavedData` (overworld SavedData) stores garrisoned village
  anchors (BlockPos set) → several villagers joining the same tick cannot
  double-spawn; anchor cleared when no VDF remain within radius so a *new*
  villager arrival later re-garrisons (user rule: "dead stays dead until
  new villagers spawn").
- Config `enabled` + `garrisonSize`; spawn respects the canopy/collision
  validation already used by SEM spawns.

## Armor set — `vdf_*` militia kit (generated like the other sets)

- `vdf_helmet`, `vdf_vest`, `vdf_pants`, `vdf_boots` — `BhqArmorItem`
  subclass instances, GeckoLib geo + PNG generated same as the existing 9
  sets (militia theme: leather/tan civilian kit + worn steel pot).
- **No recipes** → uncraftable. Creative tab only (own `semcs` tab).
- Entity loot table `entities/vdfunit.json`: ~3% per piece
  (`armorDropChance` config where practical — static loot table uses the
  default, config drives any code-added rolls).

## Config — `[vdf_unit]` in `ClassSystemConfig`

`enabled` (true), `garrisonSize` (3), `gunnerChance` (0.6), `health`,
`damage`, `patrolRadius` (64), `armorDropChance` (0.03) — mirrors the
`[bhq_unit]` section shape.

## Files touched / added

| Area | Files |
|------|-------|
| Entity | `entity/VdfUnitEntity.java` (new) |
| Goals | `entity/ai/VdfRaidResponseGoal.java`, `VdfPatrolGoal.java`, `VdfInteractGoal.java` (new) |
| Events | `event/VdfFriendlyFireEvents.java`, `event/VdfGarrisonEvents.java` (new) |
| SavedData | `world/VillageDefenseSavedData.java` (new) |
| Registry | `SemcsRegistries.java` — VDF entity, 4 armor items, spawn egg, attributes |
| Config | `config/ClassSystemConfig.java` — `[vdf_unit]` section |
| Client | `client/VdfUnitRenderer.java`, `client/VdfHeadLayer.java`; entity renderer registration |
| Item | `item/VdfArmorItem.java` or reuse `BhqArmorItem` |
| Assets | geo + textures for 4 armor pieces; vdf entity texture variants; spawn egg model; lang entries |
| Data | `data/sem_classsystem/loot_tables/entities/vdfunit.json`; optional `class_loadouts/vdf_units.json` |
| Docs | README feature list + developmentflow.md |

## Non-goals

- No recruit/command/tablet ownership — VDF is intentionally outside the
  owned-squad systems (no `[needs]`, no orders, no markers on your roster).
- No SEM faction tag membership.
- No new entity model — body reuses SEM's unit model; the villager head is
  an overlay layer.

## Risks / verification points

- `setupRoleGoals` override shape must mirror RU's (role goals call +
  our own targetSelector entries) — verify against bytecode during impl.
- `VillagerHeadModel` bone parenting vs SEM model head bone — may need a
  small offset; visually verify in-game.
- `PoiManager` nearest-POI scan cost — run on a slow tick (every ~40 ticks),
  not per-tick.
- `VillageDefenseSavedData` anchors must be dimension-aware (persist in
  overworld data, key by pos).
