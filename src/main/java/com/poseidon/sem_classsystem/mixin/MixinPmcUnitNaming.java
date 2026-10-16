// Mixin package - assigns a unique callsign name the moment a PMC/BHQ unit
// is recruited (first setOwner call), covering both the Recruit Table path
// and SEM's interact-to-recruit path.
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.world.UnitNameRegistry;
import net.minecraft.server.level.ServerLevel;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(PmcUnitEntity.class)
public abstract class MixinPmcUnitNaming {

    @Inject(method = "setOwner", at = @At("TAIL"), remap = false)
    private void semcs$assignNameOnRecruit(UUID uuid, CallbackInfo ci) {
        PmcUnitEntity self = (PmcUnitEntity) (Object) this;
        if (uuid == null || self.level().isClientSide
                || !(self.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        UnitNameRegistry.assignUniqueName(self, serverLevel);
    }
}
