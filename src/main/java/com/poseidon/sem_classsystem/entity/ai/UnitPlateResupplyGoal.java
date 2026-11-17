package com.poseidon.sem_classsystem.entity.ai;

import com.poseidon.sem_classsystem.block.ArmorerBenchBlockEntity;
import com.poseidon.sem_classsystem.event.ArmorPlateCombatEvents;
import com.poseidon.sem_classsystem.item.ArmorPlateItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.EnumSet;
import java.util.List;

// Out-of-combat Auto Plate Resupply:
// Owned PMC/BHQ units wearing a BhqArmorItem vest or helmet with an empty
// plate slot (or critically cracked <15% plate) walk to the nearest stocked
// Armorer Bench inside the owner's claimed chunks and slot a fresh plate.
public class UnitPlateResupplyGoal extends Goal {

    private static final int RESCAN_TICKS = 100;
    private static final double REACH_SQR = 2.8D * 2.8D;

    private final PmcUnitEntity unit;
    private int rescanTicks = 0;
    private BlockPos benchPos;

    public UnitPlateResupplyGoal(PmcUnitEntity unit) {
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
        if (this.unit.getOwnerUUID() == null || !this.unit.isAlive()
                || this.unit.isSleeping() || this.unit.isUsingItem()
                || this.unit.getTarget() != null) {
            return false;
        }
        EquipmentSlot neededSlot = slotNeedingPlate();
        if (neededSlot == null) {
            return false;
        }
        ItemStack armor = this.unit.getItemBySlot(neededSlot);
        this.benchPos = StockpileSearch.findBlockEntity(this.unit, ArmorerBenchBlockEntity.class,
                bench -> bench.hasCompatiblePlate(armor));
        return this.benchPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.unit.isAlive() && this.unit.getTarget() == null
                && !this.unit.isSleeping() && this.benchPos != null;
    }

    @Override
    public void start() {
        if (this.benchPos != null) {
            this.unit.getNavigation().moveTo(
                    this.benchPos.getX() + 0.5, this.benchPos.getY(),
                    this.benchPos.getZ() + 0.5, 1.0D);
        }
    }

    @Override
    public void tick() {
        if (this.benchPos == null || !(this.unit.level() instanceof ServerLevel level)) {
            return;
        }
        if (this.unit.distanceToSqr(this.benchPos.getX() + 0.5,
                this.benchPos.getY() + 0.5, this.benchPos.getZ() + 0.5) <= REACH_SQR) {
            if (level.getBlockEntity(this.benchPos) instanceof ArmorerBenchBlockEntity bench) {
                if (resupplyFrom(bench)) {
                    this.unit.playSound(SoundEvents.ARMOR_EQUIP_IRON, 0.85F, 1.15F);
                }
            }
            this.benchPos = null;
        } else if (this.unit.getNavigation().isDone()) {
            this.unit.getNavigation().moveTo(
                    this.benchPos.getX() + 0.5, this.benchPos.getY(),
                    this.benchPos.getZ() + 0.5, 1.0D);
        }
    }

    @Override
    public void stop() {
        this.benchPos = null;
        this.rescanTicks = RESCAN_TICKS;
    }

    private EquipmentSlot slotNeedingPlate() {
        if (armorNeedsPlate(this.unit.getItemBySlot(EquipmentSlot.CHEST))) {
            return EquipmentSlot.CHEST;
        }
        if (armorNeedsPlate(this.unit.getItemBySlot(EquipmentSlot.HEAD))) {
            return EquipmentSlot.HEAD;
        }
        return null;
    }

    private static boolean armorNeedsPlate(ItemStack armor) {
        int max = ArmorPlateItem.maxPlateSlots(armor);
        if (max <= 0) {
            return false;
        }
        List<ItemStack> plates = ArmorPlateItem.getPlates(armor);
        for (int i = 0; i < max; i++) {
            ItemStack p = i < plates.size() ? plates.get(i) : ItemStack.EMPTY;
            if (p.isEmpty()) {
                return true;
            }
            int rem = p.getMaxDamage() - p.getDamageValue();
            if (rem <= Math.max(10, p.getMaxDamage() / 7)) {
                return true;
            }
        }
        return false;
    }

    private boolean resupplyFrom(ArmorerBenchBlockEntity bench) {
        boolean changed = false;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.CHEST, EquipmentSlot.HEAD}) {
            ItemStack armor = this.unit.getItemBySlot(slot);
            if (!armorNeedsPlate(armor)) {
                continue;
            }
            ItemStack fresh = bench.pullBestCompatiblePlate(armor);
            if (fresh.isEmpty()) {
                continue;
            }
            ItemStack swapped = ArmorPlateItem.insertOrReplacePlate(armor, fresh);
            if (swapped != null && !swapped.isEmpty()) {
                bench.depositOldPlate(swapped);
            }
            int handlerSlot = (slot == EquipmentSlot.HEAD) ? 5 : 4;
            ArmorPlateCombatEvents.syncUnitArmorSlot(this.unit, handlerSlot, armor);
            changed = true;
        }
        return changed;
    }
}
