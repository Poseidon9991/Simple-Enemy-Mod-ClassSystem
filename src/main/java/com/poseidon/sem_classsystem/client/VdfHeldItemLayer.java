// Client package - renderers only exist on the client (never server code!)
package com.poseidon.sem_classsystem.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.poseidon.sem_classsystem.entity.VdfUnitEntity;
import com.tacz.guns.api.item.IGun;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

// Renders the militia's held item at the model's rightHandAnchor bone -
// our standalone replacement for SEM's GunLayerRenderer (which was bound to
// AbstractUnit). Bone chain is resolved by name so this stays correct even
// if the rig's root tree changes.
//
// The gun transforms mirror SEM's three stances exactly:
//   combat  - translate(0, 0.1, 0.28), YP -180, XP -95  (raised, shouldered)
//   idle    - translate(0, 0.35, 0.13), YP -180, XP -90 (lowered carry)
//   minigun - translate(0.01, 0.3, -0.25), YP -180, XP -70, roll +6 (hip carry)
// Non-gun items (the melee militia's sword) use the idle carry pose.
public class VdfHeldItemLayer extends RenderLayer<VdfUnitEntity, VdfUnitModel> {

    private final ItemInHandRenderer itemInHandRenderer;

    public VdfHeldItemLayer(RenderLayerParent<VdfUnitEntity, VdfUnitModel> parent,
                            ItemInHandRenderer itemInHandRenderer) {
        super(parent);
        this.itemInHandRenderer = itemInHandRenderer;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       VdfUnitEntity entity, float limbSwing, float limbSwingAmount,
                       float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {
        if (entity.isDeadOrDying()) {
            return;
        }
        ItemStack stack = entity.getItemInHand(InteractionHand.MAIN_HAND);
        if (stack.isEmpty()) {
            return;
        }
        // Walk the bone chain by name: root -> fakeRoot -> unit -> rightArm
        // -> rightHandAnchor
        ModelPart part = this.getParentModel().root();
        if (!part.hasChild("fakeRoot")) {
            return;
        }
        ModelPart fakeRoot = part.getChild("fakeRoot");
        ModelPart unit = fakeRoot.getChild("unit");
        if (!unit.hasChild("rightArm")
                || !unit.getChild("rightArm").hasChild("rightHandAnchor")) {
            return;
        }
        ModelPart rightArm = unit.getChild("rightArm");
        ModelPart anchor = rightArm.getChild("rightHandAnchor");

        IGun iGun = IGun.getIGunOrNull(stack);
        boolean isGun = iGun != null;
        boolean inCombat = entity.getTarget() != null;
        ResourceLocation gunId = isGun ? iGun.getGunId(stack) : null;
        boolean minigun = gunId != null && gunId.getPath().contains("minigun");

        poseStack.pushPose();
        fakeRoot.translateAndRotate(poseStack);
        unit.translateAndRotate(poseStack);
        rightArm.translateAndRotate(poseStack);
        anchor.translateAndRotate(poseStack);

        if (minigun) {
            // Hip carry - SEM's minigun branch overrides both stances
            poseStack.translate(0.01D, 0.3D, -0.25D);
            poseStack.mulPose(Axis.YP.rotationDegrees(-180.0F));
            poseStack.mulPose(Axis.XP.rotationDegrees(-70.0F));
            poseStack.mulPose(Axis.ZP.rotationDegrees(6.0F));
        } else if (isGun && inCombat) {
            // Shouldered firing stance (SEM's combat numbers)
            poseStack.translate(0.0D, 0.1D, 0.28D);
            poseStack.mulPose(Axis.YP.rotationDegrees(-180.0F));
            poseStack.mulPose(Axis.XP.rotationDegrees(-95.0F));
        } else {
            // Lowered carry - idle guns and melee weapons
            poseStack.translate(0.0D, 0.35D, 0.13D);
            poseStack.mulPose(Axis.YP.rotationDegrees(-180.0F));
            poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
        }
        poseStack.scale(1.0F, -1.0F, -1.0F);
        this.itemInHandRenderer.renderItem(entity, stack,
                ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, false,
                poseStack, buffer, packedLight);
        poseStack.popPose();
    }
}
