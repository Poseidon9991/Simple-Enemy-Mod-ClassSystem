// Faction package - per-faction, per-class gun tables the mixin feeds to SEM
package com.poseidon.sem_classsystem.faction;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.nekoyuni.SimpleEnemyMod.data.UnitLoadout;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Static gun tables: faction id -> weapon class -> possible loadouts.
// SEM's UnitLoadout record is reused so the equippers build guns exactly
// the same way SEM does (GunItemBuilder + attachments).
//
// Gun/attachment ids are TaCZ default-pack ids (tacz:<name>).
public final class ClassLoadouts {

    private ClassLoadouts() {
    }

    // factionId -> (class -> loadout pool)
    private static final Map<String, Map<WeaponClass, List<UnitLoadout>>> TABLE = new HashMap<>();

    // Small builder shortcut: turns strings into SEM's UnitLoadout record
    private static UnitLoadout gun(String gunId, int ammoCount, String fireMode,
                                   @Nullable String scope, @Nullable String muzzle, @Nullable String grip) {
        return new UnitLoadout(
                new ResourceLocation(gunId),          // the TaCZ gun item id
                ammoCount,                            // magazine fill count
                fireMode,                             // "AUTO" or "SEMI" (mapped by SEM)
                scope == null ? null : new ResourceLocation(scope),    // optic
                muzzle == null ? null : new ResourceLocation(muzzle),  // muzzle attachment
                grip == null ? null : new ResourceLocation(grip));     // grip attachment
    }

    // Convenience: register one loadout under (faction, class)
    private static void add(String faction, WeaponClass cls, UnitLoadout loadout) {
        TABLE.computeIfAbsent(faction, f -> new EnumMap<>(WeaponClass.class))
             .computeIfAbsent(cls, c -> new ArrayList<>())
             .add(loadout);
    }

    static {
        // ======================= RU (ru_units) =======================
        // SMG - Uzi, full auto room sweeper
        add("ru_units", WeaponClass.SMG,     gun("tacz:uzi",          32, "AUTO", "tacz:sight_t2", null, null));
        // Rifle - AK-47 or Type-81 (old SEM defaults)
        add("ru_units", WeaponClass.RIFLE,   gun("tacz:ak47",         30, "SEMI", "tacz:sight_t2", null, null));
        add("ru_units", WeaponClass.RIFLE,   gun("tacz:type_81",      30, "SEMI", null,            null, null));
        // Shotgun - SPAS-12
        add("ru_units", WeaponClass.SHOTGUN, gun("tacz:spas_12",       8, "SEMI", null, "tacz:muzzle_choke_sg", null));
        // Sniper/RPG - SKS or RPG-7
        add("ru_units", WeaponClass.SNIPER,  gun("tacz:sks_tactical", 10, "SEMI", "tacz:scope_elcan_4x", null, null));
        add("ru_units", WeaponClass.SNIPER,  gun("tacz:rpg7",          1, "SEMI", null, null, null));
        // Heavy MG - RPK (same as SEM's original RU auto gun)
        add("ru_units", WeaponClass.HEAVY_MG,gun("tacz:rpk",          40, "AUTO", "tacz:sight_t2", "tacz:muzzle_silencer_ursus", null));

        // ======================= US (us_units) =======================
        // SMG - Vector .45
        add("us_units", WeaponClass.SMG,     gun("tacz:vector45",     30, "AUTO", null, null, null));
        // Rifle - M4A1 / SCAR-L (old SEM defaults, ACOG)
        add("us_units", WeaponClass.RIFLE,   gun("tacz:m4a1",         30, "SEMI", "tacz:scope_acog_ta31", null, null));
        add("us_units", WeaponClass.RIFLE,   gun("tacz:scar_l",       30, "SEMI", "tacz:scope_acog_ta31", null, null));
        // Shotgun - AA-12 (auto shotgun)
        add("us_units", WeaponClass.SHOTGUN, gun("tacz:aa12",         20, "AUTO", null, "tacz:muzzle_choke_sg", null));
        // Sniper/RPG - M107, AWP (ai_awp), RPG-7
        add("us_units", WeaponClass.SNIPER,  gun("tacz:m107",          5, "SEMI", "tacz:scope_mk5hd", null, null));
        add("us_units", WeaponClass.SNIPER,  gun("tacz:ai_awp",        5, "SEMI", "tacz:scope_mk5hd", null, null));
        add("us_units", WeaponClass.SNIPER,  gun("tacz:rpg7",          1, "SEMI", null, null, null));
        // Heavy MG - M249 (old SEM US auto gun)
        add("us_units", WeaponClass.HEAVY_MG,gun("tacz:m249",         75, "AUTO", "tacz:scope_acog_ta31", null, null));

        // ======================= PMC (pmc_units) =======================
        // SMG - MP5A5
        add("pmc_units", WeaponClass.SMG,    gun("tacz:hk_mp5a5",     30, "AUTO", "tacz:sight_t2", "tacz:muzzle_silencer_mirage", null));
        // Rifle - HK G3
        add("pmc_units", WeaponClass.RIFLE,  gun("tacz:hk_g3",        20, "SEMI", "tacz:scope_elcan_4x", null, null));
        // Shotgun - AA-12
        add("pmc_units", WeaponClass.SHOTGUN, gun("tacz:aa12",        20, "AUTO", null, "tacz:muzzle_choke_sg", null));
        // Sniper/RPG - Mk14 DMR or RPG-7
        add("pmc_units", WeaponClass.SNIPER, gun("tacz:mk14",         20, "SEMI", "tacz:scope_mk5hd", null, null));
        add("pmc_units", WeaponClass.SNIPER, gun("tacz:rpg7",          1, "SEMI", null, null, null));
        // Heavy MG - Minigun
        add("pmc_units", WeaponClass.HEAVY_MG,gun("tacz:minigun",    200, "AUTO", null, null, null));
    }

    // Pick a loadout for (faction, class). null/unknown -> fall back to SEM's
    // own faction pool so a missing table never leaves a unit unarmed.
    @Nullable
    public static UnitLoadout pick(String factionId, @Nullable WeaponClass cls, RandomSource random) {
        if (cls == null) {
            return null;
        }
        List<UnitLoadout> pool = TABLE.getOrDefault(factionId, Map.of()).get(cls);
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        return pool.get(random.nextInt(pool.size()));
    }

    // Reverse lookup: given the gun a unit is holding, guess its class.
    // Used on world load for units saved before the class system existed.
    @Nullable
    public static WeaponClass inferClass(ResourceLocation gunId) {
        for (Map<WeaponClass, List<UnitLoadout>> byClass : TABLE.values()) {
            for (Map.Entry<WeaponClass, List<UnitLoadout>> e : byClass.entrySet()) {
                for (UnitLoadout l : e.getValue()) {
                    if (l.gunId.equals(gunId)) {
                        return e.getKey();
                    }
                }
            }
        }
        return null;
    }
}
