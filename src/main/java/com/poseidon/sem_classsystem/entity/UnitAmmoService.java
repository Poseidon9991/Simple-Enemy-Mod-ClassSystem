// Shared unit-ammo helpers - called from mixins so the logic stays a plain
// class (no @Unique weaving headaches when referenced from injected code).
package com.poseidon.sem_classsystem.entity;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.AmmoItemBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

public final class UnitAmmoService {

    private static final Logger LOG = LogUtils.getLogger();

    // SEM unit inventory layout: 0 = gun, 1 = offhand, 2-5 = armor, 6-17 = storage.
    private static final int STORAGE_START = 6;
    public static final int MIN_RESERVE_ROUNDS = 240;
    // FriendlyByteBuf/ItemStack.save write stack count as a signed byte, so
    // >127 overflows negative -> ItemStack.EMPTY on GUI sync AND world save.
    public static final int MAX_SAFE_STACK = 60;

    private UnitAmmoService() {
    }

    // The unit's equipped gun can live in two places: the synced MAINHAND
    // equipment slot, or handler slot 0 (SEM writes the gun there directly).
    // Check both so the ammo pass never silently skips. Also relocates stray
    // ammo out of equipment slots and tops the unit up to MIN_RESERVE_ROUNDS
    // of the gun's own ammo type - idempotent (only fills the deficit), so it
    // is safe to run on both fresh spawn (equipRandomGun TAIL) and world load
    // (readAdditionalSaveData TAIL) to repair units that spawned ammo-less.
    public static void ensurePrimaryAmmo(LivingEntity entity) {
        ItemStack gun = entity.getMainHandItem();
        IGun iGun = IGun.getIGunOrNull(gun);
        IItemHandler raw = entity.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        IItemHandlerModifiable modifiable = raw instanceof IItemHandlerModifiable m ? m : null;
        if (iGun == null && modifiable != null && modifiable.getSlots() > 0) {
            gun = modifiable.getStackInSlot(0);
            iGun = IGun.getIGunOrNull(gun);
        }
        if (iGun == null) {
            return;
        }
        if (modifiable == null) {
            return;
        }
        ResourceLocation gunId = iGun.getGunId(gun);
        ResourceLocation ammoId = gunId == null ? null : TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getGunData() == null ? null : index.getGunData().getAmmoId())
                .orElse(null);
        if (ammoId == null) {
            return;
        }

        // Relocate any stray ammo from slots 1..5 (offhand + armor) into storage
        for (int slot = 1; slot < STORAGE_START && slot < modifiable.getSlots(); slot++) {
            ItemStack s = modifiable.getStackInSlot(slot);
            if (!s.isEmpty() && IAmmo.getIAmmoOrNull(s) != null) {
                int cnt = s.getCount();
                ItemStack tmpl = s.copy();
                modifiable.setStackInSlot(slot, ItemStack.EMPTY);
                if (slot == 1) {
                    entity.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                }
                placeSplitAmmo(modifiable, tmpl, cnt);
            }
        }

        // Normalize any existing oversized stacks (> 60) and count total carried
        int carried = 0;
        for (int slot = STORAGE_START; slot < modifiable.getSlots(); slot++) {
            ItemStack stack = modifiable.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            IAmmo ammo = IAmmo.getIAmmoOrNull(stack);
            if (ammo != null && ammoId.equals(ammo.getAmmoId(stack))) {
                if (stack.getCount() > MAX_SAFE_STACK) {
                    int extra = stack.getCount() - MAX_SAFE_STACK;
                    ItemStack tmpl = stack.copy();
                    stack.setCount(MAX_SAFE_STACK);
                    modifiable.setStackInSlot(slot, stack);
                    placeSplitAmmo(modifiable, tmpl, extra);
                }
                carried += modifiable.getStackInSlot(slot).getCount();
            }
        }

        int deficit = MIN_RESERVE_ROUNDS - carried;
        if (deficit > 0) {
            ItemStack tmpl = AmmoItemBuilder.create().setId(ammoId).setCount(1).build();
            placeSplitAmmo(modifiable, tmpl, deficit);
        }
    }

    // Splits totalRounds into <= MAX_SAFE_STACK pieces: first topping up
    // partially-filled matching stacks in storage, then filling empty slots.
    public static void placeSplitAmmo(IItemHandlerModifiable modifiable, ItemStack template, int totalRounds) {
        int remaining = totalRounds;
        IAmmo tmplAmmo = IAmmo.getIAmmoOrNull(template);
        ResourceLocation tmplId = tmplAmmo != null ? tmplAmmo.getAmmoId(template) : null;
        if (tmplId != null) {
            for (int slot = STORAGE_START; slot < modifiable.getSlots() && remaining > 0; slot++) {
                ItemStack cur = modifiable.getStackInSlot(slot);
                IAmmo curAmmo = IAmmo.getIAmmoOrNull(cur);
                if (!cur.isEmpty() && curAmmo != null && tmplId.equals(curAmmo.getAmmoId(cur))
                        && cur.getCount() < MAX_SAFE_STACK) {
                    int add = Math.min(MAX_SAFE_STACK - cur.getCount(), remaining);
                    cur.grow(add);
                    modifiable.setStackInSlot(slot, cur);
                    remaining -= add;
                }
            }
        }
        for (int slot = STORAGE_START; slot < modifiable.getSlots() && remaining > 0; slot++) {
            if (modifiable.getStackInSlot(slot).isEmpty()) {
                int chunk = Math.min(MAX_SAFE_STACK, remaining);
                ItemStack stack = template.copy();
                stack.setCount(chunk);
                modifiable.setStackInSlot(slot, stack);
                remaining -= chunk;
            }
        }
    }
}
