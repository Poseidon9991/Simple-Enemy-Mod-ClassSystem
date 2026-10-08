package com.poseidon.sem_classsystem.faction;

import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.util.UnitFactionTags;

import javax.annotation.Nullable;

public enum UnitClass {

    RU_UNIT("ru_unit", "RU Soldier"),
    US_UNIT("us_unit", "US Soldier"),
    PMC_UNIT("pmc_unit", "PMC Soldier");

    private final String configSection;
    private final String displayName;

    UnitClass(String configSection, String displayName) {
        this.configSection = configSection;
        this.displayName = displayName;
    }

    public String configSection() {
        return this.configSection;
    }

    public String displayName() {
        return this.displayName;
    }

    public String comment() {
        return "Attributes for " + this.displayName + " units (replaces the shared SEM unit_attributes values)";
    }

    @Nullable
    public static UnitClass of(Entity entity) {
        if (UnitFactionTags.isRuUnit(entity)) {
            return RU_UNIT;
        }
        if (UnitFactionTags.isUsUnit(entity)) {
            return US_UNIT;
        }
        if (UnitFactionTags.isPmcUnit(entity)) {
            return PMC_UNIT;
        }
        return null;
    }
}
