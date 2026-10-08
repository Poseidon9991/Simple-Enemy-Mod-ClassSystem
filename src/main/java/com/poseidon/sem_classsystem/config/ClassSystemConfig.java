// Config package - defines every key in sem_classsystem-server.toml
package com.poseidon.sem_classsystem.config;

// Faction axis (RU/US/PMC) -> attribute values
import com.poseidon.sem_classsystem.faction.UnitClass;
// Weapon-class axis (SMG/Rifle/Shotgun/Sniper/HeavyMG) -> combat tuning
import com.poseidon.sem_classsystem.faction.WeaponClass;
// Forge's builder class used to describe config keys, defaults, ranges and comments
import net.minecraftforge.common.ForgeConfigSpec;
// Lets us call registerConfig() on the currently-loading mod container
import net.minecraftforge.fml.ModLoadingContext;
// Enum of config types: COMMON / CLIENT / SERVER (we use SERVER = per-world file)
import net.minecraftforge.fml.config.ModConfig;

// EnumMap is a fast Map specialised for enum keys
import java.util.EnumMap;
import java.util.Map;

public class ClassSystemConfig {

    // The builder accumulates every config key + section, then .build() freezes it
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    // The finished spec - handed to Forge in register()
    public static final ForgeConfigSpec SPEC;

    // faction -> its 4 attribute values  (phase 1)
    private static final Map<UnitClass, Entry> ENTRIES = new EnumMap<>(UnitClass.class);
    // weapon class -> its combat tuning values (phase 2)
    private static final Map<WeaponClass, ClassEntry> CLASS_ENTRIES = new EnumMap<>(WeaponClass.class);

    // static {} runs once at class load - defines ALL keys before register()
    static {

        // ================= PHASE 1: per-faction attributes =================
        for (UnitClass unitClass : UnitClass.values()) {
            // One [ru_unit] / [us_unit] / [pmc_unit] section each
            BUILDER.push(unitClass.configSection());
            BUILDER.comment(unitClass.comment());

            Entry entry = new Entry(
                    // max HP of this faction's soldiers (SEM default 20)
                    BUILDER.comment("Max Health for " + unitClass.displayName() + " units")
                            .defineInRange("health", 20.0, 1.0, 200.0),
                    // movement speed attribute (SEM default 0.27)
                    BUILDER.comment("Walk Speed for " + unitClass.displayName() + " units")
                            .defineInRange("speed", 0.27, 0.05, 1.5),
                    // how far the unit spots targets (SEM default 96)
                    BUILDER.comment("Detection Range for " + unitClass.displayName() + " units")
                            .defineInRange("detectionRange", 96.0, 32.0, 384.0),
                    // faction-wide accuracy tweak, stacked on the class multiplier
                    BUILDER.comment(
                                    "Accuracy multiplier for " + unitClass.displayName() + " units",
                                    "Scales the base bullet spread: 1.0 = SEM default accuracy,",
                                    "lower = more accurate, higher = less accurate.",
                                    "Stacks with the per-class multiplier in [class_tuning].")
                            .defineInRange("accuracySpreadMultiplier", 1.0, 0.0, 10.0)
            );

            ENTRIES.put(unitClass, entry);   // remember section for later lookup
            BUILDER.pop();                   // close the [section]
        }

        // ================= PHASE 2: per-class combat tuning =================
        BUILDER.push("class_tuning");
        BUILDER.comment("Per-weapon-class combat behaviour.",
                "accuracySpreadMultiplier: bullet spread scale (lower = more accurate)",
                "rangeFalloffMultiplier: how fast accuracy worsens with distance (higher = worse at range)",
                "burstLengthMultiplier: shots per burst scale (min 1 shot always)",
                "cooldownMultiplier: pause between bursts scale (lower = higher fire rate)");

        for (WeaponClass cls : WeaponClass.values()) {
            // One [class_tuning.smg] / [class_tuning.rifle] / ... section each
            BUILDER.push(cls.configKey());

            // Defaults tuned per class role:
            //   SMG     - sloppy at range, fast follow-up bursts
            //   RIFLE   - untouched baseline (all x1.0)
            //   SHOTGUN - tight up close, falls off hard with distance
            //   SNIPER  - very tight grouping, 1-shot "bursts", long pauses
            //   HEAVYMG - wide-ish but steady stream of fire
            ClassEntry entry = new ClassEntry(
                    // The name-tag suffix, e.g. "US Unit - Breacher"
                    BUILDER.comment("Nickname shown in the unit name tag")
                            .define("nickname", cls.defaultNickname()),
                    BUILDER.comment("Bullet spread multiplier (lower = more accurate)")
                            .defineInRange("accuracySpreadMultiplier", classSpreadDefault(cls), 0.0, 10.0),
                    BUILDER.comment("Distance accuracy falloff multiplier (higher = worse at range)")
                            .defineInRange("rangeFalloffMultiplier", classFalloffDefault(cls), 0.0, 20.0),
                    BUILDER.comment("Burst length multiplier (shots per burst)")
                            .defineInRange("burstLengthMultiplier", classBurstDefault(cls), 0.0, 20.0),
                    BUILDER.comment("Cooldown multiplier between bursts (lower = fires faster)")
                            .defineInRange("cooldownMultiplier", classCooldownDefault(cls), 0.0, 20.0)
            );

            CLASS_ENTRIES.put(cls, entry);
            BUILDER.pop();
        }
        BUILDER.pop();   // close [class_tuning]

        // Freeze the builder into the immutable spec Forge will register
        SPEC = BUILDER.build();
    }

