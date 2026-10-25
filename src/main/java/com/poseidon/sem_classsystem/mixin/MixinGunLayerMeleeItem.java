// Client-side mixin - renders melee weapons and non-gun items held by SEM units.
//
// In SEM, GunLayerRenderer only renders items that are instances of AbstractGunItem.
// Any melee weapon (swords, axes, knives) held by a unit is completely invisible!
//
// This mixin hooks GunLayerRenderer.render: if the held item is NOT a gun and NOT empty,
// it renders the item in the rightHandAnchor with proper third-person transforms.
package com.poseidon.sem_classsystem.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.poseidon.sem_classsystem.client.UnitProceduralAnimState;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.entity.client.GunLayerRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GunLayerRenderer.class)
public abstract class MixinGunLayerMeleeItem<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {

    @Shadow(remap = false) @Final private ItemInHandRenderer itemRenderer;

    protected MixinGunLayerMeleeItem() {
        super(null);
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void semcs$renderMeleeItem(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            T entity,
            float limbSwing,
            float limbSwingAmount,
            float partialTicks,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            CallbackInfo ci
    ) {
        if (entity.isDeadOrDying()) {
            return;
        }

        // Render offhand food / bandage / medkit in the left hand while the unit is consuming it
        if (entity.isUsingItem() && !entity.getOffhandItem().isEmpty()) {
            M parentModel = this.getParentModel();
            if (parentModel instanceof HierarchicalModel<?> hierarchicalModel) {
                ModelPart fakeRoot = hierarchicalModel.root();
                ModelPart unit = fakeRoot.getChild("unit");
                ModelPart leftArm = unit.getChild("leftArm");

                poseStack.pushPose();
                fakeRoot.translateAndRotate(poseStack);
                unit.translateAndRotate(poseStack);
                leftArm.translateAndRotate(poseStack);

                poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
                poseStack.translate(-1.0F / 16.0F, 0.125F, -0.625F);

                this.itemRenderer.renderItem(
                        entity,
                        entity.getOffhandItem(),
                        ItemDisplayContext.THIRD_PERSON_LEFT_HAND,
                        true,
                        poseStack,
                        buffer,
                        packedLight
                );
                poseStack.popPose();
            }
        }

        ItemStack stack = entity.getItemInHand(InteractionHand.MAIN_HAND);
        if (stack.isEmpty()) {
            return;
        }

        // Guns: smoothly blend between low-ready patrol transform and high-ready combat transform,
        // plus apply per-shot recoil and reload cant so the weapon matches the animated arms.
        if (stack.getItem() instanceof AbstractGunItem gunItem) {
            ResourceLocation gunId = gunItem.getGunId(stack);
            if (gunId != null && gunId.getPath().contains("minigun")) {
                return; // let SEM's minigun renderer handle miniguns
            }
            M parentModel = this.getParentModel();
            if (!(parentModel instanceof HierarchicalModel<?> hierarchicalModel)) {
                return;
            }
            UnitProceduralAnimState state = UnitProceduralAnimState.get(entity);
            float b = state.smoothCombatBlend();
            float recoil = state.recoilImpulse() * UnitProceduralAnimState.classRecoilScale(entity);
            float rel = state.smoothReloadBlend();

            ModelPart unit = hierarchicalModel.root().getChild("unit");
            ModelPart rightArm = unit.getChild("rightArm");
            ModelPart rightHandAnchor = rightArm.getChild("rightHandAnchor");

            poseStack.pushPose();
            unit.translateAndRotate(poseStack);
            rightArm.translateAndRotate(poseStack);
            rightHandAnchor.translateAndRotate(poseStack);

            double ty = Mth.lerp(b, 0.35D, 0.10D);
            double tz = Mth.lerp(b, 0.13D, 0.28D) - (recoil * 0.012D);
            float rx = Mth.lerp(b, -90.0F, -95.0F) + (recoil * 1.5F) + (rel * 8.0F);
            float rz = rel * -16.0F;

            poseStack.translate(0.0D, ty, tz);
            poseStack.mulPose(Axis.YP.rotationDegrees(-180.0F));
            poseStack.mulPose(Axis.XP.rotationDegrees(rx));
            if (Math.abs(rz) > 0.01F) {
                poseStack.mulPose(Axis.ZP.rotationDegrees(rz));
            }
            poseStack.scale(1.0F, -1.0F, -1.0F);

            this.itemRenderer.renderItem(
                    entity,
                    stack,
                    ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    false,
                    poseStack,
                    buffer,
                    packedLight
            );

            poseStack.popPose();
            ci.cancel();
            return;
        }

        // Render melee / non-gun item in hand anchor
        M parentModel = this.getParentModel();
        if (!(parentModel instanceof HierarchicalModel<?> hierarchicalModel)) {
            return;
        }

        ModelPart fakeRoot = hierarchicalModel.root();
        ModelPart unit = fakeRoot.getChild("unit");
        ModelPart rightArm = unit.getChild("rightArm");
        ModelPart rightHandAnchor = rightArm.getChild("rightHandAnchor");

        poseStack.pushPose();

        fakeRoot.translateAndRotate(poseStack);
        unit.translateAndRotate(poseStack);
        rightArm.translateAndRotate(poseStack);
        rightHandAnchor.translateAndRotate(poseStack);

        // rightHandAnchor is already at (-1, 9, 0) in rightArm space; center
        // the sword hilt cleanly inside the fist and add wrist follow-through
        // during combo slashes so the blade tracks the arm swing arc.
        UnitProceduralAnimState state = UnitProceduralAnimState.get(entity);
        float swing = state.meleeSwingProgress();
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        if (swing > 0.001F) {
            float wristSnap = Mth.sin(swing * (float) Math.PI) * 18.0F;
            poseStack.mulPose(Axis.XP.rotationDegrees(wristSnap));
        }
        poseStack.translate(0.0F, 0.0625F, -0.0625F);

        this.itemRenderer.renderItem(
                entity,
                stack,
                ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                false,
                poseStack,
                buffer,
                packedLight
        );

        poseStack.popPose();
        ci.cancel();
    }
}
