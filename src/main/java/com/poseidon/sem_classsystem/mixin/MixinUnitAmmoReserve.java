// Mixin package - guarantees every class unit spawns with enough reserve ammo
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
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

// Makes sure EVERY class unit carries a real ammo reserve, not just whatever
// the equipper happened to leave:
//   - RU/US equippers set dummyAmmo = 9999 (infinite internal magazine) and
//     place no ammo item at all
//   - PMC/BHQ get ammoCount x 6 from SEM's equipper - usually 100-180 rounds,
//     under the amount we want them to carry
// This TAIL inject counts every matching-ammo stack already in the handler
// and tops the unit up to MIN_RESERVE_ROUNDS: PMC keeps its SEM stack and
// gains the deficit, RU/US get a fresh stack from zero.
// Slots 0-5 are never touched - ammo in offhand/armor renders wrong, so the
// top-up goes into the first free storage slot (6+).
@Mixin({RuWeaponEquipper.class, UsWeaponEquipper.class, PmcUnitWeaponEquipper.class})
public abstract class MixinUnitAmmoReserve {

    private static final int STORAGE_START = 6;
    private static final int MIN_RESERVE_ROUNDS = 254;

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
            // Total the rounds of THIS ammo already carried anywhere in the
            // handler (PMC's SEM-placed stack counts toward the target too).
            int carried = 0;
            for (int slot = 0; slot < modifiable.getSlots(); slot++) {
                ItemStack stack = modifiable.getStackInSlot(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                IAmmo ammo = IAmmo.getIAmmoOrNull(stack);
                if (ammo != null && ammoId.equals(ammo.getAmmoId(stack))) {
                    carried += stack.getCount();
                }
            }
            int deficit = MIN_RESERVE_ROUNDS - carried;
            if (deficit <= 0) {
                return;
            }
            // One stack can exceed maxStackSize here - SEM's own reserve logic
            // does the same (ammoCount x 6 into a single slot).
            ItemStack topUp = AmmoItemBuilder.create().setId(ammoId).setCount(deficit).build();
            for (int slot = STORAGE_START; slot < modifiable.getSlots(); slot++) {
                if (modifiable.getStackInSlot(slot).isEmpty()) {
                    modifiable.setStackInSlot(slot, topUp);
                    return;
                }
            }
        });
    }
}
