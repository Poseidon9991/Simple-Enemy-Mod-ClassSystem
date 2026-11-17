// Mixin package - equipment GUI title carries faction + class for our units
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.entity.BhqUnitEntity;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.poseidon.sem_classsystem.item.ArmorPlateItem;
import com.poseidon.sem_classsystem.item.BhqArmorItem;
import com.poseidon.sem_classsystem.menu.ArmorPlateMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// BhqUnitEntity inherits PmcUnitEntity.mobInteract, which opens the unit
// inventory with a hardcoded "PMC Unit Equipment" title. The class name no
// longer lives on the nameplate, so the GUI title carries it instead -
// "PMC Unit Equipment - Suppressor", "BHQ Unit Equipment - Breacher".
// mobInteract contains other literal() calls ("Unit Recruited!", status
// messages) - the redirect checks the string, only the equipment title swaps.
@Mixin(PmcUnitEntity.class)
public abstract class MixinPmcUnitMenuTitle {

    @Inject(method = "mobInteract", at = @At("HEAD"), cancellable = true)
    private void semcs$openUnitVestPlates(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (player.isShiftKeyDown() && player.getItemInHand(hand).isEmpty()) {
            PmcUnitEntity self = (PmcUnitEntity) (Object) this;
            if (!self.isOwnedBy(player)) {
                return;
            }
            ItemStack chest = self.getItemBySlot(EquipmentSlot.CHEST);
            EquipmentSlot targetSlot = null;
            if (chest.getItem() instanceof BhqArmorItem && ArmorPlateItem.maxPlateSlots(chest) > 0) {
                targetSlot = EquipmentSlot.CHEST;
            } else {
                ItemStack head = self.getItemBySlot(EquipmentSlot.HEAD);
                if (head.getItem() instanceof BhqArmorItem && ArmorPlateItem.maxPlateSlots(head) > 0) {
                    targetSlot = EquipmentSlot.HEAD;
                }
            }
            if (targetSlot != null) {
                if (!self.level().isClientSide && player instanceof ServerPlayer sp) {
                    ArmorPlateMenu.openForEntity(sp, self, targetSlot);
                }
                cir.setReturnValue(InteractionResult.sidedSuccess(self.level().isClientSide));
            }
        }
    }

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
        // Recruited units carry unique callsigns: "Sgt. Jony - PMC Unit Equipment - Rifleman"
        Component name = self.getCustomName();
        if (name != null) {
            title = com.poseidon.sem_classsystem.veterancy.UnitVeterancy.rankTitle(self)
                    + " " + name.getString() + " - " + title;
        }
        return Component.literal(title);
    }
}
