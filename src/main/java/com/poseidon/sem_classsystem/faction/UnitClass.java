// Faction package - decides which "class" (faction) a soldier belongs to
package com.poseidon.sem_classsystem.faction;

// Vanilla entity type - UnitFactionTags accepts any Entity
import net.minecraft.world.entity.Entity;
// SEM's helper class: checks the entity-type tags simpleenemymod:ru_units etc.
import net.nekoyuni.SimpleEnemyMod.entity.unit.util.UnitFactionTags;

// @Nullable annotation for of() - it can return null for non-SEM entities
import javax.annotation.Nullable;

// One enum constant per soldier faction SEM ships. Each maps to a config section.
public enum UnitClass {

    // RU soldiers -> [ru_unit] section in the toml
    RU_UNIT("ru_unit", "RU Soldier"),
    // US soldiers -> [us_unit] section
    US_UNIT("us_unit", "US Soldier"),
    // PMC soldiers -> [pmc_unit] section
    PMC_UNIT("pmc_unit", "PMC Soldier"),
    // BHQ operators -> [bhq_unit] section (our own elite PMC variant;
    // sits in SEM's pmc_units tag but gets its own stat profile)
    BHQ_UNIT("bhq_unit", "BHQ Operator"),
    // VDF militia -> [vdf_unit] section (village garrison; independent
    // Monster in NO faction tag - instanceof check below is the only
    // way UnitClass.of() can ever see it)
    VDF_UNIT("vdf_unit", "VDF Militia");

    // Name of the [section] this faction uses in sem_classsystem-server.toml
    private final String configSection;
    // Human-readable name used in comments and logs
    private final String displayName;

    // Enum constructor - stores the two strings on each constant above
    UnitClass(String configSection, String displayName) {
        this.configSection = configSection;
        this.displayName = displayName;
    }

    // Getter: the toml section name, e.g. "ru_unit"
    public String configSection() {
        return this.configSection;
    }

    // Getter: pretty name, e.g. "RU Soldier"
    public String displayName() {
        return this.displayName;
    }

    // Text written at the top of this faction's section in the config file
    public String comment() {
        return "Attributes for " + this.displayName + " units (replaces the shared SEM unit_attributes values)";
    }

    // The heart of the class system: look at any entity and tell us which
    // faction it is, by asking SEM's tag checks. Returns null for entities
    // that are not SEM soldiers (players, zombies, other mods' mobs, ...).
    @Nullable
    public static UnitClass of(Entity entity) {
        // Our own BHQ unit IS in SEM's pmc_units tag (ally of PMCs), so this
        // instanceof check must run BEFORE the tag checks - otherwise a BHQ
        // operator would resolve as PMC_UNIT and get the worse stats.
        if (entity instanceof com.poseidon.sem_classsystem.entity.BhqUnitEntity) {
            return BHQ_UNIT;
        }
        // VDF is an independent Monster (not an AbstractUnit) and is in NO
        // faction tag - without this instanceof branch it falls through to
        // null and loses its [vdf_unit] stat profile + faction identity.
        if (entity instanceof com.poseidon.sem_classsystem.entity.VdfUnitEntity) {
            return VDF_UNIT;
        }
        // SEM tags RU entities with simpleenemymod:ru_units -> this checks that tag
        if (UnitFactionTags.isRuUnit(entity)) {
            return RU_UNIT;
        }
        // Same for the simpleenemymod:us_units tag
        if (UnitFactionTags.isUsUnit(entity)) {
            return US_UNIT;
        }
        // And the simpleenemymod:pmc_units tag
        if (UnitFactionTags.isPmcUnit(entity)) {
            return PMC_UNIT;
        }
        // Not an SEM soldier at all -> no class attributes apply
        return null;
    }
}
