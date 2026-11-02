# Loadouts & Weapon Classes

## Weapon classes (`WeaponClass`)

`RIFLE`, `SHOTGUN`, `SMG`, `SNIPER`, `HEAVY` (launchers), `PISTOL`, `JACK`
(jack-of-all-trades pool), `MELEE`. Used for loadout tables, attributes
(`UnitClassAttributes.spreadMultiplier`, recoil scale), recruit preferences,
and — after the `effectiveClass` inference — animation pose selection.

`WeaponClassSupport` maps a held gun id → class (via `ClassLoadouts.inferClass`),
drives recruit-table menu options and class badges.

## Datapack tables

`data/*/class_loadouts/*.json`, reloaded by `ClassLoadoutManager`
(a `SimpleJsonResourceReloadListener`). Shape:

```json
{ "classes": { "shotgun": { "m870": { "gun_id": "tacz:m870",
    "ammo_count": 7, "fire_mode": "SEMI" } } } }
```

`ClassLoadouts.pick("vdf_units", class, random)` returns a `UnitLoadout`.
Guns resolve through `GunItemBuilder` — gunpack ids (`bf1:`, `sfms:`,
`maxstuff:`, `lradd:`) work if the pack is installed; a missing pack yields
an empty stack → the caller must fall back (VDF does via `MILITIA_GUNS`).

## Equippers (SEM) + our mixins

- `PmcUnitWeaponEquipper.equipRandomGun(unit, random)` — writes gun to
  handler slot 0, then `loadout.ammoCount × 6` into the first empty slot
  (starting at slot 1 — the oversized-stack bug, see inventory-ammo.md)
- `RuWeaponEquipper` / `UsWeaponEquipper` — same pattern for enemies
- `MixinUnitAmmoReserve` injects at TAIL of all three → armor set +
  `UnitAmmoService.ensurePrimaryAmmo` + backup sidearm/food
- `MixinWeaponEquippers` — routes weapon-class selection through the
  SEMC `WeaponClass` tables
- `MixinRecruitTable` + `RecruitTableMenu`/`RecruitTableScreen` — class-pick
  buttons replaced the old sneak-click `cycleRecruitPreference` path
  (now removed as dead code)

## Attributes

`UnitClassAttributes` scales health/damage/speed/spread per faction+class.
Veterancy (`veterancy/UnitVeterancy`) layers rank-based multipliers on top —
`rankTitle`/`RANK_TITLES` drive nameplates (short titles only; the long
`RANK_NAMES` table was dead code and removed).
