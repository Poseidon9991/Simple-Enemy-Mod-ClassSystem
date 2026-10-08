package com.poseidon.sem_classsystem.config;

import com.poseidon.sem_classsystem.faction.UnitClass;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.EnumMap;
import java.util.Map;

public class ClassSystemConfig {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    private static final Map<UnitClass, Entry> ENTRIES = new EnumMap<>(UnitClass.class);

    static {
        for (UnitClass unitClass : UnitClass.values()) {
            BUILDER.push(unitClass.configSection());
            BUILDER.comment(unitClass.comment());

            Entry entry = new Entry(
                    BUILDER.comment("Max Health for " + unitClass.displayName() + " units")
                            .defineInRange("health", 20.0, 1.0, 200.0),
                    BUILDER.comment("Walk Speed for " + unitClass.displayName() + " units")
                            .defineInRange("speed", 0.27, 0.05, 1.5),
                    BUILDER.comment("Detection Range for " + unitClass.displayName() + " units")
                            .defineInRange("detectionRange", 96.0, 32.0, 384.0),
                    BUILDER.comment(
                                    "Accuracy multiplier for " + unitClass.displayName() + " units",
                                    "Scales the base bullet spread: 1.0 = SEM default accuracy,",
                                    "lower = more accurate, higher = less accurate.",
                                    "Note: SEM caps final spread at its own maxSpread config value.")
                            .defineInRange("accuracySpreadMultiplier", 1.0, 0.0, 10.0)
            );

            ENTRIES.put(unitClass, entry);
            BUILDER.pop();
        }

        SPEC = BUILDER.build();
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC);
    }

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

    private static double safeGet(ForgeConfigSpec.DoubleValue value, double fallback) {
        try {
            return value.get();
        } catch (IllegalStateException notLoadedYet) {
            return fallback;
        }
    }

    private record Entry(ForgeConfigSpec.DoubleValue health,
                         ForgeConfigSpec.DoubleValue speed,
                         ForgeConfigSpec.DoubleValue detectionRange,
                         ForgeConfigSpec.DoubleValue spreadMultiplier) {
    }
}
