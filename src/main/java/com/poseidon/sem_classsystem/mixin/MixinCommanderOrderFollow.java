// Mixin package - makes FOLLOW_COMMANDER a real formation instead of a blob
package com.poseidon.sem_classsystem.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.CommanderOrderGoal;
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.utils.FormationUtils;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// SEM's FOLLOW_COMMANDER points every unit at the owner's exact position,
// so a squad piles onto the player's feet. This redirects follow orders to
// a tightened wedge: index 0 covers the owner's back, the rest fan out in
// chevrons at ~60% of wedge spacing.
@Mixin(CommanderOrderGoal.class)
public abstract class MixinCommanderOrderFollow {

    @Inject(method = "calculateClassicFormationTarget", at = @At("HEAD"),
            cancellable = true, remap = false)
    private void semcs$followInLooseWedge(LivingEntity owner, OrderType currentOrder, int myIndex,
                                          CallbackInfoReturnable<Vec3> cir) {
        if (currentOrder != OrderType.FOLLOW_COMMANDER) {
            return;
        }
        Vec3 ownerPos = owner.position();
        if (myIndex <= 0) {
            // Point man covers directly behind the owner's facing
            Vec3 behind = owner.getLookAngle().scale(-1.6D);
            cir.setReturnValue(ownerPos.add(behind));
            return;
        }
        Vec3 wedge = FormationUtils.getTargetPosition(ownerPos, owner.getLookAngle(),
                OrderType.FORM_WEDGE, myIndex);
        // Pull the wedge in 40% so follow stays tight on the move
        cir.setReturnValue(ownerPos.add(wedge.subtract(ownerPos).scale(0.6D)));
    }
}
