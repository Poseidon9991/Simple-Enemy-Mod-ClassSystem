// Mixin package - stops curios items rendering as corrupted purple armor
package com.poseidon.sem_classsystem.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.model.geom.ModelPart;
import net.nekoyuni.SimpleEnemyMod.compat.geckolib.GeckoCompat;
import net.nekoyuni.SimpleEnemyMod.compat.geckolib.GeckoCompatClient;
import net.nekoyuni.SimpleEnemyMod.compat.geckolib.internal.GeckoArmorLayerImpl;
import net.nekoyuni.SimpleEnemyMod.entity.client.pmc_unit.PmcUnitModel;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// GeckoArmorLayerImpl renders SEM's curios slots (back/head/body/belt) as
// armor pieces. Its getArmorResource computes a conventional
// "modid:textures/models/armor/<item>_layer_N.png" path - fine for real
// armor, but curios items (Artifacts rings, belts, charms) have no such
// file, so the piece draws with the purple/black missing texture.
//
// MixinUniversalArmorLayer fixed the same bug for the non-gecko layer by
// returning null - impossible here: the caller feeds the result straight
// into RenderType.armorCutoutNoCull() with no null check, so null would
// crash. Instead we swap the missing path for a 1x1 fully-transparent
// texture shipped in our jar: the cutout shader discards every fragment
// and the piece renders invisible - same effect, no NPE.
//
// Real armor (vanilla, modded, gecko) is untouched: only paths that
// resolve to nothing get replaced. Existence is cached per location.
@Mixin(GeckoArmorLayerImpl.class)
public abstract class MixinGeckoArmorTexture {

    // 1x1 transparent PNG shipped at assets/sem_classsystem/textures/models/armor/semcs_empty.png
    private static final ResourceLocation EMPTY_TEXTURE =
            ResourceLocation.parse("sem_classsystem:textures/models/armor/semcs_empty.png");
    private static final Map<ResourceLocation, Boolean> TEX_EXISTS = new ConcurrentHashMap<>();
    // unique paths already logged once - proves the swap is firing
    private static final java.util.Set<ResourceLocation> SWAP_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Shadow(remap = false)
    @Final
    private HumanoidModel<?> dummyHumanoidModel;

    @Shadow(remap = false)
    private void copyPartWithGlobalOffset(ModelPart source, ModelPart target,
                                          float pX, float pY, float pZ,
                                          float rX, float rY, float rZ,
                                          boolean preserveVisibility) {
    }

    // Guard renderArmorPiece: skip non-armor Curios (Artifacts rings, scarves,
    // hats, belts, charms) so GeckoArmorLayerImpl never wraps them onto
    // dummyHumanoidModel with a missing armor texture.
    // Also sync RUunitModel / USunitModel bones onto dummyHumanoidModel
    // (SEM's GeckoArmorLayerImpl only checked instanceof PmcUnitModel).
    @Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true, remap = false)
    private void semcs$skipNonArmorCurios(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                          AbstractUnit entity, ItemStack stack, EquipmentSlot slot,
                                          float limbSwing, float limbSwingAmount, float partialTicks,
                                          float ageInTicks, float netHeadYaw, float headPitch,
                                          CallbackInfo ci) {
        if (stack.isEmpty()) {
            ci.cancel();
            return;
        }
        boolean isGeckoArmor = GeckoCompat.LOADED && GeckoCompatClient.isGeckoArmor(stack);
        if (!isGeckoArmor && !(stack.getItem() instanceof ArmorItem)) {
            ci.cancel();
            return;
        }
        if (isGeckoArmor) {
            EntityModel<?> parent = ((GeckoArmorLayerImpl<?, ?>) (Object) this).getParentModel();
            if (parent instanceof HierarchicalModel<?> hModel && !(parent instanceof PmcUnitModel<?>)) {
                semcs$syncHierarchicalUnitParts(hModel);
            }
        }
    }

    @Unique
    private void semcs$syncHierarchicalUnitParts(HierarchicalModel<?> hModel) {
        ModelPart root = hModel.root();
        if (!root.hasChild("unit")) {
            return;
        }
        ModelPart unitParent = root.getChild("unit");
        float pX = unitParent.x;
        float pY = unitParent.y;
        float pZ = unitParent.z;
        float rX = unitParent.xRot;
        float rY = unitParent.yRot;
        float rZ = unitParent.zRot;

        if (unitParent.hasChild("head")) {
            copyPartWithGlobalOffset(unitParent.getChild("head"), this.dummyHumanoidModel.head, pX, pY, pZ, rX, rY, rZ, true);
        }
        if (unitParent.hasChild("body")) {
            copyPartWithGlobalOffset(unitParent.getChild("body"), this.dummyHumanoidModel.body, pX, pY, pZ, rX, rY, rZ, true);
        }
        if (unitParent.hasChild("rightArm")) {
            copyPartWithGlobalOffset(unitParent.getChild("rightArm"), this.dummyHumanoidModel.rightArm, pX, pY, pZ, rX, rY, rZ, true);
        }
        if (unitParent.hasChild("leftArm")) {
            copyPartWithGlobalOffset(unitParent.getChild("leftArm"), this.dummyHumanoidModel.leftArm, pX, pY, pZ, rX, rY, rZ, true);
        }
        if (unitParent.hasChild("rightLeg")) {
            copyPartWithGlobalOffset(unitParent.getChild("rightLeg"), this.dummyHumanoidModel.rightLeg, pX, pY, pZ, rX, rY, rZ, true);
        }
        if (unitParent.hasChild("leftLeg")) {
            copyPartWithGlobalOffset(unitParent.getChild("leftLeg"), this.dummyHumanoidModel.leftLeg, pX, pY, pZ, rX, rY, rZ, true);
        }
        this.dummyHumanoidModel.crouching = false;
        this.dummyHumanoidModel.riding = false;
        this.dummyHumanoidModel.young = false;
    }

    // remap=false: getArmorResource is a SEM-internal method name.
    @Inject(method = "getArmorResource", at = @At("RETURN"), cancellable = true, remap = false)
    private void semcs$emptyForMissingTexture(Entity entity, ItemStack stack, EquipmentSlot slot,
                                              String layer, CallbackInfoReturnable<ResourceLocation> cir) {
        ResourceLocation texture = cir.getReturnValue();
        if (texture == null) {
            return;
        }
        boolean exists = TEX_EXISTS.computeIfAbsent(texture, loc ->
                Minecraft.getInstance().getResourceManager().getResource(loc).isPresent());
        if (!exists) {
            if (SWAP_LOGGED.add(texture)) {
                com.poseidon.sem_classsystem.SemClassSystem.LOGGER.info(
                        "Swapped missing armor texture {} for the empty texture", texture);
            }
            cir.setReturnValue(EMPTY_TEXTURE);
        }
    }

    // Must match MixinUniversalArmorLayer.ARMOR_SCALE - this layer is the
    // other armor path (gecko/modded pieces) and hard-resets every synced
    // part to 1.0, which left modded armor looking small next to the
    // scaled vanilla pieces.
    private static final float ARMOR_SCALE = 1.5F;

    // remap=false: SEM-internal method name.
    @Inject(method = "copyPartWithGlobalOffset", at = @At("RETURN"), remap = false)
    private void semcs$scaleGeckoArmorPart(ModelPart source, ModelPart target,
                                           float pX, float pY, float pZ,
                                           float rX, float rY, float rZ,
                                           boolean preserveVisibility, CallbackInfo ci) {
        target.xScale = target.yScale = target.zScale = ARMOR_SCALE;
    }
}
