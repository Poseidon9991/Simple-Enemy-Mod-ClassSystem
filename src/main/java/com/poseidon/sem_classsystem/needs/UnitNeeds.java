// Needs package - hunger / energy / morale state for owned units
package com.poseidon.sem_classsystem.needs;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.entity.BhqUnitEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

// All unit-need state lives on the entity's persistentData compound so it
// saves/loads with the unit for free. Everything in here is a no-op unless
// [needs].enabled is set in the config - with the toggle off the drain tick
// never runs and the goals never start, so units behave like plain SEM.
//
// Applies to OWNED units only (recruited PMC + BHQ, i.e. PmcUnitEntity with
// an owner UUID). Wild RU/US patrols and unrecruited PMCs are untouched.
public final class UnitNeeds {

    public static final int MAX = 20;          // hunger + energy scale (MC-like)
    public static final int MORALE_MAX = 100;
    public static final int MORALE_BASE = 70;  // morale drifts back to this

    // Energy at/below this and the unit wants a bunk; above WAKE_AT it
    // climbs back out of bed.
    public static final int EXHAUSTED_AT = 3;
    public static final int WAKE_AT = MAX;

    private static final String TAG_HUNGER = "semcs_needs_hunger";
    private static final String TAG_ENERGY = "semcs_needs_energy";
    private static final String TAG_MORALE = "semcs_needs_morale";
    private static final String TAG_LAST = "semcs_needs_last";   // gameTime of last drain
    private static final String TAG_STARVING = "semcs_needs_starving"; // ticks at 0 hunger
    private static final String TAG_BED = "semcs_needs_bed";     // BlockPos.asLong, claimed bunk
    private static final String TAG_SHIFT = "semcs_needs_shift"; // SHIFT_AUTO / DAY / NIGHT
    private static final String TAG_BARKED = "semcs_needs_barked"; // bitmask of fired barks

    // Shift schedule: AUTO sleeps whenever exhausted (default behaviour),
    // DAY-shift units work the day and bunk down at night, NIGHT-shift is
    // the reverse - lets a player run a real watch rotation on posts.
    public static final int SHIFT_AUTO = 0;
    public static final int SHIFT_DAY = 1;
    public static final int SHIFT_NIGHT = 2;

    // Bark thresholds - one message each time a stat crosses INTO the bad
    // zone; the mask bit clears when the stat recovers, so a unit that
    // gets fed and goes hungry again barks again.
    private static final int BARK_HUNGRY = 1;    // hunger <= 6
    private static final int BARK_STARVING = 2;  // hunger <= 0
    private static final int BARK_TIRED = 4;     // energy <= EXHAUSTED_AT
    private static final int BARK_SHAKEN = 8;    // morale < 30

    private UnitNeeds() {
    }

    // Does the needs system apply to this entity right now? Cheap check used
    // by every goal/event gate.
    public static boolean appliesTo(Entity entity) {
        return ClassSystemConfig.needsEnabled()
                && entity instanceof PmcUnitEntity pmc
                && pmc.getOwnerUUID() != null;
    }

    // ---- stats --------------------------------------------------------------

    public static int hunger(PmcUnitEntity unit) {
        CompoundTag data = unit.getPersistentData();
        ensureInit(unit, data);
        return (int) Math.ceil(data.getFloat(TAG_HUNGER));
    }

    public static int energy(PmcUnitEntity unit) {
        CompoundTag data = unit.getPersistentData();
        ensureInit(unit, data);
        return (int) Math.ceil(data.getFloat(TAG_ENERGY));
    }

    public static int morale(PmcUnitEntity unit) {
        CompoundTag data = unit.getPersistentData();
        ensureInit(unit, data);
        return data.getInt(TAG_MORALE);
    }

    // Units that existed before [needs] was switched on (or before their
    // first drain tick) start fed, rested and steady instead of starving.
    private static void ensureInit(PmcUnitEntity unit, CompoundTag data) {
        if (!data.contains(TAG_LAST)) {
            data.putFloat(TAG_HUNGER, MAX);
            data.putFloat(TAG_ENERGY, MAX);
            data.putInt(TAG_MORALE, MORALE_BASE);
            data.putLong(TAG_LAST, unit.level().getGameTime());
        }
    }

    public static boolean isStarving(PmcUnitEntity unit) {
        return hunger(unit) <= 0;
    }

    // True while the unit is below the rest threshold OR currently asleep -
    // duty goals consult this so an exhausted guard actually leaves his post.
    public static boolean needsRest(PmcUnitEntity unit) {
        return unit.isSleeping() || energy(unit) <= EXHAUSTED_AT;
    }

    // Starving units hold fire - they are too weak to fight, not dead yet.
    public static boolean refusesToFight(Entity entity) {
        return entity instanceof PmcUnitEntity pmc && appliesTo(pmc) && isStarving(pmc);
    }

