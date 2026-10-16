// Mixin package - a fresh SEM order always cancels chunk duty
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.entity.ai.UnitChunkDutyGoal;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Any standing order supersedes map duty - PacketIssueOrder, the commander
// GUI and the squad whistle all funnel through setOrder, so clearing here
// covers every command path without each caller having to remember.
@Mixin(PmcUnitEntity.class)
public abstract class MixinPmcUnitDutyClear {

    @Inject(method = "setOrder", at = @At("TAIL"), remap = false)
    private void semcs$clearDutyOnOrder(OrderType order, CallbackInfo ci) {
        PmcUnitEntity self = (PmcUnitEntity) (Object) this;
        int duty = UnitChunkDutyGoal.dutyOf(self);
        if (duty != 0) {
            UnitChunkDutyGoal.clear(self);
            // A primary leaving its post on a fresh order hands the post
            // over to the bound reserve.
            com.poseidon.sem_classsystem.event.GuardPostEvents
                    .onPostDutyCleared(self, duty);
        }
    }
}
