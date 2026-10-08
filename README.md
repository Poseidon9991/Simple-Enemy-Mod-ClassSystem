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

### How it works

- `MixinAbstractUnit` — injects at the tail of `AbstractUnit.finalizeSpawn`
  (and `readAdditionalSaveData` for reloads), overwriting health /
  movement speed / follow range per faction via SEM's `UnitFactionTags`.
- `MixinRangedGunAttackGoal` — redirects `AiGunSpreadHelper.CalculateSpread`
  to scale the shooter's base spread by the faction's accuracy multiplier.

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
