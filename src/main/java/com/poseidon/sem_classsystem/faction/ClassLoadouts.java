// Faction package - thin accessor over the datapack-loaded class gun tables
package com.poseidon.sem_classsystem.faction;

import com.poseidon.sem_classsystem.data.ClassLoadoutManager;
import com.tacz.guns.api.TimelessAPI;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.nekoyuni.SimpleEnemyMod.data.UnitLoadout;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// The gun tables are DATA, not code: they live in
//   data/<namespace>/class_loadouts/<faction>.json
// and are loaded by ClassLoadoutManager on world load / /reload.
// Our own defaults ship inside the jar under data/sem_classsystem/..., and
// players or gunpacks can extend/replace them with an ordinary datapack -
// exactly like SEM's own unit_loadouts system.
public final class ClassLoadouts {

    private ClassLoadouts() {
    }

    // Pick a loadout for (faction, class). null/empty pool -> caller falls
    // back to SEM's own faction pool so a missing table never disarms a unit.
    @Nullable
    public static UnitLoadout pick(String factionId, @Nullable WeaponClass cls, RandomSource random) {
        if (cls == null) {
            return null;
        }
        return ClassLoadoutManager.pick(factionId, cls, random);
    }

    // Reverse lookup: which class claims this gun id. Checks our datapack
    // tables first - an explicit assignment always wins (our mk14 is a sniper
    // even though TaCZ calls it a rifle). Guns absent from every table fall
    // back to the gun's own TaCZ type, so swapping an SMG for a different SMG
    // stays legal even if the new gun isn't in any loadout pool yet.
    // Used on world load (old units) and by the weapon-lock checks.
    //
    // The answer is a pure function of (datapack tables, TaCZ index) so it
    // memoizes per gun id - Optional wraps the "no class" answer so negative
    // results cache too. ClassLoadoutManager.apply() clears this on every
    // datapack reload. A stale null can only fail OPEN (the lock permits a
    // gun it cannot classify), never wrongly reject.
    @Nullable
    public static WeaponClass inferClass(ResourceLocation gunId) {
        return INFERENCE_CACHE.computeIfAbsent(gunId, ClassLoadouts::inferClassUncached)
                .orElse(null);
    }

    private static final ConcurrentHashMap<ResourceLocation, Optional<WeaponClass>> INFERENCE_CACHE =
            new ConcurrentHashMap<>();

    private static Optional<WeaponClass> inferClassUncached(ResourceLocation gunId) {
        WeaponClass fromTable = ClassLoadoutManager.classOf(gunId);
        return Optional.ofNullable(fromTable != null ? fromTable : typeClass(gunId));
    }

    // Called by ClassLoadoutManager when datapack tables reload.
    public static void invalidateInferenceCache() {
        INFERENCE_CACHE.clear();
    }

    // TaCZ type fallback for guns not in our tables. Maps the gun index's
    // declared type onto the nearest WeaponClass; types with no equivalent
    // (rpg, exotic gunpack types) and unknown guns return null - the lock
    // treats null as "cannot prove it's the wrong class" and permits it.
    @Nullable
    private static WeaponClass typeClass(ResourceLocation gunId) {
        return TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> {
                    String type = index.getType();
                    return switch (type == null ? "" : type) {
                        case "smg" -> WeaponClass.SMG;
                        case "rifle" -> WeaponClass.RIFLE;
                        case "shotgun" -> WeaponClass.SHOTGUN;
                        case "sniper" -> WeaponClass.SNIPER;
                        case "mg" -> WeaponClass.HEAVY_MG;
                        // Every launcher shares TaCZ's single "rpg" type -
                        // RPGs and grenade launchers both land on HEAVY.
                        case "rpg" -> WeaponClass.HEAVY;
                        case "pistol" -> WeaponClass.PISTOL;
                        default -> null;
                    };
                })
                .orElse(null);
    }
}