    // ---- per-class default multipliers (match the README table) ----
    private static double classSpreadDefault(WeaponClass cls) {
        return switch (cls) {
            case SMG -> 1.3;      // less accurate overall
            case RIFLE -> 1.0;    // baseline
            case SHOTGUN -> 0.8;  // tight when close
            case SNIPER -> 0.3;   // ~3 misses per 10 shots
            case HEAVY_MG -> 1.15;
        };
    }

    private static double classFalloffDefault(WeaponClass cls) {
        return switch (cls) {
            case SMG -> 2.5;      // accuracy dies at range
            case RIFLE -> 1.0;
            case SHOTGUN -> 4.0;  // only dangerous up close
            case SNIPER -> 0.5;   // stays accurate at distance
            case HEAVY_MG -> 1.5;
        };
    }

    private static double classBurstDefault(WeaponClass cls) {
        return switch (cls) {
            case SMG -> 1.5;      // bigger bursts
            case RIFLE -> 1.0;
            case SHOTGUN -> 0.5;  // 1-2 shells then pause
            case SNIPER -> 0.25;  // single aimed shots
            case HEAVY_MG -> 1.4;
        };
    }

    private static double classCooldownDefault(WeaponClass cls) {
        return switch (cls) {
            case SMG -> 0.5;      // fires again quickly
            case RIFLE -> 1.0;
            case SHOTGUN -> 1.3;  // pump-action feel
            case SNIPER -> 5.0;   // very low fire rate
            case HEAVY_MG -> 0.65;
        };
    }

    // Called once from the mod constructor while Forge loads us
    public static void register() {
        // Type.SERVER -> saves/<world>/serverconfig/sem_classsystem-server.toml
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC);
    }

    // ================= Faction accessors (phase 1) =================

    public static double health(UnitClass unitClass) {
        return safeGet(ENTRIES.get(unitClass).health, 20.0);
    }

    public static double speed(UnitClass unitClass) {
        return safeGet(ENTRIES.get(unitClass).speed, 0.27);
    }

    public static double detectionRange(UnitClass unitClass) {
        return safeGet(ENTRIES.get(unitClass).detectionRange, 96.0);
    }

    public static double spreadMultiplier(UnitClass unitClass) {
        return safeGet(ENTRIES.get(unitClass).spreadMultiplier, 1.0);
    }

    // ================= Class accessors (phase 2) =================

    // The display nickname ("Sweeper", "Camper", ...) for the name tag
    public static String classNickname(WeaponClass cls) {
        try {
            return CLASS_ENTRIES.get(cls).nickname.get();
        } catch (IllegalStateException notLoadedYet) {
            return cls.defaultNickname();
        }
    }

    // Per-class bullet-spread multiplier (stacks on the faction one)
    public static double classSpread(WeaponClass cls) {
        return safeGet(CLASS_ENTRIES.get(cls).spreadMultiplier, 1.0);
    }

    // Per-class distance falloff multiplier
    public static double classFalloff(WeaponClass cls) {
        return safeGet(CLASS_ENTRIES.get(cls).falloffMultiplier, 1.0);
    }

    // Per-class burst length multiplier
    public static double classBurst(WeaponClass cls) {
        return safeGet(CLASS_ENTRIES.get(cls).burstMultiplier, 1.0);
    }

    // Per-class burst cooldown multiplier (the "fire rate" knob)
    public static double classCooldown(WeaponClass cls) {
        return safeGet(CLASS_ENTRIES.get(cls).cooldownMultiplier, 1.0);
    }

    // Config values throw IllegalStateException if read before the file loads.
    // Catch that and fall back to SEM-equivalent defaults instead of crashing.
    private static double safeGet(ForgeConfigSpec.DoubleValue value, double fallback) {
        try {
            return value.get();
        } catch (IllegalStateException notLoadedYet) {
            return fallback;
        }
    }

    // One faction's 4 config values bundled together
    private record Entry(ForgeConfigSpec.DoubleValue health,
                         ForgeConfigSpec.DoubleValue speed,
                         ForgeConfigSpec.DoubleValue detectionRange,
                         ForgeConfigSpec.DoubleValue spreadMultiplier) {
    }

    // One weapon class's tuning bundle: nickname + 4 combat multipliers
    private record ClassEntry(ForgeConfigSpec.ConfigValue<String> nickname,
                              ForgeConfigSpec.DoubleValue spreadMultiplier,
                              ForgeConfigSpec.DoubleValue falloffMultiplier,
                              ForgeConfigSpec.DoubleValue burstMultiplier,
                              ForgeConfigSpec.DoubleValue cooldownMultiplier) {
    }
}
