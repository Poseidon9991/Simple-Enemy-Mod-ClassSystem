// Mixin package - stops curios items rendering as corrupted purple armor
package com.poseidon.sem_classsystem.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.model.geom.ModelPart;
import net.nekoyuni.SimpleEnemyMod.compat.geckolib.internal.GeckoArmorLayerImpl;
import org.spongepowered.asm.mixin.Mixin;
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
