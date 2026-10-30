// Mixin package - recruited soldiers must not vanish on Peaceful
package com.poseidon.sem_classsystem.mixin;

import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Mixin;

// PmcUnitEntity sits on SEM's Monster-lineage hierarchy, so it inherits
// Mob's shouldDespawnInPeaceful -> (this instanceof Enemy) -> true.
// That made sense when PMCs were enemies; in our class system they are
// the player's recruited soldiers - assets like tamed wolves, which do
// NOT despawn on Peaceful. checkDespawn's peaceful branch runs before
// persistenceRequired is even consulted, so the flag can't save them.
//
// We can't reparent SEM's class (hierarchies are fixed at definition),
// but the only hostile effect that still matters is this one method -
// merging an override straight into PmcUnitEntity. BhqUnitEntity
// inherits it. RU/US stay untouched: they ARE enemies and should keep
// despawning on Peaceful. Golem aggro is handled separately in
// UnitDefenseCompat's defenders-tag veto.
@Mixin(PmcUnitEntity.class)
public abstract class MixinPmcUnitPeaceful {

    // Unannotated merge: PmcUnitEntity does not declare this method, so
    // the merge ADDS it to the class - cleanly overriding the one it
    // inherits from Mob. No @Overwrite needed: nothing in the target
    // class itself is being replaced.
    public boolean shouldDespawnInPeaceful() {
        return false;
    }
}
