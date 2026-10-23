// Entity.ai package - owned units scavenge useful drops off the ground
package com.poseidon.sem_classsystem.entity.ai;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.event.UnitMarkerTracker;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.api.item.IGun;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

// Post-battle cleanup: an owned, out-of-combat unit walks to the nearest
// worthwhile ItemEntity and stores it. Only useful loot qualifies - ammo a
// carried gun actually wants (same dry-calibre logic as the resupply goal),
// food, medical items, spare guns and armour, ammo boxes - so squads don't
// vacuum up dirt blocks and rotten junk. Storage slots only (index 6+);
// gear slots are untouched and a full inventory just leaves the drop.
public class UnitLootGoal extends Goal {

    private static final double SCAN_RANGE = 8.0D;
    private static final double REACH_SQR = 1.7D * 1.7D;
    private static final int RESCAN_TICKS = 25;
    private static final int FIRST_STORAGE_SLOT = 6;

    private final PmcUnitEntity unit;
    private ItemEntity target;
    private int scanCooldown = 0;

    public UnitLootGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!ClassSystemConfig.lootPickupEnabled()
                || this.unit.getOwnerUUID() == null
                || this.unit.getTarget() != null
                || this.unit.isSleeping()) {
            return false;
        }
        if (this.scanCooldown > 0) {
            this.scanCooldown--;
            return false;
        }
        this.scanCooldown = RESCAN_TICKS;
        this.target = findLoot();
        return this.target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.target != null
                && this.target.isAlive()
                && this.unit.getTarget() == null
                && this.unit.distanceToSqr(this.target) < SCAN_RANGE * SCAN_RANGE * 4;
    }

    @Override
    public void tick() {
        if (this.target == null) {
            return;
        }
        if (this.unit.distanceToSqr(this.target) > REACH_SQR) {
            if (this.unit.getNavigation().isDone()) {
                this.unit.getNavigation().moveTo(this.target, 1.0D);
            }
            return;
        }
        take(this.target);
        this.target = null;
    }

    @Override
    public void stop() {
        this.target = null;
    }

    private void take(ItemEntity drop) {
        IItemHandler inv = this.unit.getCapability(ForgeCapabilities.ITEM_HANDLER)
                .resolve().orElse(null);
        if (inv == null) {
            return;
        }
        ItemStack original = drop.getItem().copy();
        ItemStack stack = drop.getItem();
        for (int slot = FIRST_STORAGE_SLOT; slot < inv.getSlots() && !stack.isEmpty(); slot++) {
            stack = inv.insertItem(slot, stack, false);
        }
        int taken = original.getCount() - stack.getCount();
        if (taken <= 0) {
            return;   // no room at all - leave it on the ground
        }
        if (stack.isEmpty()) {
            drop.discard();
        } else {
            drop.setItem(stack);
        }
        this.unit.level().playSound(null, this.unit.blockPosition(),
                SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.4F, 1.2F);
        this.unit.getNavigation().stop();
        ItemStack takenStack = original.copy();
        takenStack.setCount(taken);
        pingOwner(takenStack);
    }

    // Salvage beacon: an actionbar ping so the commander knows the squad
    // grabbed something. Throttled per unit - a loot room shouldn't spam.
    private int lastPingTick = -10000;

    private void pingOwner(ItemStack stack) {
        if (this.unit.tickCount - this.lastPingTick < 200) {
            return;
        }
        this.lastPingTick = this.unit.tickCount;
        java.util.UUID owner = this.unit.getOwnerUUID();
        if (owner == null || this.unit.getServer() == null) {
            return;
        }
        net.minecraft.server.level.ServerPlayer player =
                this.unit.getServer().getPlayerList().getPlayer(owner);
        if (player != null) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    this.unit.getDisplayName().getString() + " salvaged "
                            + stack.getCount() + "x "
                            + stack.getHoverName().getString())
                    .withStyle(net.minecraft.ChatFormatting.YELLOW), true);
        }
    }

    private ItemEntity findLoot() {
        Set<ResourceLocation> wantedAmmo = StockpileSearch.dryAmmoIds(this.unit);
        List<ItemEntity> items = this.unit.level().getEntitiesOfClass(
                ItemEntity.class,
                this.unit.getBoundingBox().inflate(SCAN_RANGE),
                e -> e.isAlive() && !e.hasPickUpDelay()
                        && worthPickup(e.getItem(), wantedAmmo));
        return items.stream()
                .min(Comparator.comparingDouble(this.unit::distanceToSqr))
                .orElse(null);
    }

    private static boolean worthPickup(ItemStack stack, Set<ResourceLocation> wantedAmmo) {
        if (stack.isEmpty()) {
            return false;
        }
        IAmmo ammo = IAmmo.getIAmmoOrNull(stack);
        if (ammo != null) {
            return wantedAmmo.contains(ammo.getAmmoId(stack));
        }
        return stack.isEdible()
                || UnitMarkerTracker.isMedicalItem(stack)
                || stack.getItem() instanceof ArmorItem
                || stack.getItem() instanceof IAmmoBox
                || IGun.getIGunOrNull(stack) != null;
    }
}
