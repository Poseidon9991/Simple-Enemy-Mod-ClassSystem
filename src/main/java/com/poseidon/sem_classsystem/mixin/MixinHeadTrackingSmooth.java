package com.poseidon.sem_classsystem.mixin;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.client.animation.procedural.HeadTrackingLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Fixes 20-TPS stepping in SEM's HeadTrackingLayer by interpolating head yaw,
// body yaw, and pitch with the partial tick fraction from ageInTicks.
// Since AdvancedWeaponPoseLayer derives arm aim from head.xRot / head.yRot,
// this makes both head tracking and weapon aiming smooth at full frame rate.
@Mixin(HeadTrackingLayer.class)
public abstract class MixinHeadTrackingSmooth {

    @Shadow(remap = false) @Final private ModelPart head;
    @Shadow(remap = false) private float maxYawDegrees;
    @Shadow(remap = false) private float minPitchDegrees;
    @Shadow(remap = false) private float maxPitchDegrees;

    @Inject(method = "applyTransformations", at = @At("HEAD"), cancellable = true, remap = false)
    private void semcs$smoothHeadTracking(ModelPart root, Entity entity, float ageInTicks, CallbackInfo ci) {
        if (!(entity instanceof LivingEntity living)) {
            ci.cancel();
            return;
        }

        float pt = Mth.frac(ageInTicks);
        float bodyYaw = Mth.rotLerp(pt, living.yBodyRotO, living.yBodyRot);
        float headYaw = Mth.rotLerp(pt, living.yHeadRotO, living.yHeadRot);
        float netHeadYaw = Mth.wrapDegrees(headYaw - bodyYaw);
        float headPitch = Mth.lerp(pt, living.xRotO, living.getXRot());

        netHeadYaw = Mth.clamp(netHeadYaw, -this.maxYawDegrees, this.maxYawDegrees);
        headPitch = Mth.clamp(headPitch, this.minPitchDegrees, this.maxPitchDegrees);

        this.head.yRot += netHeadYaw * Mth.DEG_TO_RAD;
        this.head.xRot += headPitch * Mth.DEG_TO_RAD;
        ci.cancel();
    }
}
