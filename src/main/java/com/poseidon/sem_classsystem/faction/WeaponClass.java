// Faction package - WeaponClass is the second axis on top of faction:
// a unit is "RU SMG", "US Sniper", "PMC Heavy MG" etc.
package com.poseidon.sem_classsystem.faction;

// Enum constants = the combat roles a soldier can be assigned.
// Each constant stores its config section key and default nickname.
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
    HEAVY_MG("heavy_mg", "Suppressor");

    // Key used in the config file, e.g. [class_tuning.smg]
    private final String configKey;
    // Default display nickname shown in the name tag (Sweeper, Camper, ...)
    private final String defaultNickname;

    // Enum constructor - stores key + nickname per constant
    WeaponClass(String configKey, String defaultNickname) {
        this.configKey = configKey;
        this.defaultNickname = defaultNickname;
    }

    // Getter for the toml key
    public String configKey() {
        return this.configKey;
    }

    // Getter for the built-in nickname (config can override it)
    public String defaultNickname() {
        return this.defaultNickname;
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
}
