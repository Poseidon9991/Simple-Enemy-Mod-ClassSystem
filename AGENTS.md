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
    via `AbstractSoftDepMixinPlugin` (Curios, Xaero)
  - `entity/ai/` — unit goals (medic, self-heal, melee, chunk duty,
    fallback, eat, sleep)
  - `needs/UnitNeeds.java` — hunger/energy/morale state on owned units
    (persistentData NBT); everything gates on `[needs].enabled` config,
    default off = units behave like plain SEM. Drain uses elapsed
    game-time so offline catch-up applies.
  - `network/` — Forge packets; decoders cap client-supplied counts
    (`PacketAssignRoute.MAX_UNITS` convention)
  - `world/` — SavedData (chunk claims, unit name registry)
  - `client/` + `client/xaero/` — tablet/map UI; Xaero types must stay out
    of `client/` root so packets class-load without the map mod
  - `menu/`, `block/` — recruit tables (PMC + BHQ) and guard posts
- `libs/` — local jars (SEM, TaCZ, GeckoLib, Curios, Superb Warfare, WTHIT)

## Conventions

- Mixin injected/unique members use the `semcs$` prefix and `@Unique`.
- Private mixin fields/constants should be `@Unique` to avoid target
  collisions.
- `ForgeRegistries.getKey` is `@Nullable` — always null-guard before use.
- Curios is a soft dependency: accessed reflectively or behind
  `ModList.get().isLoaded("curios")` checks.
- Commits are authored by the repo owner (Poseidon9991).

<!-- FUSION-ORCHESTRATOR:START -->
# Fusion Multi-Agent Collaboration Protocol

Connected to **Fusion Desktop Orchestrator** via Streamable HTTP MCP at `http://127.0.0.1:4545/mcp`.
Active Workspace Mode: **`live_folder`**.

## Connected IDE Roster
- **`antigravity` (Antigravity Desktop)** — Role: *Lead Architect* | Active Model: `Gemini 3.8 Flash (High)`
- **`devin` (Devin Desktop)** — Role: *Backend & Implementer* | Active Model: `swe-2-max`

## Mandatory 5-Step MCP Teamwork Protocol
1. **`fusion_sync_session({ agent_id })`** — Call this FIRST when starting any task to read the global project goal, active workspace mode, teammate roster, active file locks, and recent checkpoints.
2. **`fusion_claim_locks({ agent_id, file_paths, reason })`** — In `live_folder` mode, ALWAYS claim locks on target files BEFORE editing them. If `success: false`, do not overwrite files owned by `blocked_by`; coordinate or work on unlocked files.
3. **`fusion_publish_checkpoint({ agent_id, summary, modified_files })`** — Publish concise progress summaries after meaningful milestones so teammates stay synchronized without reading raw transcripts.
4. **`fusion_send_a2a_message({ from_agent, to_agent, task_prompt, context_files, auto_wake })`** — Delegate subtasks or request reviews directly from another IDE agent. Fusion automatically wakes the target IDE when `auto_wake` is `true`.
5. **`fusion_complete_and_unlock({ agent_id, final_summary })`** — Call this when your task is finished to release all owned file locks, record the final checkpoint, and advance the Master Router sequential queue.
<!-- FUSION-ORCHESTRATOR:END -->
