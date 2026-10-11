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

    // SEM inflates head-slot armor parts x1.28 so vanilla helmets wrap the
    // unit head. For our cap that scale drags the brim, crown and ear
    // defenders off the head and across the face - restore 1.0 so the cap
    // hugs the head as authored. Vanilla/mod helmets keep SEM's look.
    @Inject(method = "applyManualScale", at = @At("TAIL"), remap = false)
    private void semcs$snugBhqHead(HumanoidModel<LivingEntity> armorModel,
                                   EquipmentSlot slot, CallbackInfo ci) {
        if (slot == EquipmentSlot.HEAD && armorModel instanceof BhqArmorModels.BhqArmorModel) {
            armorModel.head.xScale = armorModel.head.yScale = armorModel.head.zScale = 1.0F;
            armorModel.hat.xScale = armorModel.hat.yScale = armorModel.hat.zScale = 1.0F;
        }
    }
}
