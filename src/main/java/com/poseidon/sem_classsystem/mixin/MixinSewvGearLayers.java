// Mixin package - blocks tacz_sewv's gear render layers on SEM units
package com.poseidon.sem_classsystem.mixin;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// tacz_sewv paints equipment (mich helmets, vests, holsters, headwear) onto
// SEM units through layers it attaches in its own AddLayers handler - that
// list covers PMC/RU/US natively, so the wrong-color gear shows even without
// anything this addon does. The bedrock armor layer samples the UNIT'S SKIN
// as the gear texture, so any skin not authored for its exact UV layout
// renders in garbled colors.
//
// Suppressing at addLayer covers every attach point (sewv's handler, any
// future one) in one place. Only net.nekoyuni.SimpleEnemyMod / our own unit
// renderers are affected - sewv's own entities (pmc commander etc.) keep
// their layers. The check is fully name-based: no sewv class is loaded or
// referenced, so nothing breaks when the mod is absent.
@Mixin(LivingEntityRenderer.class)
public abstract class MixinSewvGearLayers {

    @Inject(method = "addLayer", at = @At("HEAD"), cancellable = true)
    private void semcs$skipSewvGear(RenderLayer<?, ?> layer, CallbackInfoReturnable<Boolean> cir) {
        if (!layer.getClass().getName().startsWith("com.neoalive.tacz_sewv.")) {
            return;
        }
        String renderer = this.getClass().getName();
        if (renderer.startsWith("net.nekoyuni.SimpleEnemyMod.")
                || renderer.startsWith("com.poseidon.sem_classsystem.")) {
            cir.setReturnValue(false);
        }
    }
}
