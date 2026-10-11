// Mixin package - gives SWIMMING-posed SEM units a real prone hitbox
package com.poseidon.sem_classsystem.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Prone units must actually BE low: without this the hitbox and the firing
// eye stay at standing height while the model lies flat - players would
// shoot the visible body and whiff the invisible box above it, and the
// unit's own rounds would leave from mid-air. Vanilla only overrides
// getDimensions on Player/a few mobs, so the override has to live on
// Entity with an instanceof guard - one cheap check per call, other
// entities take the vanilla path.
@Mixin(Entity.class)
public abstract class MixinUnitProneDimensions {

    // Belly-down silhouette: shoulder-width, shin-height.
    private static final EntityDimensions SEMCS$PRONE =
            EntityDimensions.scalable(0.8F, 0.6F);

    @Inject(method = "getDimensions", at = @At("HEAD"), cancellable = true)
    private void semcs$proneDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        if (pose == Pose.SWIMMING && (Object) this instanceof AbstractUnit) {
            cir.setReturnValue(SEMCS$PRONE);
        }
    }

    // Fire origin while prone - roughly chest height of a lying body.
    @Inject(method = "getEyeHeight", at = @At("HEAD"), cancellable = true)
    private void semcs$proneEyeHeight(Pose pose, EntityDimensions dimensions,
                                      CallbackInfoReturnable<Float> cir) {
        if (pose == Pose.SWIMMING && (Object) this instanceof AbstractUnit) {
            cir.setReturnValue(0.35F);
        }
    }
}
