// Server/Common mixin - adds UnitMeleeAttackGoal to SEM units
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.entity.ai.UnitChunkDutyGoal;
import com.poseidon.sem_classsystem.entity.ai.UnitEatGoal;
import com.poseidon.sem_classsystem.entity.ai.UnitMedicHealGoal;
import com.poseidon.sem_classsystem.entity.ai.UnitMeleeAttackGoal;
import com.poseidon.sem_classsystem.entity.ai.UnitSelfHealGoal;
import com.poseidon.sem_classsystem.entity.ai.UnitSleepGoal;
import com.poseidon.sem_classsystem.entity.ai.UnitWeaponFallbackGoal;
import net.minecraft.world.entity.PathfinderMob;
import net.nekoyuni.SimpleEnemyMod.entity.ai.roles.AbstractFriendlyGoals;
import net.nekoyuni.SimpleEnemyMod.entity.ai.roles.AbstractUnitGoals;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Injects self-heal/food eating and melee attack capability across BOTH
// enemy unit roles (AbstractUnitGoals: RU, US) AND friendly unit roles
// (AbstractFriendlyGoals: PMC, BHQ).
@Mixin({AbstractUnitGoals.class, AbstractFriendlyGoals.class})
public abstract class MixinAbstractUnitGoals {

    @Inject(method = "addGoals", at = @At("TAIL"), remap = false)
    private void semcs$addMeleeAndSelfHealGoals(PathfinderMob entity, CallbackInfo ci) {
        entity.goalSelector.addGoal(1, new UnitSelfHealGoal(entity));
        // Priority 1 (ahead of SeekCoverGoal at 2) so melee units immediately rush and fight
        entity.goalSelector.addGoal(1, new UnitMeleeAttackGoal(entity, 1.12D, true));
        // Medic: patches up wounded allies between fights (self-gates on MEDIC class)
        entity.goalSelector.addGoal(4, new UnitMedicHealGoal(entity));
        // Ammo auto-resupply: swaps to the sidearm when the primary runs dry
        entity.goalSelector.addGoal(9, new UnitWeaponFallbackGoal(entity));
        // Chunk duty (guard/patrol claimed map chunks) - priority 2 beats the
        // CommanderOrderGoal's MOVE flag so a posted guard actually moves
        if (entity instanceof net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity pmc) {
            entity.goalSelector.addGoal(2, new UnitChunkDutyGoal(pmc));
            // [needs]: sleep ties duty at 2 (duty self-yields to an exhausted
            // unit) and eating sits under combat but above idle orders.
            entity.goalSelector.addGoal(2, new UnitSleepGoal(pmc));
            entity.goalSelector.addGoal(3, new UnitEatGoal(pmc));
        }
    }
}
