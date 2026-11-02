# Unit Inventory & Ammo

## SEM `UnitInventoryHandler` layout (18 slots, `ItemStackHandler`)

| Slots | Contents |
|---|---|
| 0 | Gun (main hand mirror — `onContentsChanged` syncs `EquipmentSlot.MAINHAND`) |
| 1 | Offhand |
| 2–5 | Armor (feet/legs/chest/head via `syncEquipmentToInventory`) |
| 6–17 | Storage — where reserve ammo belongs |

`PmcUnitMenu` exposes all 18 to the GUI; `getCapability(ITEM_HANDLER)`
returns it as `IItemHandlerModifiable`.

## The oversized-stack bug

`ItemStack` count serializes as a **signed byte** — SEM writes
`ammoCount × 6` (e.g. 180) as one stack in the first free slot. On
save/network sync, >127 overflows negative → `ItemStack.EMPTY`. That's the
root of "empty ammo slot" reports: corrupted stacks vanish on load/sync.

## `UnitAmmoService` (shared, no weaving)

`ensurePrimaryAmmo(entity)` — idempotent, safe to re-run:

1. Find the gun — mainhand first, handler slot 0 as fallback
2. Resolve `gunId → getCommonGunIndex → getGunData().getAmmoId()`
   (null-guarded — gunpack guns without a `data` section used to NPE here)
3. Relocate stray ammo out of slots 1–5 into storage
4. Split existing stacks > `MAX_SAFE_STACK` (60)
5. Top up to `MIN_RESERVE_ROUNDS` (240) via `AmmoItemBuilder`,
   validating the built stack is a real `IAmmo`

Call sites: `MixinUnitAmmoReserve` (equipper TAIL — fresh spawns) and
`MixinAbstractUnit` (`readAdditionalSaveData` TAIL — repairs old
ammo-corrupted saves on chunk load). `[SEMCAmmo]` warn logs name the exact
gate that skipped: no gun, missing handler, unresolvable ammoId, or
unbuildable ammo stack.

## Related ammo systems

- `UnitAmmoBoxGoal` — units physically resupply from `tacz:ammo_box`
  stacks (in inventory or Curios `back`/`belt` slots via `MixinCuriosHelper`)
- `MixinPmcAmmoPlacement` — earlier placement pass; the service supersedes it
- VDF guns get `setMaxDummyAmmoAmount(MAX_VALUE)` + `dummyAmmo=9999` — no
  physical ammo needed (SEM convention for non-player units)
