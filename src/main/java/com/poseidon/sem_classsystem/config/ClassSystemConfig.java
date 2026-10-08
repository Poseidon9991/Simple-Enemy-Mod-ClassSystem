// Config package - holds the class that defines our .toml config file
package com.poseidon.sem_classsystem.config;

// Our UnitClass enum (RU_UNIT / US_UNIT / PMC_UNIT) - one config section per value
import com.poseidon.sem_classsystem.faction.UnitClass;
// Forge's builder class used to describe config keys, defaults, ranges and comments
import net.minecraftforge.common.ForgeConfigSpec;
// Lets us call registerConfig() on the currently-loading mod container
import net.minecraftforge.fml.ModLoadingContext;
// Enum of config types: COMMON / CLIENT / SERVER (we use SERVER = per-world file)
import net.minecraftforge.fml.config.ModConfig;

// EnumMap is a fast Map specialised for enum keys - we look up entries by UnitClass
import java.util.EnumMap;
import java.util.Map;

public class ClassSystemConfig {

    // The builder accumulates every config key + section, then .build() freezes it into SPEC
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    // The finished config spec - handed to Forge in register()
    public static final ForgeConfigSpec SPEC;

    // One Entry (4 config values) per UnitClass, stored in a map for quick lookup
    private static final Map<UnitClass, Entry> ENTRIES = new EnumMap<>(UnitClass.class);

    // static {} runs once when this class is first loaded - perfect place to define
    // all config keys before register() is called from the mod constructor
    static {
        // Loop over every faction so each one gets an identical set of 4 keys
        for (UnitClass unitClass : UnitClass.values()) {
            // push() opens a [section] in the toml file, e.g. [ru_unit]
            BUILDER.push(unitClass.configSection());
            // Section-level comment written above the keys in the file
            BUILDER.comment(unitClass.comment());

            // Build one Entry: health, speed, detectionRange and spread multiplier.
            // defineInRange(key, default, min, max) creates a validated number option.
            Entry entry = new Entry(
                    // "health" key - max HP of this faction's soldiers (matches SEM default 20)
                    BUILDER.comment("Max Health for " + unitClass.displayName() + " units")
                            .defineInRange("health", 20.0, 1.0, 200.0),
                    // "speed" key - movement speed attribute (SEM default 0.27)
                    BUILDER.comment("Walk Speed for " + unitClass.displayName() + " units")
                            .defineInRange("speed", 0.27, 0.05, 1.5),
                    // "detectionRange" key - how far the unit spots targets (SEM default 96)
                    BUILDER.comment("Detection Range for " + unitClass.displayName() + " units")
                            .defineInRange("detectionRange", 96.0, 32.0, 384.0),
                    // "accuracySpreadMultiplier" key - multiplies the gun spread the AI gets.
                    // <1 tightens spread (more accurate), >1 widens it (less accurate).
                    BUILDER.comment(
                                    "Accuracy multiplier for " + unitClass.displayName() + " units",
                                    "Scales the base bullet spread: 1.0 = SEM default accuracy,",
                                    "lower = more accurate, higher = less accurate.",
                                    "Note: SEM caps final spread at its own maxSpread config value.")
                            .defineInRange("accuracySpreadMultiplier", 1.0, 0.0, 10.0)
            );

            // Remember which Entry belongs to which faction for later lookups
            ENTRIES.put(unitClass, entry);
            // pop() closes the current [section]
            BUILDER.pop();
        }

        // Freeze the builder into the immutable spec Forge will register
        SPEC = BUILDER.build();
    }

    // Called once from SemClassSystem's constructor while the mod is loading
    public static void register() {
        // Type.SERVER = the file lands in saves/<world>/serverconfig/, not config/
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC);
    }

    // --- Accessor helpers: code elsewhere calls these instead of raw .get() ---

    // Max health configured for the given faction (falls back to SEM's 20 default)
    public static double health(UnitClass unitClass) {
        return safeGet(ENTRIES.get(unitClass).health, 20.0);
    }

    // Movement speed configured for the given faction (SEM default 0.27)
    public static double speed(UnitClass unitClass) {
        return safeGet(ENTRIES.get(unitClass).speed, 0.27);
    }

    // Detection/follow range configured for the given faction (SEM default 96)
    public static double detectionRange(UnitClass unitClass) {
        return safeGet(ENTRIES.get(unitClass).detectionRange, 96.0);
    }

    // Spread multiplier configured for the given faction (1.0 = SEM behaviour)
    public static double spreadMultiplier(UnitClass unitClass) {
        return safeGet(ENTRIES.get(unitClass).spreadMultiplier, 1.0);
    }

    // Reading a config value before Forge has loaded the file throws
    // IllegalStateException (e.g. an entity spawning extremely early).
    // Catch that and return the SEM default instead of crashing.
    private static double safeGet(ForgeConfigSpec.DoubleValue value, double fallback) {
        try {
            // Normal path: return whatever the user configured
            return value.get();
        } catch (IllegalStateException notLoadedYet) {
            // Config file not loaded yet -> behave exactly like vanilla SEM
            return fallback;
        }
    }

    // A record = immutable data holder. Bundles the 4 config values of one faction.
    private record Entry(ForgeConfigSpec.DoubleValue health,
                         ForgeConfigSpec.DoubleValue speed,
                         ForgeConfigSpec.DoubleValue detectionRange,
                         ForgeConfigSpec.DoubleValue spreadMultiplier) {
    }
}
