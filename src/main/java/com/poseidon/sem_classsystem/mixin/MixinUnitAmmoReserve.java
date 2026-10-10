// Mixin package - guarantees every class unit spawns with a reserve ammo stack
package com.poseidon.sem_classsystem.mixin;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.nekoyuni.SimpleEnemyMod.entity.equipment.PmcUnitWeaponEquipper;
import net.nekoyuni.SimpleEnemyMod.entity.equipment.RuWeaponEquipper;
import net.nekoyuni.SimpleEnemyMod.entity.equipment.UsWeaponEquipper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Only SEM's PMC equipper hands out a reserve ammo stack (ammoCount x 6).
// RU/US equippers just set dummyAmmo = 9999 - infinite internal magazine,
// no ammo item in the inventory at all. After ANY equipper runs, this TAIL
// inject checks the handler for a stack matching the gun's ammo id and adds
// one into the first free storage slot (6+) when missing:
//   - RU/US units: gain the visible reserve stack they never had
//   - PMC/BHQ: skipped - PMC's equipper already inserted it
//     (MixinPmcAmmoPlacement moves it to storage; dedup check makes the
//     order between these two TAIL injects irrelevant).
// Slots 0-5 are never touched - ammo in offhand/armor renders wrong.
@Mixin({RuWeaponEquipper.class, UsWeaponEquipper.class, PmcUnitWeaponEquipper.class})
public abstract class MixinUnitAmmoReserve {

    private static final int STORAGE_START = 6;
    private static final int RESERVE_MAGS = 6;

    // remap=false: equipRandomGun is a SEM-internal method, never obfuscated.
    @Inject(method = "equipRandomGun", at = @At("TAIL"), remap = false)
    private static void semcs$ensureReserveAmmo(LivingEntity entity, RandomSource random,
                                                CallbackInfo ci) {
        ItemStack gun = entity.getMainHandItem();
        IGun iGun = IGun.getIGunOrNull(gun);
        if (iGun == null) {
            return;
        }
        ResourceLocation gunId = iGun.getGunId(gun);
        if (gunId == null) {
            return;
        }
        ResourceLocation ammoId = TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getGunData().getAmmoId())
                .orElse(null);
        if (ammoId == null) {
            return;
        }

        entity.getCapability(ForgeCapabilities.ITEM_HANDLER).ifPresent(handler -> {
            if (!(handler instanceof IItemHandlerModifiable modifiable)) {
                return;
            }
            // Already carrying a stack of this ammo -> nothing to add
            for (int slot = 0; slot < modifiable.getSlots(); slot++) {
                ItemStack stack = modifiable.getStackInSlot(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                IAmmo ammo = IAmmo.getIAmmoOrNull(stack);
                if (ammo != null && ammoId.equals(ammo.getAmmoId(stack))) {
                    return;
                }
            }
            // Reserve sized like SEM's own rule: six magazines' worth
            int count = Math.max(1, iGun.getCurrentAmmoCount(gun)) * RESERVE_MAGS;
            ItemStack reserve = AmmoItemBuilder.create().setId(ammoId).setCount(count).build();
            for (int slot = STORAGE_START; slot < modifiable.getSlots(); slot++) {
                if (modifiable.getStackInSlot(slot).isEmpty()) {
                    modifiable.setStackInSlot(slot, reserve);
                    return;
                }
            }
        });
    }
}
