package com.poseidon.sem_classsystem.entity.ai;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.needs.UnitNeeds;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IGun;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

// Ammo logistics for owned units ([needs].ammoResupply). When a carried
// gun has no matching rounds left anywhere in the unit's pack, the unit
// walks to the nearest stockpile container inside the owner's claims and
// pulls matching ammunition - up to RESTOCK_ROUNDS per trip.
//
// Complements UnitWeaponFallbackGoal: that goal swaps to the sidearm the
// moment the primary runs dry; this one is what eventually brings the
// primary back. Out of combat only.
public class UnitResupplyGoal extends Goal {

    private static final int RESCAN_TICKS = 100;
    private static final int RESTOCK_ROUNDS = 96;
    private static final double REACH_SQR = 2.6 * 2.6;

    private final PmcUnitEntity unit;
    private int rescanTicks = 0;
    private BlockPos stockpile;
    private Set<ResourceLocation> dryAmmoIds = Set.of();

    public UnitResupplyGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.rescanTicks > 0) {
            this.rescanTicks--;
            return false;
        }
        this.rescanTicks = RESCAN_TICKS;
        if (!UnitNeeds.appliesTo(this.unit) || !ClassSystemConfig.ammoResupply()
                || !this.unit.isAlive() || this.unit.isSleeping()
                || this.unit.isUsingItem() || this.unit.getTarget() != null) {
            return false;
        }
        this.dryAmmoIds = findDryAmmoIds();
        if (this.dryAmmoIds.isEmpty()) {
            return false;
        }
        this.stockpile = StockpileSearch.findContainer(this.unit, this::acceptsAmmo);
        return this.stockpile != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.unit.isAlive() && this.unit.getTarget() == null
                && this.stockpile != null;
    }

    @Override
    public void start() {
        this.unit.getNavigation().moveTo(
                this.stockpile.getX() + 0.5, this.stockpile.getY(),
                this.stockpile.getZ() + 0.5, 1.0D);
    }

    @Override
    public void tick() {
        if (this.stockpile == null) {
            return;
        }
        if (this.unit.distanceToSqr(this.stockpile.getX() + 0.5,
                this.stockpile.getY() + 0.5, this.stockpile.getZ() + 0.5) <= REACH_SQR) {
            boolean got = StockpileSearch.withdrawIntoUnit(
                    this.unit, this.stockpile, this::acceptsAmmo, RESTOCK_ROUNDS);
            if (got) {
                // Zipper/mag foley - same language as the eat sounds.
                this.unit.playSound(SoundEvents.ARMOR_EQUIP_IRON, 0.7F, 1.2F);
            }
            this.stockpile = null;   // trip ends whether or not ammo was found
        } else if (this.unit.getNavigation().isDone()) {
            this.unit.getNavigation().moveTo(
                    this.stockpile.getX() + 0.5, this.stockpile.getY(),
                    this.stockpile.getZ() + 0.5, 1.0D);
        }
    }

    @Override
    public void stop() {
        this.stockpile = null;
        this.dryAmmoIds = Set.of();
        this.rescanTicks = RESCAN_TICKS;
    }

    private boolean acceptsAmmo(ItemStack stack) {
        IAmmo ammo = IAmmo.getIAmmoOrNull(stack);
        return ammo != null && this.dryAmmoIds.contains(ammo.getAmmoId(stack));
    }

    // Ammo ids of carried guns that have zero matching rounds - loose
    // stacks and the gun's own loaded magazine both count as supply.
    // Two passes so ammo listed before its gun still counts.
    private Set<ResourceLocation> findDryAmmoIds() {
        IItemHandler inv = this.unit.getCapability(ForgeCapabilities.ITEM_HANDLER)
                .resolve().orElse(null);
        if (inv == null) {
            return Set.of();
        }
        ResourceLocation[] ids = new ResourceLocation[8];
        int[] rounds = new int[8];
        int idCount = 0;
        // Pass 1: every carried gun claims its ammo id + loaded mag.
        for (int i = 0; i < inv.getSlots() && idCount < ids.length; i++) {
            ItemStack stack = inv.getStackInSlot(i);
            IGun gun = IGun.getIGunOrNull(stack);
            if (gun == null) {
                continue;
            }
            ResourceLocation gunId = gun.getGunId(stack);
            ResourceLocation ammoId = gunId == null ? null
                    : TimelessAPI.getCommonGunIndex(gunId)
                    .map(index -> index.getGunData().getAmmoId())
                    .orElse(null);
            if (ammoId == null) {
                continue;
            }
            int at = indexOf(ids, idCount, ammoId);
            if (at < 0) {
                ids[idCount] = ammoId;
                rounds[idCount] = gun.getCurrentAmmoCount(stack);
                idCount++;
            } else {
                rounds[at] += gun.getCurrentAmmoCount(stack);
            }
        }
        // Pass 2: loose rounds top up the matching gun's pool.
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            IAmmo ammo = IAmmo.getIAmmoOrNull(stack);
            if (ammo == null) {
                continue;
            }
            int at = indexOf(ids, idCount, ammo.getAmmoId(stack));
            if (at >= 0) {
                rounds[at] += stack.getCount();
            }
        }
        Set<ResourceLocation> wanted = new HashSet<>();
        for (int i = 0; i < idCount; i++) {
            if (rounds[i] <= 0) {
                wanted.add(ids[i]);
            }
        }
        return wanted;
    }

    private static int indexOf(ResourceLocation[] ids, int count, ResourceLocation id) {
        for (int i = 0; i < count; i++) {
            if (ids[i].equals(id)) {
                return i;
            }
        }
        return -1;
    }
}
