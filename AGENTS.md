# [SEM] Class System

Minecraft 1.20.1 Forge mod (Forge 47.4.0, Java 17) adding a weapon-class
system on top of Simple Enemy Mod (SEM). Gradle + ForgeGradle 6,
Parchment mappings, MixinGradle.

## Build & verify

- Compile: `gradlew compileJava`
- Full build: `gradlew build`
- No test sources exist (`src/main` only) — `check` is a no-op beyond compile.
- Gradle JVM args: `-Xmx3G`, daemon disabled.

## Layout

- `src/main/java/com/poseidon/sem_classsystem/` — mod sources
  - `faction/` — `WeaponClass` (assignable classes vs `pistol` subclass),
    `UnitClass` (factions), `ClassLoadouts`, `WeaponClassSupport`
  - `data/ClassLoadoutManager.java` — datapack reload listener for
    `data/*/class_loadouts/*.json` (faction -> class -> gun pool)
  - `mixin/` — patches against SEM unit/AI/client classes; soft-dep gates
    via `AbstractSoftDepMixinPlugin` (Curios, Xaero, SEWV)
  - `entity/ai/` — unit goals (medic, self-heal, melee, chunk duty, fallback)
  - `network/` — Forge packets; decoders cap client-supplied counts
    (`PacketAssignRoute.MAX_UNITS` convention)
  - `world/` — SavedData (chunk claims, unit name registry)
  - `client/` + `client/xaero/` — tablet/map UI; Xaero types must stay out
    of `client/` root so packets class-load without the map mod
  - `menu/`, `block/` — recruit tables (PMC + BHQ) and guard posts
- `libs/` — local jars (SEM, TaCZ, GeckoLib, Curios, SEWV, Superb Warfare, WTHIT)
- `_gen_*.py` — one-off asset-generation scripts (dev tooling, not shipped)

## Conventions

- Mixin injected/unique members use the `semcs$` prefix and `@Unique`.
- Private mixin fields/constants should be `@Unique` to avoid target
  collisions.
- `ForgeRegistries.getKey` is `@Nullable` — always null-guard before use.
- Curios is a soft dependency: accessed reflectively or behind
  `ModList.get().isLoaded("curios")` checks.
- Commits are authored by the repo owner (Poseidon9991).
