// Mixin package - fixes the inherited inventory GUI title for BHQ units
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.entity.BhqUnitEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// BhqUnitEntity inherits PmcUnitEntity.mobInteract, which opens the unit
// inventory with a hardcoded "PMC Unit Equipment" title. We redirect the
// Component.literal call and swap the title when the unit is ours.
// mobInteract contains other literal() calls ("Unit Recruited!", status
// messages) - the redirect checks the string, only the equipment title swaps.
@Mixin(PmcUnitEntity.class)
public abstract class MixinPmcUnitMenuTitle {

    @Redirect(method = "mobInteract",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/chat/Component;literal(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;"))
    private MutableComponent semcs$bhqMenuTitle(String text) {
        if ("PMC Unit Equipment".equals(text)
                && (Object) this instanceof BhqUnitEntity) {
            return Component.literal("BHQ Unit Equipment");
        }
        return Component.literal(text);
    }
}
