package com.poseidon.sem_classsystem.entity.ai;

import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandlerModifiable;

// Ammo auto-resupply: TaCZ already drains every slot of the unit's
// ITEM_HANDLER when reloading, so a dry magazine means the whole inventory
// is out of that calibre. This always-on scan notices "magazine empty AND
// no matching rounds anywhere" and swaps in the best alternative firearm
// the unit is carrying (the backup sidearm we issue at spawn, or any gun
// that still has ammo). When the player restocks the primary's ammo the
// next scan swaps back to the strongest armed gun automatically.
//
// For inventory-less enemy units (RU/US) the backup pistol lives in the
// "semcs_backup_sidearm" NBT written by MixinUnitAmmoReserve; the drained
// primary is parked in "semcs_stashed_primary" so it can be restored.
public class UnitWeaponFallbackGoal extends Goal {

    private static final String SIDEARM_NBT = "semcs_backup_sidearm";
    private static final String STASHED_NBT = "semcs_stashed_primary";

    private final PathfinderMob mob;
    private int scanCooldown = 0;

    public UnitWeaponFallbackGoal(PathfinderMob mob) {
        this.mob = mob;
        // No MOVE/LOOK flags: this only swaps items, never drives the body.
        this.setFlags(java.util.EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        return true;   // always-on lightweight scanner
    }

    @Override
    public void tick() {
        if (this.mob.level().isClientSide || !this.mob.isAlive() || this.mob.isUsingItem()) {
            return;
        }
        if (--this.scanCooldown > 0) {
            return;
        }
        this.scanCooldown = 20;

        ItemStack held = this.mob.getMainHandItem();
        IGun heldGun = IGun.getIGunOrNull(held);
        if (heldGun == null) {
            return;
        }

        boolean loaded = heldGun.getCurrentAmmoCount(held) > 0;
        boolean infinite = heldGun.useDummyAmmo(held) && heldGun.getDummyAmmoAmount(held) > 0;
        boolean reservable = heldGun.hasInventoryAmmo(this.mob, held, true);
        if (loaded || infinite || reservable) {
            return; // gun can still fire - nothing to do
        }

        swapToArmedWeapon(held);
    }

    private void swapToArmedWeapon(ItemStack dryGun) {
        // 1. Inventory units (PMC/BHQ): swap with the best gun in storage.
        var cap = this.mob.getCapability(ForgeCapabilities.ITEM_HANDLER);
        if (cap.isPresent() && cap.orElse(null) instanceof IItemHandlerModifiable handler) {
            int bestSlot = -1;
            int bestScore = -1;
            for (int slot = 6; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                IGun g = IGun.getIGunOrNull(stack);
                if (g == null) {
                    continue;
                }
                int score = g.getCurrentAmmoCount(stack)
                        + (g.hasInventoryAmmo(this.mob, stack, true) ? 1000 : 0)
                        + Math.min(g.getDummyAmmoAmount(stack), 999);
                if (score > bestScore && score > 0) {
                    bestScore = score;
                    bestSlot = slot;
                }
            }
            if (bestSlot >= 0) {
                ItemStack replacement = handler.getStackInSlot(bestSlot);
                handler.setStackInSlot(bestSlot, dryGun);
                handler.setStackInSlot(0, replacement);   // onContentsChanged -> draw()
                this.mob.setItemSlot(EquipmentSlot.MAINHAND, replacement);
                return;
            }
        }

        // 2. No inventory / no armed gun in storage: fall back to the NBT
        //    sidearm issued to RU/US units at spawn.
        CompoundTag data = this.mob.getPersistentData();
        ItemStack current = this.mob.getMainHandItem();
        if (data.contains(SIDEARM_NBT)) {
            ItemStack sidearm = ItemStack.of(data.getCompound(SIDEARM_NBT));
            IGun sg = IGun.getIGunOrNull(sidearm);
            if (!sidearm.isEmpty() && sg != null
                    && (sg.getCurrentAmmoCount(sidearm) > 0 || sg.getDummyAmmoAmount(sidearm) > 0)) {
                data.put(STASHED_NBT, current.save(new CompoundTag()));
                data.remove(SIDEARM_NBT);
                this.mob.setItemSlot(EquipmentSlot.MAINHAND, sidearm);
                IGunOperator.fromLivingEntity(this.mob).draw(() -> sidearm);
                return;
            }
        }
        // Sidearm also spent: restore the stashed primary so the unit keeps
        // its real gun (and can fire again if it somehow regains ammo).
        if (data.contains(STASHED_NBT)) {
            ItemStack primary = ItemStack.of(data.getCompound(STASHED_NBT));
            if (!primary.isEmpty()) {
                data.put(SIDEARM_NBT, current.save(new CompoundTag()));
                data.remove(STASHED_NBT);
                this.mob.setItemSlot(EquipmentSlot.MAINHAND, primary);
                IGunOperator.fromLivingEntity(this.mob).draw(() -> primary);
            }
        }
    }
}
