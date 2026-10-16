package com.poseidon.sem_classsystem.entity.ai;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.needs.UnitNeeds;
import com.tacz.guns.api.item.IAmmo;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.EnumSet;
import java.util.Set;

// Ammo logistics for owned units ([needs].ammoResupply). When a carried
// gun has no matching rounds left anywhere in the unit's pack, the unit
// walks to the nearest stockpile container inside the owner's claims and
// pulls matching ammunition - up to RESTOCK_ROUNDS per trip. A TaCZ ammo
// box worn as a curio is handled separately by UnitAmmoBoxGoal (no needs
// gating - the box is the unit's own gear).
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
        this.dryAmmoIds = StockpileSearch.dryAmmoIds(this.unit);
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
}
