// Mixin package - fixes missing textures on modded armor (Artifacts etc.)
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.client.BhqArmorModels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.client.util.UniversalArmorLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// SEM's armor layer computes a texture path for every ArmorItem a unit
// wears. Armor from mods like Artifacts declares paths that only exist for
// their own renderer, so the layer ends up drawing the missing-texture
// black/purple checkerboard on the unit.
//
// Fix: after getArmorTexture resolves a location, verify the texture file
// actually exists in the active resource packs. If not -> return null and
// the caller's existing "texture == null -> skip" branch takes over, so the
// unit simply shows no armor instead of a corrupted overlay.
//
// Existence is cached per path - the check runs up to 4 times per frame
// per unit, and resource lookups don't change outside a resource reload.
@Mixin(UniversalArmorLayer.class)
public abstract class MixinUniversalArmorLayer {

    private static final Map<ResourceLocation, Boolean> SEMCS$TEX_EXISTS = new ConcurrentHashMap<>();

    @Inject(method = "getArmorTexture", at = @At("RETURN"), cancellable = true, remap = false)
    private void semcs$skipMissingTexture(net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit entity,
                                          net.minecraft.world.item.ItemStack stack,
                                          EquipmentSlot slot,
                                          net.minecraft.world.item.ArmorItem armorItem,
                                          CallbackInfoReturnable<ResourceLocation> cir) {
        ResourceLocation texture = cir.getReturnValue();
        if (texture == null) {
            return;
        }
        boolean exists = SEMCS$TEX_EXISTS.computeIfAbsent(texture, loc ->
                Minecraft.getInstance().getResourceManager().getResource(loc).isPresent());
        if (!exists) {
            cir.setReturnValue(null);
        }
    }

    // Same guard for the dyeable-armor overlay pass (leather-style items):
    // a missing overlay texture is just as purple-black as the base one.
    @Inject(method = "getArmorOverlayTexture", at = @At("RETURN"), cancellable = true, remap = false)
    private void semcs$skipMissingOverlayTexture(net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit entity,
                                                 net.minecraft.world.item.ItemStack stack,
                                                 EquipmentSlot slot,
                                                 net.minecraft.world.item.ArmorItem armorItem,
                                                 CallbackInfoReturnable<ResourceLocation> cir) {
        ResourceLocation texture = cir.getReturnValue();
        if (texture == null) {
            return;
        }
        boolean exists = SEMCS$TEX_EXISTS.computeIfAbsent(texture, loc ->
                Minecraft.getInstance().getResourceManager().getResource(loc).isPresent());
        if (!exists) {
            cir.setReturnValue(null);
        }
    }

    // SEM inflates armor parts on units (head x1.28, body ~x2, legs/boots
    // ~x2.1) and shifts limbs by hand-tuned offsets - tuned for vanilla
    // armor shells, but on our multi-piece gear it produced a fat blob
    // layer that swallows the skin. Our models are authored at player
    // proportions, so skip SEM's scale and offset passes entirely: the
    // pieces then sit exactly like they do on a player.
    @Inject(method = "applyManualScale", at = @At("HEAD"), cancellable = true, remap = false)
    private void semcs$playerScaleArmor(HumanoidModel<LivingEntity> armorModel,
                                        EquipmentSlot slot, CallbackInfo ci) {
        if (armorModel instanceof BhqArmorModels.BhqArmorModel) {
            resetScale(armorModel);
            ci.cancel();
        }
    }

    @Inject(method = "applyManualPosition", at = @At("HEAD"), cancellable = true, remap = false)
    private void semcs$playerPositionArmor(HumanoidModel<LivingEntity> armorModel,
                                           EquipmentSlot slot, CallbackInfo ci) {
        if (armorModel instanceof BhqArmorModels.BhqArmorModel) {
            ci.cancel();
        }
    }

    // Part scale persists on the shared baked model - if an older render
    // ever set it, snap it back so SEM's pass can't leak through.
    private static void resetScale(HumanoidModel<?> model) {
        for (var part : new net.minecraft.client.model.geom.ModelPart[]{
                model.head, model.hat, model.body,
                model.rightArm, model.leftArm, model.rightLeg, model.leftLeg}) {
            part.xScale = part.yScale = part.zScale = 1.0F;
        }
    }
}
