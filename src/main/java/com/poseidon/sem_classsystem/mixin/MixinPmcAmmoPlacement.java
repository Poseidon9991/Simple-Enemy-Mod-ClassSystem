// Mixin package - keeps PMC reserve ammo out of the equipment slots
package com.poseidon.sem_classsystem.mixin;

// TaCZ ammo interface - detects "is this stack an ammo item"
import com.tacz.guns.api.item.IAmmo;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
// Forge capability + handler interfaces to reach the unit's inventory
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandlerModifiable;
// SEM's PMC equipper - THE CLASS WE PATCH
import net.nekoyuni.SimpleEnemyMod.entity.equipment.PmcUnitWeaponEquipper;
// Mixin annotations:
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// SEM's PmcUnitWeaponEquipper scans for the first EMPTY slot starting at
// slot 1, so the reserve ammo stack lands in the OFFHAND slot - and
// onContentsChanged() then visually equips the bullets in the unit's
// left hand. If the offhand is occupied it can even land in armor slots.
//
// This TAIL inject runs after the equipper finishes: it sweeps every ammo
// stack found in slots 1-5 (offhand + armor) into the first free storage
// slot (6-17). Storage slots have no equipment sync, so the ammo stays
// invisible while remaining consumable - TaCZ's reload scans the whole
// item handler anyway.
@Mixin(PmcUnitWeaponEquipper.class)
public abstract class MixinPmcAmmoPlacement {

    // Slot 1 is the offhand; 2-5 are armor. Anything ammo found there after
    // equipping gets relocated into the storage grid.
    private static final int STORAGE_START = 6;

    // remap=false: equipRandomGun is a SEM-internal method, never obfuscated.
    @Inject(method = "equipRandomGun", at = @At("TAIL"), remap = false)
    private static void semcs$moveAmmoToStorage(LivingEntity entity, RandomSource random,
                                                CallbackInfo ci) {
        entity.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler -> {
            if (!(handler instanceof IItemHandlerModifiable modifiable)) {
                return;
            }
            // Check offhand + armor slots for stray ammo stacks
            for (int slot = 1; slot < STORAGE_START && slot < modifiable.getSlots(); slot++) {
                ItemStack stack = modifiable.getStackInSlot(slot);
                if (stack.isEmpty() || IAmmo.getIAmmoOrNull(stack) == null) {
                    continue;   // empty or a real item (gun/armor) -> leave it
                }
                // Find the first free storage slot and move the ammo there
                for (int target = STORAGE_START; target < modifiable.getSlots(); target++) {
                    if (modifiable.getStackInSlot(target).isEmpty()) {
                        modifiable.setStackInSlot(target, stack.copy());
                        // Clear the equipment slot - this also un-equips the
                        // ammo from the offhand via onContentsChanged()
                        modifiable.setStackInSlot(slot, ItemStack.EMPTY);
                        break;
                    }
                }
            }
        });
    }
}
