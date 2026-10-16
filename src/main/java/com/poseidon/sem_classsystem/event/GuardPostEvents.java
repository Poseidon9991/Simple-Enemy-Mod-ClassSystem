// Event package - guard post lifecycle: death promotion and off-duty relay
package com.poseidon.sem_classsystem.event;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.block.GuardPostBlockEntity;
import com.poseidon.sem_classsystem.entity.ai.UnitChunkDutyGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

// Keeps the post covered without the block having to tick:
//   - primary dies          -> its slot frees, the reserve is promoted
//   - primary ordered away  -> duty clears via MixinPmcUnitDutyClear ->
//                              onPostDutyCleared() promotes the reserve
//   - reserve dies          -> slot frees, nothing else changes
//   - primary returns       -> PacketReturnToPost re-duties it and calls
//                              standDownReserve() to release the cover
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID)
public final class GuardPostEvents {

    private GuardPostEvents() {
    }

    @SubscribeEvent
    public static void onUnitDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof PmcUnitEntity unit)
                || !(unit.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos post = UnitChunkDutyGoal.boundPost(unit);
        if (post == null || !(level.getBlockEntity(post)
                instanceof GuardPostBlockEntity be)) {
            return;
        }
        int slot = be.unbind(unit.getUUID());
        if (slot == 1) {
            be.activateReserve(level);   // the reserve inherits the post
        }
    }

    // Called by MixinPmcUnitDutyClear when a unit's post duty is cleared by
    // a fresh SEM order - the player is still commanding it, but the slot
    // stays bound so "Return to post" can pull it back later.
    public static void onPostDutyCleared(PmcUnitEntity unit, int previousDuty) {
        if (previousDuty != UnitChunkDutyGoal.DUTY_POST
                || !(unit.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos post = UnitChunkDutyGoal.boundPost(unit);
        if (post == null || !(level.getBlockEntity(post)
                instanceof GuardPostBlockEntity be)) {
            return;
        }
        if (unit.getUUID().equals(be.primary())) {
            be.activateReserve(level);
        }
    }
}
