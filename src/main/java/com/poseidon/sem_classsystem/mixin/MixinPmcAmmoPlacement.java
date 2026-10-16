// Mixin package - keeps PMC reserve ammo out of the equipment slots and splits oversized stacks
package com.poseidon.sem_classsystem.mixin;

import com.tacz.guns.api.item.IAmmo;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.nekoyuni.SimpleEnemyMod.entity.equipment.PmcUnitWeaponEquipper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1. Moves any ammo placed by SEM into slots 1-5 (offhand + armor) into storage slots (6-17).
// 2. Splits any oversized ammo stack (e.g. SEM's 30x6 = 180 rounds) into legal <= 60 stacks.
//    In Minecraft 1.20.1, FriendlyByteBuf.writeItem and ItemStack.save write stack count as a
//    signed byte (-128..127), so a single stack of 180 or 254 overflows to -76 / -2 and turns
//    into ItemStack.EMPTY on client GUI sync and world save!
@Mixin(PmcUnitWeaponEquipper.class)
public abstract class MixinPmcAmmoPlacement {

    private static final int STORAGE_START = 6;
    private static final int MAX_SAFE_AMMO_STACK = 60;

    @Inject(method = "equipRandomGun", at = @At("TAIL"), remap = false)
    private static void semcs$moveAmmoToStorage(LivingEntity entity, RandomSource random,
                                                CallbackInfo ci) {
        entity.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler -> {
            if (!(handler instanceof IItemHandlerModifiable modifiable)) {
                return;
            }
            // Sweep offhand + armor slots (1..5) for stray ammo stacks
            for (int slot = 1; slot < STORAGE_START && slot < modifiable.getSlots(); slot++) {
                ItemStack stack = modifiable.getStackInSlot(slot);
                if (stack.isEmpty() || IAmmo.getIAmmoOrNull(stack) == null) {
                    continue;
                }
                int totalRounds = stack.getCount();
                ItemStack template = stack.copy();
                modifiable.setStackInSlot(slot, ItemStack.EMPTY);
                if (slot == 1) {
                    entity.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                }
                semcs$distributeAmmoIntoStorage(modifiable, template, totalRounds);
            }

            // Also normalize any oversized ammo stacks already in storage (6..17)
            for (int slot = STORAGE_START; slot < modifiable.getSlots(); slot++) {
                ItemStack stack = modifiable.getStackInSlot(slot);
                if (!stack.isEmpty() && IAmmo.getIAmmoOrNull(stack) != null && stack.getCount() > MAX_SAFE_AMMO_STACK) {
                    int extra = stack.getCount() - MAX_SAFE_AMMO_STACK;
                    ItemStack template = stack.copy();
                    stack.setCount(MAX_SAFE_AMMO_STACK);
                    modifiable.setStackInSlot(slot, stack);
                    semcs$distributeAmmoIntoStorage(modifiable, template, extra);
                }
            }
        });
    }

    @Unique
    private static void semcs$distributeAmmoIntoStorage(IItemHandlerModifiable modifiable,
                                                        ItemStack template, int roundsToPlace) {
        int remaining = roundsToPlace;
        for (int target = STORAGE_START; target < modifiable.getSlots() && remaining > 0; target++) {
            ItemStack existing = modifiable.getStackInSlot(target);
            if (existing.isEmpty()) {
                int chunk = Math.min(MAX_SAFE_AMMO_STACK, remaining);
                ItemStack piece = template.copy();
                piece.setCount(chunk);
                modifiable.setStackInSlot(target, piece);
                remaining -= chunk;
            }
        }
    }
}
