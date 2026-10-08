# [SEM] Class System

A Forge 1.20.1 addon for **Simple Enemy Mod** that gives each faction its own
attribute set instead of sharing the global `unit_attributes` config values.

**Requires [Simple Enemy Mod](https://github.com/NekoYuni/Simple-Enemy-Mod) to be installed.**

## The problem

In SEM, `sem-server.toml` (`config -> unit_attributes`) applies a single
`health`, `speed`, and `detectionRange` to **every** unit — RU, US, and PMC
soldiers all end up with identical stats, and gun accuracy comes from the
global `gun_ai_shooting_config` section.

## What this mod does

Per-faction attributes, configurable per world in
`saves/<world>/serverconfig/sem_classsystem-server.toml`:

```toml
[ru_unit]
    health = 20.0          # 1.0 - 200.0
    speed = 0.27           # 0.05 - 1.5
    detectionRange = 96.0  # 32.0 - 384.0
    accuracySpreadMultiplier = 1.0  # scales bullet spread: <1 = more accurate, >1 = less accurate

[us_unit]
    # same keys

[pmc_unit]
    # same keys
```

Defaults match SEM's own defaults, so the mod is a no-op until you tune it.
Applied on spawn and on world load, so existing units pick up config changes.

## Phase 2 — Weapon classes

Every unit also rolls a **weapon class** at spawn. The class decides which gun
it carries, how it shoots, and the nickname in its name tag
(e.g. `US Unit - Sweeper`).

| Class | Nickname | Behaviour | Guns |
|---|---|---|---|
| SMG | Breacher | fast follow-up bursts, accuracy dies at range | UZI (RU), Vector (US), MP5 (PMC) |
| Rifle | Rifleman | untouched SEM baseline | AK-47 / Type-81 (RU), M4A1 / SCAR-L (US), HK G3 (PMC) |
| Shotgun | Sweeper | tight up close, useless past ~25 blocks | SPAS-12 (RU), AA-12 (US/PMC) |
| Sniper & RPG | Camper | single aimed shots, long pauses, very tight | SKS / RPG-7 (RU), M107 / AWP / RPG-7 (US), Mk14 / RPG-7 (PMC) |
| Heavy MG | Suppressor | sustained fire, decent spread | RPK (RU), M249 (US), Minigun (PMC) |

Per-class tuning lives in `[class_tuning.<class>]` — `accuracySpreadMultiplier`,
`rangeFalloffMultiplier`, `burstLengthMultiplier`, `cooldownMultiplier`, and the
displayed `nickname`. The class is stored in the unit's NBT, survives save/load,
and units saved before this mod get their class inferred from the gun they hold.

### How it works

- `MixinAbstractUnit` — injects into `finalizeSpawn`/`readAdditionalSaveData`/
  `addAdditionalSaveData`: rolls + persists the weapon class (`semcs$weaponClass`
  via injected `IClassifiedUnit` bridge), overwrites faction attributes, and
  sets the `Faction Unit - Nickname` name tag.
- `MixinWeaponEquippers` — one mixin over all three SEM equippers; redirects
  `UnitLoadoutManager.getRandomLoadout` to our per-class gun tables.
- `MixinRangedGunAttackGoal` — `@Mutable @Shadow`s the burst/cooldown finals
  (retuned per class after the ctor) and redirects
  `AiGunSpreadHelper.CalculateSpread` to scale spread by faction × class and
  the distance falloff by class.

No SEM code is copied or modified — everything is applied at runtime via mixins.

## Requirements

- Minecraft 1.20.1, Forge 47+
- [Simple Enemy Mod](https://github.com/NekoYuni/Simple-Enemy-Mod) `1.20.1-0.1.5-beta` up to `0.1.6` (covers `v0.1.6-beta.1`)
- TaCZ `1.1.4` – `1.1.8` (required by SEM)

## Building

Drop `simpleenemymod.jar` and `tacz.jar` into `libs/`, then:

```
gradlew build
```

Output: `build/libs/sem_classsystem-<version>.jar`

## Credits

- **[NekoYuni](https://github.com/NekoYuni)** — original author of
  [Simple Enemy Mod](https://github.com/NekoYuni/Simple-Enemy-Mod), which this
  addon depends on and extends. All soldier AI, entities, and core systems are
  his work.
- **[Timeless and Classics Zero (TaCZ)](https://www.curseforge.com/minecraft/mc-mods/timeless-and-classics-zero)** — gun system used by SEM.
- Extension architecture inspired by [tacz-simple-enemies-vehicular-warfare](https://github.com/neoalive/tacz-simple-enemies-vehicular-warfare).

## License

GPLv3 — same as Simple Enemy Mod. This addon modifies SEM behavior at runtime
and is distributed as a GPLv3 derivative work.
