package com.poseidon.sem_classsystem.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.neoalive.tacz_sewv.client.CuriosHeadLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Fixes the purple-and-black box drawn by tacz_sewv's CuriosHeadLayer when
// a unit wears a head curio from Artifacts (or any other mod):
// CuriosHeadLayer checks CuriosRendererRegistry.getRenderer(item).isPresent()
// and then hardcodes "<modid>:textures/curio/<item>.png" on SuperbWarfare's
// thermal_imaging_goggles mesh. For Artifacts items that texture path does
// not exist, producing a giant missing-texture box on the unit's head.
@Pseudo
@Mixin(value = CuriosHeadLayer.class, remap = false)
public abstract class MixinSewvGearLayers {

    @Unique
    private static final Map<ResourceLocation, Boolean> SEMCS$CURIO_TEX_EXISTS = new ConcurrentHashMap<>();

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void semcs$skipMissingHeadCurioTexture(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                                   LivingEntity entity, float limbSwing, float limbSwingAmount,
                                                   float partialTicks, float ageInTicks, float netHeadYaw,
                                                   float headPitch, CallbackInfo ci) {
        ItemStack stack = semcs$getHeadCurio(entity);
        if (stack.isEmpty()) {
            return;
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) {
            ci.cancel();
            return;
        }
        ResourceLocation expectedTex = ResourceLocation.fromNamespaceAndPath(
                key.getNamespace(), "textures/curio/" + key.getPath() + ".png");
        boolean exists = SEMCS$CURIO_TEX_EXISTS.computeIfAbsent(expectedTex, loc ->
                Minecraft.getInstance().getResourceManager().getResource(loc).isPresent());
        if (!exists) {
            ci.cancel();
        }
    }

    @Unique
    private static ItemStack semcs$getHeadCurio(LivingEntity entity) {
        var inv = CuriosApi.getCuriosInventory(entity).orElse(null);
        if (inv == null) {
            return ItemStack.EMPTY;
        }
        ICurioStacksHandler head = inv.getStacksHandler("head").orElse(null);
        if (head == null || head.getSlots() < 1) {
            return ItemStack.EMPTY;
        }
        return head.getStacks().getStackInSlot(0);
    }
}
