// Client package - renderers only exist on the client (never server code!)
package com.poseidon.sem_classsystem.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.entity.VdfUnitEntity;
import com.poseidon.sem_classsystem.item.BhqArmorItem;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

// Worn-armor rendering for the independent VDF renderer - replaces the
// AbstractUnit-bound GeckoArmorLayerImpl it used to inherit.
//
// Flow mirrors SEM's proven path for the same BhqArmorItem pieces:
//   1. sync the unit's live bone poses into a HumanoidModel dummy
//      (name-walked, same trick as UnitCuriosRenderLayer)
//   2. apply the 1.5 / 1.56 scales our mixin imposes on SEM's humanoid path
//   3. ask Forge for the armor model - BhqArmorItem returns its
//      GeoArmorRenderer; it then prepForRender()s off the posed dummy and
//      resolves its own geo texture
//   4. render inside the fakeRoot -> unit transform so the piece sits in
//      the same space the bones were measured in
public class VdfArmorLayer extends RenderLayer<VdfUnitEntity, VdfUnitModel> {

    // Matches MixinUniversalArmorLayer's proportions on the SEM path
    private static final float ARMOR_SCALE = 1.5F;
    private static final float BOOT_SCALE = 1.56F;

    private final HumanoidModel<VdfUnitEntity> innerModel;
    private final HumanoidModel<VdfUnitEntity> outerModel;

