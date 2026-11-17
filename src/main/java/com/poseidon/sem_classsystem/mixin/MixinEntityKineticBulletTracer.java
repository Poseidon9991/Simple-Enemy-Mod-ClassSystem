// Mixin package - enables glowing cyan Type-95 tracers on mana-infused TaCZ bullets
package com.poseidon.sem_classsystem.mixin;

import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityKineticBullet.class)
public abstract class MixinEntityKineticBulletTracer {

    @Unique
    private static final String SEMCS_MANA_BULLET_KEY = "semcs_mana_bullet";

    // Force TaCZ's EntityBulletRenderer to draw its full glowing tracer beam
    // (colored cyan via TRACER_COLOR_OVERRIDER_KEY) even on non-tracer ammo packs.
    @Inject(method = "isTracerAmmo", at = @At("HEAD"), cancellable = true, remap = false)
    private void semcs$forceManaBulletTracer(CallbackInfoReturnable<Boolean> cir) {
        if (((Entity) (Object) this).getPersistentData().getBoolean(SEMCS_MANA_BULLET_KEY)) {
            cir.setReturnValue(true);
        }
    }
}
