// Faction package - WeaponClass is the second axis on top of faction:
// a unit is "RU SMG", "US Sniper", "PMC Heavy MG" etc.
package com.poseidon.sem_classsystem.faction;

import java.util.Arrays;

// Enum constants = the combat roles a soldier can be assigned.
// Each constant stores its config section key and default nickname.
// Constants flagged as subclasses are never rolled or assigned - they only
// exist through the gun in the unit's hand (see WeaponClassSupport.getEffective).
public enum WeaponClass {

    // Close-range skirmisher: fast follow-up shots, accuracy falls off with range
    SMG("smg", "Breacher"),
    // Baseline soldier: untouched SEM combat tuning
    RIFLE("rifle", "Rifleman"),
    // Room-clearer: nasty up close, spread falls off hard past short range
    SHOTGUN("shotgun", "Sweeper"),
    // Long-range / launcher: very slow cadence but very tight grouping
    SNIPER("sniper", "Camper"),
    // Suppression platform: sustained bursts, slightly worse spread than SMG
    HEAVY_MG("heavy_mg", "Suppressor"),
    // Ordnance specialist: every launcher TaCZ types as "rpg" - RPGs fly
    // flat, grenade launchers lob - the firing goal computes the arc from
    // each gun's real bullet speed/gravity so it handles both.
    HEAVY("heavy", "Demolisher"),
    // Generalist: the lock accepts every gun type; its own middling tuning
    // applies no matter what it holds (see computeEffective) - decent at
    // everything, never as good as the specialist holding the same gun.
    JACK("jack", "Jack"),
    // SUBCLASS, not assignable: any unit given a pistol fights with these
    // rules (quick single shots, mid-range, stays mobile) while keeping its
    // real class. Pistols bypass the class weapon lock for every class.
    PISTOL("pistol", "Sidearm", true);

    // Key used in the config file, e.g. [class_tuning.smg]
    private final String configKey;
    // Default display nickname shown in the name tag (Sweeper, Camper, ...)
    private final String defaultNickname;
    // Subclasses are behaviour overlays only - excluded from spawn rolls,
    // recruit-table cycling and /semcs assignment commands.
    private final boolean subclass;

    // Enum constructor - stores key + nickname per constant
    WeaponClass(String configKey, String defaultNickname) {
        this(configKey, defaultNickname, false);
    }

    WeaponClass(String configKey, String defaultNickname, boolean subclass) {
        this.configKey = configKey;
        this.defaultNickname = defaultNickname;
        this.subclass = subclass;
    }

    // Getter for the toml key
    public String configKey() {
        return this.configKey;
    }

    // Getter for the built-in nickname (config can override it)
    public String defaultNickname() {
        return this.defaultNickname;
    }

    // True for behaviour overlays (pistol sidearm) that are never assigned
    // as a unit's main class.
    public boolean isSubclass() {
        return this.subclass;
    }

    // The assignable classes only - subclasses are filtered out once here so
    // spawn rolls and recruit cycling never see them.
    private static final WeaponClass[] MAIN_CLASSES =
            Arrays.stream(values()).filter(c -> !c.subclass).toArray(WeaponClass[]::new);

    public static WeaponClass[] mainClasses() {
        return MAIN_CLASSES;
    }

    // Parse a saved NBT string back into the enum, or null if unknown/absent
    public static WeaponClass byName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            return WeaponClass.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    // Look up a class by its config/datapack key ("smg", "rifle", ...), or null
    public static WeaponClass byConfigKey(String key) {
        for (WeaponClass cls : values()) {
            if (cls.configKey.equals(key)) {
                return cls;
            }
        }
        return null;
    }
}
