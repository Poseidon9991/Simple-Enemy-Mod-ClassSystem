// Mixin package - equipment GUI title carries faction + class for our units
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.entity.BhqUnitEntity;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// BhqUnitEntity inherits PmcUnitEntity.mobInteract, which opens the unit
// inventory with a hardcoded "PMC Unit Equipment" title. The class name no
// longer lives on the nameplate, so the GUI title carries it instead -
// "PMC Unit Equipment - Suppressor", "BHQ Unit Equipment - Breacher".
// mobInteract contains other literal() calls ("Unit Recruited!", status
// messages) - the redirect checks the string, only the equipment title swaps.
@Mixin(PmcUnitEntity.class)
public abstract class MixinPmcUnitMenuTitle {

    @Redirect(method = "mobInteract",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/chat/Component;literal(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;"))
    private MutableComponent semcs$classMenuTitle(String text) {
        if (!"PMC Unit Equipment".equals(text)) {
            return Component.literal(text);
        }
        PmcUnitEntity self = (PmcUnitEntity) (Object) this;
        String title = (self instanceof BhqUnitEntity ? "BHQ" : "PMC") + " Unit Equipment";
        WeaponClass cls = WeaponClassSupport.get(self);
        if (cls != null) {
            title += " - " + ClassSystemConfig.classNickname(cls);
        }
        return Component.literal(title);
    }
}