    public VdfArmorLayer(RenderLayerParent<VdfUnitEntity, VdfUnitModel> parent,
                         EntityRendererProvider.Context context) {
        super(parent);
        this.innerModel = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
        this.outerModel = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       VdfUnitEntity entity, float limbSwing, float limbSwingAmount,
                       float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {
        if (entity.isDeadOrDying()) {
            return;
        }
        this.renderArmorPiece(poseStack, buffer, entity, EquipmentSlot.HEAD, packedLight,
                limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch);
        this.renderArmorPiece(poseStack, buffer, entity, EquipmentSlot.CHEST, packedLight,
                limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch);
        this.renderArmorPiece(poseStack, buffer, entity, EquipmentSlot.LEGS, packedLight,
                limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch);
        this.renderArmorPiece(poseStack, buffer, entity, EquipmentSlot.FEET, packedLight,
                limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch);
    }

    private void renderArmorPiece(PoseStack poseStack, MultiBufferSource buffer,
                                  VdfUnitEntity entity, EquipmentSlot slot, int packedLight,
                                  float limbSwing, float limbSwingAmount, float partialTicks,
                                  float ageInTicks, float netHeadYaw, float headPitch) {
        ItemStack stack = entity.getItemBySlot(slot);
        if (!(stack.getItem() instanceof ArmorItem armorItem)
                || armorItem.getEquipmentSlot() != slot) {
            return;
        }
        // Resolve the bone anchors once - same names the model bakes.
        ModelPart fakeRoot = this.getParentModel().root().getChild("fakeRoot");
        ModelPart unit = fakeRoot.getChild("unit");

        // Inner layer for pants/boots, outer for chest/head (vanilla split)
        HumanoidModel<VdfUnitEntity> base =
                (slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET)
                        ? this.innerModel : this.outerModel;
        syncHumanoidFromUnit(unit, base);
        applyUnitScale(base, slot);

        HumanoidModel<?> armorModel = IClientItemExtensions.of(armorItem)
                .getHumanoidArmorModel(entity, stack, slot, base);

        if (armorModel instanceof software.bernie.geckolib.renderer.GeoArmorRenderer<?> geo) {
            // Same sequence SEM's GeckoArmorLayerImpl + MixinGeckoArmorTexture
            // run for these pieces: hand the posed dummy to the geo renderer,
            // apply SEM's per-slot scale/offset tuning, then equalize the
            // asymmetric leg/boot/melee-arm offsets.
            geo.prepForRender(entity, stack, slot, base);
            net.nekoyuni.SimpleEnemyMod.compat.geckolib.internal.GeckoArmorAdjuster
                    .applyAdjustments(geo, base, slot);
            fixGeckoArmorAlignment(entity, base, slot);
        } else {
            // Plain vanilla armor model - standard visibility + pose pass
            setPartVisibility(armorModel, slot);
            @SuppressWarnings("unchecked")
            HumanoidModel<VdfUnitEntity> typed = (HumanoidModel<VdfUnitEntity>) armorModel;
            typed.prepareMobModel(entity, limbSwing, limbSwingAmount, partialTicks);
            typed.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks,
                    netHeadYaw, headPitch);
        }

        ResourceLocation texture = armorTexture(stack, entity, slot);

        poseStack.pushPose();
        armorModel.renderToBuffer(poseStack,
                buffer.getBuffer(RenderType.armorCutoutNoCull(texture)),
                packedLight, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
        poseStack.popPose();
    }

    // Mirrors GeckoArmorLayerImpl.syncModelParts + copyPartWithGlobalOffset:
    // adds unit.x/y/z and unit.xRot/yRot/zRot to each humanoid part so
    // GeckoArmorAdjuster's +6Y offsets land in the exact space it expects.
    private static void syncHumanoidFromUnit(ModelPart unitBone, HumanoidModel<?> target) {
        float pX = unitBone.x;
        float pY = unitBone.y;
        float pZ = unitBone.z;
        float rX = unitBone.xRot;
        float rY = unitBone.yRot;
        float rZ = unitBone.zRot;
        if (unitBone.hasChild("head")) copyPartWithGlobalOffset(unitBone.getChild("head"), target.head, pX, pY, pZ, rX, rY, rZ);
        if (unitBone.hasChild("body")) copyPartWithGlobalOffset(unitBone.getChild("body"), target.body, pX, pY, pZ, rX, rY, rZ);
        if (unitBone.hasChild("rightArm")) copyPartWithGlobalOffset(unitBone.getChild("rightArm"), target.rightArm, pX, pY, pZ, rX, rY, rZ);
        if (unitBone.hasChild("leftArm")) copyPartWithGlobalOffset(unitBone.getChild("leftArm"), target.leftArm, pX, pY, pZ, rX, rY, rZ);
        if (unitBone.hasChild("rightLeg")) copyPartWithGlobalOffset(unitBone.getChild("rightLeg"), target.rightLeg, pX, pY, pZ, rX, rY, rZ);
        if (unitBone.hasChild("leftLeg")) copyPartWithGlobalOffset(unitBone.getChild("leftLeg"), target.leftLeg, pX, pY, pZ, rX, rY, rZ);
        target.hat.copyFrom(target.head);
        target.crouching = false;
        target.riding = false;
        target.young = false;
    }

    private static void copyPartWithGlobalOffset(ModelPart source, ModelPart target,
                                                 float pX, float pY, float pZ,
                                                 float rX, float rY, float rZ) {
        boolean vis = target.visible;
        target.copyFrom(source);
        target.visible = vis;
        target.x += pX;
        target.y += pY;
        target.z += pZ;
        target.xRot += rX;
        target.yRot += rY;
        target.zRot += rZ;
    }

    private static void fixGeckoArmorAlignment(VdfUnitEntity entity, HumanoidModel<?> dummy, EquipmentSlot slot) {
        boolean melee = com.poseidon.sem_classsystem.entity.ai.UnitMeleeAttackGoal.isMeleeWeapon(
                entity.getMainHandItem());
        if (slot == EquipmentSlot.CHEST && melee) {
            dummy.rightArm.x -= 0.80F;
            dummy.leftArm.x += 0.80F;
            dummy.rightArm.y += 0.25F;
            dummy.leftArm.y += 0.25F;
            dummy.rightArm.z -= 0.20F;
            dummy.leftArm.z -= 0.20F;
        } else if (slot == EquipmentSlot.LEGS) {
            dummy.rightLeg.x += 0.25F;
            dummy.rightLeg.y += 0.50F;
            dummy.rightLeg.z -= 0.40F;
            dummy.leftLeg.x -= 0.10F;
            dummy.leftLeg.y += 0.02F;
            dummy.leftLeg.z -= 0.35F;
        } else if (slot == EquipmentSlot.FEET) {
            dummy.rightLeg.x -= 0.25F;
            dummy.rightLeg.y -= 1.00F;
            dummy.rightLeg.z -= 0.60F;
            dummy.leftLeg.x += 0.25F;
            dummy.leftLeg.y -= 1.00F;
            dummy.leftLeg.z -= 0.20F;
        }
    }

    // The unit skin + painted-gear overlay sits proud of a 1.0 armor shell,
    // so flat armor disappears inside the body - a mild uniform bump clears
    // it without SEM's ~x2 inflation. Boots scale past leggings or the
    // legging piece swallows them (same split as the SEM mixin).
    private static void applyUnitScale(HumanoidModel<?> model, EquipmentSlot slot) {
        for (ModelPart part : new ModelPart[]{
                model.head, model.hat, model.body, model.rightArm, model.leftArm}) {
            part.xScale = part.yScale = part.zScale = ARMOR_SCALE;
        }
        float legScale = slot == EquipmentSlot.FEET ? BOOT_SCALE : ARMOR_SCALE;
        model.rightLeg.xScale = model.rightLeg.yScale = model.rightLeg.zScale = legScale;
        model.leftLeg.xScale = model.leftLeg.yScale = model.leftLeg.zScale = legScale;
    }

    // Vanilla HumanoidArmorLayer split - only the parts the slot covers show.
    private static void setPartVisibility(HumanoidModel<?> model, EquipmentSlot slot) {
        model.setAllVisible(false);
        switch (slot) {
            case HEAD -> {
                model.head.visible = true;
                model.hat.visible = true;
            }
            case CHEST -> {
                model.body.visible = true;
                model.rightArm.visible = true;
                model.leftArm.visible = true;
            }
            case LEGS -> {
                model.body.visible = true;
                model.rightLeg.visible = true;
                model.leftLeg.visible = true;
            }
            case FEET -> {
                model.rightLeg.visible = true;
                model.leftLeg.visible = true;
            }
            default -> {
            }
        }
    }

    private static ResourceLocation armorTexture(ItemStack stack, VdfUnitEntity entity,
                                                 EquipmentSlot slot) {
        Item item = stack.getItem();
        // Our own gecko pieces resolve their texture by piece name; pass it
        // explicitly so the buffer target is right even if the renderer
        // consumes the supplied VertexConsumer instead of its own texture.
        if (item instanceof BhqArmorItem bhq) {
            return ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "textures/models/armor/" + bhq.piece() + ".png");
        }
        String path = ((net.minecraft.world.item.ArmorItem) item)
                .getArmorTexture(stack, entity, slot, null);
        if (path == null || path.isEmpty()) {
            path = "textures/models/armor/leather_layer_1.png";
        }
        return ResourceLocation.parse(path);
    }
}
