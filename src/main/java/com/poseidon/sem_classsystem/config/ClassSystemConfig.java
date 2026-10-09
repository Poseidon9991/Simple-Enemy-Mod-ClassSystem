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
    // faction -> whether village defenders (golems, guard mods) ignore it
    private static final Map<UnitClass, ForgeConfigSpec.BooleanValue> DEFENDER_IGNORE =
            new EnumMap<>(UnitClass.class);
    // BHQ recruit table price (assigned inside the static block below)
    private static ForgeConfigSpec.IntValue BHQ_RECRUIT_PRICE;

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
                            .defineInRange("health", factionHealthDefault(unitClass), 1.0, 200.0),
                    // movement speed attribute (SEM default 0.27)
                    BUILDER.comment("Walk Speed for " + unitClass.displayName() + " units")
                            .defineInRange("speed", factionSpeedDefault(unitClass), 0.05, 1.5),
                    // how far the unit spots targets (SEM default 96)
                    BUILDER.comment("Detection Range for " + unitClass.displayName() + " units")
                            .defineInRange("detectionRange", factionRangeDefault(unitClass), 32.0, 384.0),
                    // faction-wide accuracy tweak, stacked on the class multiplier
                    BUILDER.comment(
                                    "Accuracy multiplier for " + unitClass.displayName() + " units",
                                    "Scales the base bullet spread: 1.0 = SEM default accuracy,",
                                    "lower = more accurate, higher = less accurate.",
                                    "Stacks with the per-class multiplier in [class_tuning].")
                            .defineInRange("accuracySpreadMultiplier", factionSpreadDefault(unitClass), 0.0, 10.0)
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
                "cooldownMultiplier: pause between bursts scale (lower = higher fire rate)",
                "crouchWhileFiring: unit takes a knee while the shoot goal runs",
                "crouchSpreadMultiplier: extra accuracy bonus while crouched (lower = tighter)");

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
                            .defineInRange("cooldownMultiplier", classCooldownDefault(cls), 0.0, 20.0),
                    BUILDER.comment(
                                    "Preferred fighting distance in blocks - how close the unit",
                                    "tries to get to its target before it stops advancing",
                                    "(SEM default ~90). Low = charges in, high = holds position.")
                            .defineInRange("engageDistance", classEngageDefault(cls), 2.0, 384.0),
                    BUILDER.comment(
                                    "Max shooting distance multiplier - how far the unit fires from",
                                    "(lower = holds fire until close, higher = reaches further)")
                            .defineInRange("shootDistanceMultiplier", classShootDefault(cls), 0.1, 5.0),
                    BUILDER.comment("Movement speed multiplier while advancing on a target")
                            .defineInRange("approachSpeedMultiplier", classApproachDefault(cls), 0.3, 3.0),
                    BUILDER.comment(
                                    "Whether this class crouches while firing - snipers take a",
                                    "knee, machine-gunners brace; assault classes stay mobile.")
                            .define("crouchWhileFiring", classCrouchDefault(cls)),
                    BUILDER.comment(
                                    "Spread multiplier applied while crouched (lower = tighter).",
                                    "Only matters for classes with crouchWhileFiring = true.")
                            .defineInRange("crouchSpreadMultiplier", 0.8, 0.05, 2.0)
            );

            CLASS_ENTRIES.put(cls, entry);
            BUILDER.pop();
        }
        BUILDER.pop();   // close [class_tuning]

        // ================= COMPAT: other-mod defender behaviour =================
        BUILDER.push("compat");
        BUILDER.comment(
                "Relationship between SEM units and 'village defender' mobs",
                "(iron golems, snow golems, guard mods like Guard Villagers).",
                "SEM units extend Monster, so defenders attack ALL of them by",
                "default - including your own recruited PMC soldiers.",
                "true = defenders of the sem_classsystem:unit_defenders entity tag",
                "will never target that faction. Other mobs (zombies etc.) are",
                "unaffected - they still fight units normally.");

        for (UnitClass unitClass : UnitClass.values()) {
            // PMC + BHQ default to true: recruited allies should not be
            // attacked by the player's own village. RU/US stay hostile.
            boolean def = unitClass == UnitClass.PMC_UNIT || unitClass == UnitClass.BHQ_UNIT;
            // RU_UNIT -> "RU" -> key "defendersIgnoreRU" etc.
            String key = "defendersIgnore" + unitClass.name().split("_")[0];
            DEFENDER_IGNORE.put(unitClass, BUILDER.comment(
                    "Village defenders ignore " + unitClass.displayName() + " units")
                    .define(key, def));
        }
        BUILDER.pop();   // close [compat]

        // ================= BHQ recruit table =================
        BUILDER.push("bhq_recruit");
        BUILDER.comment(
                "BHQ Recruit Table settings. The table hires BHQ operators -",
                "elite units with better stats than plain PMCs, so they cost",
                "more. The recruit ITEM itself is shared with SEM's table",
                "(MiscConfig.recruitItem - emerald by default).");
        BHQ_RECRUIT_PRICE = BUILDER
                .comment("Amount of the recruit item required to hire a BHQ operator")
                .defineInRange("price", 32, 1, 64);
        BUILDER.pop();   // close [bhq_recruit]

        // Freeze the builder into the immutable spec Forge will register
        SPEC = BUILDER.build();
    }

    // ---- per-faction stat defaults -------------------------------------
    // RU/US/PMC keep SEM's vanilla-feel numbers (20 HP / 0.27 / 96 / x1.0).
    // BHQ is the elite tier: tougher, faster, spots further, shoots straighter.
    private static double factionHealthDefault(UnitClass c) {
        return c == UnitClass.BHQ_UNIT ? 35.0 : 20.0;
    }

    private static double factionSpeedDefault(UnitClass c) {
        return c == UnitClass.BHQ_UNIT ? 0.30 : 0.27;
    }

    private static double factionRangeDefault(UnitClass c) {
        return c == UnitClass.BHQ_UNIT ? 112.0 : 96.0;
    }

    private static double factionSpreadDefault(UnitClass c) {
        return c == UnitClass.BHQ_UNIT ? 0.8 : 1.0;
    }

    // ---- per-class default multipliers (match the README table) ----
    private static double classSpreadDefault(WeaponClass cls) {
        return switch (cls) {
            case SMG -> 1.3;      // less accurate overall
            case RIFLE -> 1.0;    // baseline
            case SHOTGUN -> 0.8;  // tight when close
            case SNIPER -> 0.3;   // ~3 misses per 10 shots
            case HEAVY_MG -> 1.15;
            case PISTOL -> 0.9;   // decent per-shot accuracy
        };
    }

    private static double classFalloffDefault(WeaponClass cls) {
        return switch (cls) {
            case SMG -> 2.5;      // accuracy dies at range
            case RIFLE -> 1.0;
            case SHOTGUN -> 4.0;  // only dangerous up close
            case SNIPER -> 0.5;   // stays accurate at distance
            case HEAVY_MG -> 1.5;
            case PISTOL -> 2.0;   // fades past mid-range
        };
    }

    private static double classBurstDefault(WeaponClass cls) {
        return switch (cls) {
            case SMG -> 1.5;      // bigger bursts
            case RIFLE -> 1.0;
            case SHOTGUN -> 0.5;  // 1-2 shells then pause
            case SNIPER -> 0.25;  // single aimed shots
            case HEAVY_MG -> 1.4;
            case PISTOL -> 0.4;   // short taps, mostly singles
        };
    }

    private static double classCooldownDefault(WeaponClass cls) {
        return switch (cls) {
            case SMG -> 0.5;      // fires again quickly
            case RIFLE -> 1.0;
            case SHOTGUN -> 1.3;  // pump-action feel
            case SNIPER -> 5.0;   // very low fire rate
            case HEAVY_MG -> 0.65;
            case PISTOL -> 0.7;   // snappy semi-auto cadence
        };
    }

    // ---- per-class behaviour defaults: where they fight from ----
    private static double classEngageDefault(WeaponClass cls) {
        return switch (cls) {
            case SMG -> 25.0;     // closes to SMG range
            case RIFLE -> 60.0;   // standard infantry spacing
            case SHOTGUN -> 12.0; // charges into shotgun range
            case SNIPER -> 105.0; // beyond shoot range -> never advances
            case HEAVY_MG -> 55.0;// plants itself mid-field and suppresses
            case PISTOL -> 30.0;  // works mid-range, keeps moving
        };
    }

    private static double classShootDefault(WeaponClass cls) {
        return switch (cls) {
            case SMG -> 0.5;      // fires up to ~45 blocks (SEM max 90)
            case RIFLE -> 1.0;    // SEM default
            case SHOTGUN -> 0.3;  // holds fire beyond ~27 blocks
            case SNIPER -> 1.25;  // engages out to ~112 blocks
            case HEAVY_MG -> 1.0;
            case PISTOL -> 0.5;   // engages inside ~45 blocks
        };
    }

    private static double classApproachDefault(WeaponClass cls) {
        return switch (cls) {
            case SMG -> 1.15;     // pushes in briskly
            case RIFLE -> 1.0;
            case SHOTGUN -> 1.2;  // rushes the target
            case SNIPER -> 1.0;   // rarely moves anyway
            case HEAVY_MG -> 0.9; // lumbers into position
            case PISTOL -> 1.1;   // light on its feet, keeps closing
        };
    }

    private static boolean classCrouchDefault(WeaponClass cls) {
        return switch (cls) {
            case SNIPER -> true;    // takes a knee to aim
            case HEAVY_MG -> true;  // braces the gun before firing
            default -> false;       // assault classes stay mobile
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

    // Per-class preferred fighting distance in blocks (MoveToAttackRangeGoal)
    public static double classEngageDistance(WeaponClass cls) {
        return safeGet(CLASS_ENTRIES.get(cls).engageDistance, 90.0);
    }

    // Per-class max shooting distance multiplier (RangedGunAttackGoal)
    public static double classShootDistance(WeaponClass cls) {
        return safeGet(CLASS_ENTRIES.get(cls).shootDistanceMultiplier, 1.0);
    }

    // Per-class movement speed multiplier while advancing
    public static double classApproachSpeed(WeaponClass cls) {
        return safeGet(CLASS_ENTRIES.get(cls).approachSpeedMultiplier, 1.0);
    }

    // Should this class crouch while its shoot goal is running?
    public static boolean classCrouchWhileFiring(WeaponClass cls) {
        ForgeConfigSpec.BooleanValue v = CLASS_ENTRIES.get(cls).crouchWhileFiring;
        try {
            return v.get();
        } catch (IllegalStateException notLoadedYet) {
            return classCrouchDefault(cls);
        }
    }

    // Extra spread multiplier applied while the unit is crouched
    public static double classCrouchSpread(WeaponClass cls) {
        return safeGet(CLASS_ENTRIES.get(cls).crouchSpreadMultiplier, 0.8);
    }

    // ================= Compat accessors =================

    // Should village defenders (unit_defenders tag) never target this faction?
    // Defaults: PMC=true (allies), RU/US=false (hostile - defenders fight them)
    public static boolean defendersIgnore(UnitClass unitClass) {
        ForgeConfigSpec.BooleanValue v = DEFENDER_IGNORE.get(unitClass);
        try {
            return v.get();
        } catch (IllegalStateException notLoadedYet) {
            return unitClass == UnitClass.PMC_UNIT || unitClass == UnitClass.BHQ_UNIT;
        }
    }

    // Price (in the shared recruit item) to hire a BHQ operator
    public static int bhqRecruitPrice() {
        try {
            return BHQ_RECRUIT_PRICE.get();
        } catch (IllegalStateException notLoadedYet) {
            return 32;
        }
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
    // + 3 behaviour values (engage distance, shoot distance, approach speed)
    private record ClassEntry(ForgeConfigSpec.ConfigValue<String> nickname,
                              ForgeConfigSpec.DoubleValue spreadMultiplier,
                              ForgeConfigSpec.DoubleValue falloffMultiplier,
                              ForgeConfigSpec.DoubleValue burstMultiplier,
                              ForgeConfigSpec.DoubleValue cooldownMultiplier,
                              ForgeConfigSpec.DoubleValue engageDistance,
                              ForgeConfigSpec.DoubleValue shootDistanceMultiplier,
                              ForgeConfigSpec.DoubleValue approachSpeedMultiplier,
                              ForgeConfigSpec.BooleanValue crouchWhileFiring,
                              ForgeConfigSpec.DoubleValue crouchSpreadMultiplier) {
    }
}
