// Mixin package - Suppressor-class fire is a full tracer stream
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.item.ModernKineticGunScriptAPI;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Inside ModernKineticGunScriptAPI's bullet spawn path the tracer flag is
//   hasTracerAmmo() && operator.nextBulletIsTracer(interval)
// where the gun's tracer_count_interval decides every-Nth-round tracers.
// For Suppressor-class shooters we force the answer to true - their whole
// burst stream glows, so suppression fire actually LOOKS like suppression.
// (Guns with tracer_count_interval < 0 are still gated by hasTracerAmmo
// before this call - their ammo simply has no tracer round defined.)
// remap=false: the lambda and its target are both TaCZ-internal names.
@Mixin(ModernKineticGunScriptAPI.class)
public abstract class MixinGunTracer {

    @Redirect(method = "lambda$shootOnce$2", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/api/entity/IGunOperator;nextBulletIsTracer(I)Z"))
    private boolean semcs$suppressorTracers(IGunOperator operator, int interval) {
        if (operator instanceof LivingEntity living
                && WeaponClassSupport.getEffective(living) == WeaponClass.HEAVY_MG) {
            return true;
        }
        return operator.nextBulletIsTracer(interval);
    }
}
