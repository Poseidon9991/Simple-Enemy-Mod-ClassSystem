// Entity package - downed-not-dead state on owned units
package com.poseidon.sem_classsystem.entity;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Pose;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

// Mirrors PlayerRevive's player flow for units: a lethal hit collapses the
// unit (no AI, no fighting) and starts a bleedout clock; a revive brings it
// back standing. State lives in persistentData NBT so it survives chunk
// unloads and restarts. PlayerRevive itself only ever touches players, so
// this stays fully standalone - installing that mod changes nothing here.
public final class DownedState {

    private static final String KEY_DOWNED = "semcs:downed";
    private static final String KEY_UNTIL = "semcs:downedUntil";   // gameTime

    private DownedState() {
    }

    public static boolean isDowned(PmcUnitEntity unit) {
        return unit.getPersistentData().getBoolean(KEY_DOWNED);
    }

    // Collapse the unit: freeze AI, pin to the ground, start the clock.
    public static void down(PmcUnitEntity unit) {
        CompoundTag data = unit.getPersistentData();
        data.putBoolean(KEY_DOWNED, true);
        data.putLong(KEY_UNTIL, unit.level().getGameTime()
                + ClassSystemConfig.downedBleedoutTicks());
        unit.setHealth(Math.min(1.0F, unit.getMaxHealth()));
        unit.setTarget(null);
        unit.releaseMovementLock();
        unit.setNoAi(true);
        unit.setPose(Pose.SWIMMING);   // prone - models that honour pose lie flat
        unit.setShiftKeyDown(true);
    }

    // Stand the unit back up at `frac` of max health.
    public static void revive(PmcUnitEntity unit, double frac) {
        unit.getPersistentData().remove(KEY_DOWNED);
        unit.getPersistentData().remove(KEY_UNTIL);
        unit.setNoAi(false);
        unit.setShiftKeyDown(false);
        unit.setPose(Pose.STANDING);
        unit.setHealth((float) Math.max(1.0,
                unit.getMaxHealth() * Math.min(1.0, Math.max(0.05, frac))));
    }

    public static boolean expired(PmcUnitEntity unit) {
        long until = unit.getPersistentData().getLong(KEY_UNTIL);
        return until > 0 && unit.level().getGameTime() >= until;
    }

    // Seconds left on the bleedout clock, for HUD/status strings.
    public static int secondsLeft(PmcUnitEntity unit) {
        long until = unit.getPersistentData().getLong(KEY_UNTIL);
        long left = until - unit.level().getGameTime();
        return (int) Math.max(0, left / 20);
    }
}
