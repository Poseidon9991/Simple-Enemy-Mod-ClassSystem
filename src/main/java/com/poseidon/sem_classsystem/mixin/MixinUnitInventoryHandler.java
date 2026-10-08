// Mixin package - locks the PMC inventory weapon slots to the unit's class
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.faction.ClassLoadouts;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
// TaCZ gun item interface - used to detect "is this stack a gun" and read its id
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
// SEM's per-unit inventory handler - slots 0=main hand(gun), 1=offhand,
// 2-5=armor, 6-17=storage. Backing for the PMC unit GUI.
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.inventory.UnitInventoryHandler;
// Mixin annotations:
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Patch SEM's UnitInventoryHandler. Because Forge's SlotItemHandler.mayPlace
// delegates to isItemValid(), blocking here rejects the item in the GUI,
// on shift-click, and via hoppers - every path goes through isItemValid.
@Mixin(UnitInventoryHandler.class)
public abstract class MixinUnitInventoryHandler {

    // The handler knows its owner unit - shadow that field to ask its class.
    // remap=false: SEM's own field, same name in dev and production jars.
    @Shadow(remap = false)
    @Final
    private PmcUnitEntity unit;

    // HEAD-inject into isItemValid with cancellable=true: we can veto the
    // insertion by setting the return value to false.
    // remap=false: isItemValid is a Forge ItemStackHandler method - Forge API
    // names are not obfuscated, so no remapping needed.
    @Inject(method = "isItemValid", at = @At("HEAD"), cancellable = true, remap = false)
    private void semcs$lockGunSlots(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        // Only the two weapon slots are class-locked - storage/armor untouched
        if (slot != 0 && slot != 1) {
            return;
        }
        // Non-gun items keep SEM's original rules (the main-hand slot accepts
        // anything by default - we only police GUNS)
        if (!(stack.getItem() instanceof AbstractGunItem)) {
            return;
        }
        // No class assigned -> no lock (fresh unit mid-setup)
        WeaponClass cls = WeaponClassSupport.get(this.unit);
        if (cls == null) {
            return;
        }
        // Which class does this gun belong to? Read the gun id through TaCZ.
        IGun iGun = IGun.getIGunOrNull(stack);
        ResourceLocation gunId = iGun != null ? iGun.getGunId(stack) : null;
        WeaponClass gunClass = gunId == null ? null : ClassLoadouts.inferClass(gunId);

        // Unknown/custom guns stay allowed - only guns we KNOW belong to a
        // different class get rejected. A Camper can never hold an AK-47.
        if (gunClass != null && gunClass != cls) {
            cir.setReturnValue(false);
        }
    }
}
