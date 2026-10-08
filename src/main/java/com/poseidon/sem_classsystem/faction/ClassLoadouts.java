// Faction package - thin accessor over the datapack-loaded class gun tables
package com.poseidon.sem_classsystem.faction;

import com.poseidon.sem_classsystem.data.ClassLoadoutManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.nekoyuni.SimpleEnemyMod.data.UnitLoadout;

import javax.annotation.Nullable;

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

    // Reverse lookup: which class claims this gun id (scans all factions).
    // Used on world load (old units) and by the weapon-lock checks.
    @Nullable
    public static WeaponClass inferClass(ResourceLocation gunId) {
        return ClassLoadoutManager.classOf(gunId);
    }
}
