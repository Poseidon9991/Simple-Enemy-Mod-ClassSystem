// Mixin package - patches all three SEM weapon equippers at once
package com.poseidon.sem_classsystem.mixin;

// Our per-faction-per-class gun tables
import com.poseidon.sem_classsystem.faction.ClassLoadouts;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
// SEM's loadout record - we return the same type SEM expects
import net.nekoyuni.SimpleEnemyMod.data.UnitLoadout;
// SEM's datapack loadout manager - the call we intercept + fallback
import net.nekoyuni.SimpleEnemyMod.event.common.UnitLoadoutManager;
// The three equipper classes (one per faction) we patch
import net.nekoyuni.SimpleEnemyMod.entity.equipment.PmcUnitWeaponEquipper;
import net.nekoyuni.SimpleEnemyMod.entity.equipment.RuWeaponEquipper;
import net.nekoyuni.SimpleEnemyMod.entity.equipment.UsWeaponEquipper;
// Mixin annotations:
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// One mixin patches THREE target classes: all of SEM's equipper classes have
// the identical static signature equipRandomGun(LivingEntity, RandomSource)
// and all call UnitLoadoutManager.getRandomLoadout(factionId, random) inside.
@Mixin({RuWeaponEquipper.class, UsWeaponEquipper.class, PmcUnitWeaponEquipper.class})
public abstract class MixinWeaponEquippers {

    // @Redirect on the getRandomLoadout call inside equipRandomGun.
    // remap=false: every name involved is SEM-internal (never obfuscated).
    //
    // Handler signature rules for a STATIC call inside a STATIC method:
    //   (target method's own args...) + (enclosing method's args...)
    // = (String factionId, RandomSource random) + (LivingEntity entity, RandomSource random)
    // The two RandomSources are the same object - kept both for clarity.
    @Redirect(method = "equipRandomGun", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/event/common/UnitLoadoutManager;getRandomLoadout(Ljava/lang/String;Lnet/minecraft/util/RandomSource;)Lnet/nekoyuni/SimpleEnemyMod/data/UnitLoadout;"))
    private static UnitLoadout semcs$classLoadout(String factionId, RandomSource random,
                                                  LivingEntity entity, RandomSource entityRandom) {
        // Roll (or read) the unit's weapon class, then pick a loadout from
        // OUR per-class table instead of SEM's flat faction pool.
        // BHQ operators go through SEM's PMC equipper (factionId "pmc_units")
        // but have their own table: class_loadouts/bhq_units.json.
        WeaponClass cls = WeaponClassSupport.getOrAssign(entity, random);
        String tableKey = entity instanceof com.poseidon.sem_classsystem.entity.BhqUnitEntity
                ? "bhq_units" : factionId;
        UnitLoadout loadout = ClassLoadouts.pick(tableKey, cls, random);

        if (loadout != null) {
            return loadout;   // class loadout wins
        }
        // Faction/class combo missing from our tables -> keep SEM behaviour
        return UnitLoadoutManager.getRandomLoadout(factionId, random);
    }
}
