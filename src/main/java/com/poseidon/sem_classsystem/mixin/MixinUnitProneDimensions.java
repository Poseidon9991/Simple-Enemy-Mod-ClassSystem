package com.poseidon.sem_classsystem.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Gives prone (Pose.SWIMMING) SEM units a real low-profile hitbox (0.8 x 0.6)
// and a 0.38 eye height so bullets originate from the prone rifle barrel.
@Mixin(Entity.class)
public abstract class MixinUnitProneDimensions {

    private static final EntityDimensions SEMCS$PRONE =
            EntityDimensions.scalable(0.8F, 0.6F);

    @Inject(method = "getDimensions", at = @At("HEAD"), cancellable = true)
    private void semcs$proneDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        if (pose == Pose.SWIMMING && (Object) this instanceof AbstractUnit) {
            cir.setReturnValue(SEMCS$PRONE);
        }
    }

    @Inject(method = "getEyeHeight", at = @At("HEAD"), cancellable = true)
    private void semcs$proneEyeHeight(Pose pose, EntityDimensions dimensions,
                                      CallbackInfoReturnable<Float> cir) {
        if (pose == Pose.SWIMMING && (Object) this instanceof AbstractUnit) {
            cir.setReturnValue(0.38F);
        }
    }
}
