// Mixin package - adds our melee + self-heal goals to every SEM unit role
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.ai.UnitHealGoal;
import com.poseidon.sem_classsystem.ai.UnitMeleeGoal;
import net.minecraft.world.entity.PathfinderMob;
import net.nekoyuni.SimpleEnemyMod.entity.ai.roles.AbstractUnitGoals;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// AbstractUnitGoals.addGoals() is the shared goal assembly for every SEM
// role (Default/Friendly x Unit/SquadUnit/SquadLeader). A TAIL inject here
// gives all of them - RU, US, PMC, BHQ - our two item goals.
//
// Priorities (lower wins): SEM uses 0 TacticalManager/Float, 1 Doors,
// 2 SeekCover, 3 MoveToAttackRange, 4 Peek, 5 Maneuver, 6 GunAttack.
// Melee lands on 3: it outranks Peek/Maneuver/Gun but still yields to
// cover-seeking and floating. Heal lands on 6 but holds no flags - it
// never blocks or gets blocked, just fires its one-tick action.
@Mixin(AbstractUnitGoals.class)
public abstract class MixinAbstractUnitGoals {

    // remap=false: addGoals is a SEM-internal name.
    @Inject(method = "addGoals", at = @At("TAIL"), remap = false)
    private void semcs$addItemGoals(PathfinderMob mob, CallbackInfo ci) {
        if (!(mob instanceof AbstractUnit)) {
            return;
        }
        mob.goalSelector.addGoal(3, new UnitMeleeGoal(mob));
        mob.goalSelector.addGoal(6, new UnitHealGoal(mob));
    }
}