    // Morale-driven accuracy scale for UnitClassAttributes.spreadMultiplier:
    // full morale -> x1.0, zero morale -> x1.6 spread (shaky hands).
    public static double spreadPenaltyScale(Entity entity) {
        if (!(entity instanceof PmcUnitEntity pmc) || !appliesTo(pmc)) {
            return 1.0;
        }
        double deficit = (MORALE_MAX - morale(pmc)) / (double) MORALE_MAX;
        return 1.0 + deficit * 0.6;
    }

    // Low morale + getting shot = a chance the unit breaks for cover. Read
    // by UnitNeedsEvents.onHurt.
    public static boolean shouldBreak(PmcUnitEntity unit) {
        return appliesTo(unit)
                && morale(unit) < 30
                && unit.getRandom().nextFloat() < 0.35F;
    }

    // ---- lifecycle ----------------------------------------------------------

    // Called from LivingTickEvent. Drain is measured in elapsed game-time,
    // not "goal ticks", so units keep getting hungry while their chunk was
    // unloaded - an abandoned outpost starves even if nobody watched it.
    public static void tick(PmcUnitEntity unit) {
        if (!appliesTo(unit)) {
            return;
        }
        CompoundTag data = unit.getPersistentData();
        ensureInit(unit, data);   // first contact: fed, rested, steady
        long now = unit.level().getGameTime();
        long elapsed = Math.min(now - data.getLong(TAG_LAST), 24000L * 3);   // cap offline catch-up at 3 days
        data.putLong(TAG_LAST, now);
        if (elapsed <= 0) {
            return;
        }

        double rate = unit instanceof BhqUnitEntity
                ? ClassSystemConfig.bhqDrainMultiplier() : 1.0;
        if (unit.getTarget() != null && unit.getTarget().isAlive()) {
            rate *= ClassSystemConfig.combatDrainMultiplier();
        }

        float hunger = data.getFloat(TAG_HUNGER)
                - (float) (elapsed * rate / ClassSystemConfig.hungerDrainTicks());
        data.putFloat(TAG_HUNGER, Math.max(0, hunger));

        // Sleeping units still get hungry but don't tire further; energy
        // only refills via UnitSleepGoal.
        if (!unit.isSleeping()) {
            float energy = data.getFloat(TAG_ENERGY)
                    - (float) (elapsed * rate / ClassSystemConfig.energyDrainTicks());
            data.putFloat(TAG_ENERGY, Math.max(0, energy));
        }

        tickMorale(unit, data, elapsed);
        tickStarvation(unit, data, elapsed);
        tickBarks(unit, data);
    }

    private static void tickMorale(PmcUnitEntity unit, CompoundTag data, long elapsed) {
        int morale = data.getInt(TAG_MORALE);
        double delta = 0;
        if (data.getFloat(TAG_HUNGER) <= 0) {
            delta -= elapsed / 600.0;    // starving grinds morale fast
        }
        if (data.getFloat(TAG_ENERGY) <= EXHAUSTED_AT) {
            delta -= elapsed / 1200.0;
        }
        if (delta == 0) {
            // Healthy unit drifts back toward baseline.
            delta = (MORALE_BASE - morale) * Math.min(1.0, elapsed / 2400.0) * 0.05;
        }
        data.putInt(TAG_MORALE, clamp((int) Math.round(morale + delta), 0, MORALE_MAX));
    }

    private static void tickStarvation(PmcUnitEntity unit, CompoundTag data, long elapsed) {
        if (data.getFloat(TAG_HUNGER) > 0) {
            data.putInt(TAG_STARVING, 0);
            return;
        }
        int starving = data.getInt(TAG_STARVING) + (int) elapsed;
        if (starving >= ClassSystemConfig.starveDamageTicks()) {
            starving -= ClassSystemConfig.starveDamageTicks();
            // Vanilla starve damage - death runs SEM's dropCustomDeathLoot,
            // so the gear scatters on the ground as items.
            DamageSources sources = unit.damageSources();
            unit.hurt(sources.starve(), 1.0F);
        }
        data.putInt(TAG_STARVING, starving);
    }

    // Compares current stats against the barked-bitmask and radios the
    // owner for each NEW threshold crossing. Styled like radio chatter.
    private static void tickBarks(PmcUnitEntity unit, CompoundTag data) {
        if (!ClassSystemConfig.needsBarks()) {
            return;
        }
        int mask = 0;
        if (data.getFloat(TAG_HUNGER) <= 0) mask |= BARK_STARVING;
        else if (data.getFloat(TAG_HUNGER) <= 6) mask |= BARK_HUNGRY;
        if (data.getFloat(TAG_ENERGY) <= EXHAUSTED_AT) mask |= BARK_TIRED;
        if (data.getInt(TAG_MORALE) < 30) mask |= BARK_SHAKEN;

        int fired = data.getInt(TAG_BARKED);
        int fresh = mask & ~fired;
        data.putInt(TAG_BARKED, mask);
        if (fresh == 0) {
            return;
        }
        if ((fresh & BARK_STARVING) != 0) {
            bark(unit, "* stomach growling - needs food NOW *");
        } else if ((fresh & BARK_HUNGRY) != 0) {
            bark(unit, "\"Running low on rations.\"");
        }
        if ((fresh & BARK_TIRED) != 0) {
            bark(unit, "\"I need rack time soon.\"");
        }
        if ((fresh & BARK_SHAKEN) != 0) {
            bark(unit, "\"I'm losing it out here...\"");
        }
    }

