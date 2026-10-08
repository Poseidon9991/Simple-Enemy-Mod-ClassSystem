package com.poseidon.sem_classsystem.faction;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

public final class UnitClassAttributes {

    private UnitClassAttributes() {
    }

    public static void apply(AbstractUnit unit, boolean fullHeal) {
        UnitClass unitClass = UnitClass.of(unit);
        if (unitClass == null) {
            return;
        }

        double health = ClassSystemConfig.health(unitClass);
        double speed = ClassSystemConfig.speed(unitClass);
        double range = ClassSystemConfig.detectionRange(unitClass);

        AttributeInstance healthAttr = unit.getAttribute(Attributes.MAX_HEALTH);
        AttributeInstance speedAttr = unit.getAttribute(Attributes.MOVEMENT_SPEED);
        AttributeInstance rangeAttr = unit.getAttribute(Attributes.FOLLOW_RANGE);

        if (healthAttr != null) {
            healthAttr.setBaseValue(health);
            if (fullHeal || unit.getHealth() > (float) health) {
                unit.setHealth((float) health);
            }
        }
        if (speedAttr != null) {
            speedAttr.setBaseValue(speed);
        }
        if (rangeAttr != null) {
            rangeAttr.setBaseValue(range);
        }
    }

    public static double spreadMultiplier(Entity entity) {
        UnitClass unitClass = UnitClass.of(entity);
        return unitClass == null ? 1.0 : ClassSystemConfig.spreadMultiplier(unitClass);
    }
}
