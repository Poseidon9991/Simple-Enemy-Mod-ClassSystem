package com.poseidon.sem_classsystem.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.poseidon.sem_classsystem.client.UnitProceduralAnimState;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Tilts the entire unit PoseStack at the TAIL of LivingEntityRenderer.setupRotations
// when a SEM unit is prone. Because setupRotations runs on the master PoseStack
// BEFORE the base model and BEFORE all RenderLayers (GeckoArmorLayerImpl,
// UniversalArmorLayer, GunLayerRenderer, tacz_sewv gear layers), rotating here
// keeps all 3D GeckoLib armor pieces and held guns 100% locked to the unit's body!
@Mixin(LivingEntityRenderer.class)
public abstract class MixinUnitRenderProne {

    @Inject(method = "setupRotations", at = @At("TAIL"))
    private void semcs$applyProneTransform(LivingEntity entity, PoseStack poseStack,
                                           float ageInTicks, float bodyYRot, float partialTicks,
                                           CallbackInfo ci) {
        if (!(entity instanceof AbstractUnit unit) || unit.deathAnimationState.isStarted()) {
            return;
        }
        float p = UnitProceduralAnimState.get(unit).smoothProneBlend();
        if (p > 0.001F) {
            poseStack.translate(0.0F, 0.18F * p, 0.62F * p);
            poseStack.mulPose(Axis.XP.rotationDegrees(-82.0F * p));
        }
    }
}