    // One bark = a gray italic radio line to the owner, if online.
    private static void bark(PmcUnitEntity unit, String line) {
        java.util.UUID owner = unit.getOwnerUUID();
        if (owner == null || !(unit.level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return;
        }
        net.minecraft.server.level.ServerPlayer player = sl.getServer().getPlayerList().getPlayer(owner);
        if (player == null) {
            return;
        }
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[")
                .append(unit.getDisplayName().copy())
                .append(net.minecraft.network.chat.Component.literal("] " + line))
                .withStyle(net.minecraft.ChatFormatting.GRAY, net.minecraft.ChatFormatting.ITALIC));
    }

    // ---- shift schedule -------------------------------------------------------

    public static int shift(PmcUnitEntity unit) {
        return unit.getPersistentData().getInt(TAG_SHIFT);
    }

    public static void setShift(PmcUnitEntity unit, int shift) {
        unit.getPersistentData().putInt(TAG_SHIFT, shift);
    }

    // Whether this unit is allowed to bunk down right now. AUTO units sleep
    // whenever they are exhausted; DAY/NIGHT shift units wait for their
    // off-shift half of the cycle.
    public static boolean canSleepNow(PmcUnitEntity unit) {
        return switch (shift(unit)) {
            case SHIFT_DAY -> unit.level().isNight();
            case SHIFT_NIGHT -> unit.level().isDay();
            default -> true;
        };
    }

    public static String shiftLabel(int shift) {
        return switch (shift) {
            case SHIFT_DAY -> "Day shift";
            case SHIFT_NIGHT -> "Night shift";
            default -> "Auto";
        };
    }

    // ---- events (called by goals / listeners) --------------------------------

    // A unit ate one food item: nutrition fills hunger 1:1, a meal is a
    // small morale bump too.
    public static void feed(PmcUnitEntity unit, ItemStack stack) {
        if (!appliesTo(unit)) {
            return;
        }
        FoodProperties food = stack.getFoodProperties(unit);
        CompoundTag data = unit.getPersistentData();
        ensureInit(unit, data);
        float hunger = data.getFloat(TAG_HUNGER)
                + (food != null ? food.getNutrition() : 2);
        data.putFloat(TAG_HUNGER, Math.min(MAX, hunger));
        addMorale(unit, 3);
    }

    public static void onKill(PmcUnitEntity unit) {
        addMorale(unit, 4);
    }

    // Nearby squadmate died - everyone in earshot loses heart.
    public static void onAllyDeath(PmcUnitEntity unit) {
        addMorale(unit, -8);
    }

    // Full night's (or day's - shifts don't care) rest.
    public static void onRested(PmcUnitEntity unit) {
        addMorale(unit, 15);
    }

    public static void addMorale(PmcUnitEntity unit, int delta) {
        if (!appliesTo(unit)) {
            return;
        }
        CompoundTag data = unit.getPersistentData();
        data.putInt(TAG_MORALE, clamp(data.getInt(TAG_MORALE) + delta, 0, MORALE_MAX));
    }

    // +1 energy per call - UnitSleepGoal paces this by sleepEnergyTicks.
    public static void restoreEnergy(PmcUnitEntity unit) {
        CompoundTag data = unit.getPersistentData();
        ensureInit(unit, data);
        data.putFloat(TAG_ENERGY, Math.min(MAX, data.getFloat(TAG_ENERGY) + 1));
    }

    // ---- bed binding --------------------------------------------------------

    // The bunk this unit claimed (BlockPos.asLong), 0 when unassigned.
    public static long bedPos(PmcUnitEntity unit) {
        return unit.getPersistentData().getLong(TAG_BED);
    }

    public static void claimBed(PmcUnitEntity unit, BlockPos pos) {
        unit.getPersistentData().putLong(TAG_BED, pos.asLong());
    }

    public static void releaseBed(PmcUnitEntity unit) {
        unit.getPersistentData().remove(TAG_BED);
    }

    // Pull the unit out of bed: clears the sleeping pose (SAFE: no-op when
    // not sleeping) and frees the bunk for the next exhausted squadmate.
    public static void wake(PmcUnitEntity unit) {
        if (unit.isSleeping()) {
            unit.stopSleeping();
        }
        releaseBed(unit);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
