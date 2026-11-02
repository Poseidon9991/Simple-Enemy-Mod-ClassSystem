# Needs & Veterancy

## `UnitNeeds` (`needs/UnitNeeds.java`)

Hunger / energy / morale state stored on owned units in `persistentData`
NBT. Everything gates on the `[needs].enabled` config — default **off** =
units behave like plain SEM. Drain uses elapsed game-time so offline
catch-up applies (a unit left for a day hasn't secretly starved).

`UnitNeedsEvents` is the tick driver — morale derives from ally proximity
(the `ALLY_MORALE_RADIUS_SQR` constant was removed as dead code when the
formula changed; the live radius lives in the event file).

## Upkeep goals

| Goal | Behavior |
|---|---|
| `UnitEatGoal` | Find food (inventory/supply crate) and eat |
| `UnitSleepGoal` | Sleep at night — client shows `ZzzParticle` |
| `UnitSelfHealGoal` / `UnitMedicHealGoal` | Bandage (IFAK-curio aware) |
| `UnitResupplyGoal` / `StockpileSearch` | Fetch from the stockpile |

## Veterancy (`veterancy/UnitVeterancy.java` + `event/VeterancyEvents.java`)

Kills and survival time raise a unit's rank; `rankTitle`/`RANK_TITLES`
(short strings) drive nameplates; veterancy multiplies into
`UnitClassAttributes` (spread, speed). Veterancy state persists in the
unit's persistent NBT.

## Naming

`UnitNameRegistry` (`world/` SavedData) hands out persistent names;
`MixinPmcUnitNaming` hooks SEM naming so a recruited unit keeps its name
across relogs/deaths.
