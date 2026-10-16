// Faction package - this class actually writes the configured values onto a unit
package com.poseidon.sem_classsystem.faction;

// Reads the configured numbers out of sem_classsystem-server.toml
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
// Vanilla base class of all entities (needed for the spread checker's input type)
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
// An attribute entry on a mob (holds base value + modifiers). Think "health slot".
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
// Vanilla registry of attribute types: MAX_HEALTH, MOVEMENT_SPEED, FOLLOW_RANGE...
import net.minecraft.world.entity.ai.attributes.Attributes;
// SEM's base soldier class - PmcUnitEntity / RUunitEntity / USunitEntity extend it
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
// Veterancy: rank bonuses + accuracy bonus earned from kills
import com.poseidon.sem_classsystem.veterancy.UnitVeterancy;

public final class UnitClassAttributes {

    // Private constructor - this is a pure utility class, nobody should instantiate it
    private UnitClassAttributes() {
    }

    // Overwrites the vanilla attribute bases on a SEM unit with our per-faction
    // config values. Called by the mixin right after SEM applies its global ones,
    // so OUR values win.
    //
    // fullHeal = true  -> spawned just now: refill HP to the new max (SEM behaviour)
    // fullHeal = false -> loaded from disk: keep saved HP, only clamp if it now
    //                     exceeds the (possibly reduced) max health
    public static void apply(AbstractUnit unit, boolean fullHeal) {
        // Which faction is this soldier? null = not a known SEM faction -> do nothing
        UnitClass unitClass = UnitClass.of(unit);
        if (unitClass == null) {
            return;
        }

        // Read the three configured numbers for this faction's section
        double health = ClassSystemConfig.health(unitClass);
        double speed = ClassSystemConfig.speed(unitClass);
        double range = ClassSystemConfig.detectionRange(unitClass);

        // Grab the unit's attribute slots (can be null in edge cases -> null-checked)
        AttributeInstance healthAttr = unit.getAttribute(Attributes.MAX_HEALTH);
        AttributeInstance speedAttr = unit.getAttribute(Attributes.MOVEMENT_SPEED);
        AttributeInstance rangeAttr = unit.getAttribute(Attributes.FOLLOW_RANGE);

        // --- Max health ---
        if (healthAttr != null) {
            // Replace the base max HP with our faction value
            healthAttr.setBaseValue(health);
            // On fresh spawn: fill HP to the new max (SEM does the same).
            // On world load: only cut HP down if it now exceeds the lower max.
            if (fullHeal || unit.getHealth() > (float) health) {
                unit.setHealth((float) health);
            }
        }
        // --- Walk speed (global nerf multiplier applied on top) ---
        if (speedAttr != null) {
            speedAttr.setBaseValue(speed * ClassSystemConfig.globalWalkSpeedMultiplier());
        }
        // --- Detection range (vanilla FOLLOW_RANGE drives mob target search) ---
        if (rangeAttr != null) {
            rangeAttr.setBaseValue(range);
        }
        // --- Veterancy: +HP / +melee damage per earned rank ---
        UnitVeterancy.applyRankBonuses(unit);
    }

    // Accuracy multiplier for whoever is shooting. 1.0 keeps SEM's spread,
    // 0.5 halves it (deadly), 2.0 doubles it (stormtrooper aim).
    // Non-SEM shooters get 1.0 so we never touch other mods' behaviour.
    public static double spreadMultiplier(Entity entity) {
        UnitClass unitClass = UnitClass.of(entity);
        double base = unitClass == null ? 1.0 : ClassSystemConfig.spreadMultiplier(unitClass);
        // Veterans shoot tighter: -6% spread per rank, floor -30%
        if (entity instanceof LivingEntity living) {
            base *= UnitVeterancy.spreadScale(living);
        }
        // Low morale shakes the aim ([needs] mode only, 1.0 otherwise)
        base *= com.poseidon.sem_classsystem.needs.UnitNeeds.spreadPenaltyScale(entity);
        return base;
    }
}
