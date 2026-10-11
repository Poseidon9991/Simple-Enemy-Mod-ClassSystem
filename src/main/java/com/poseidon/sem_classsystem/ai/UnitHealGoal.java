// AI package - lets units consume medical items from their own inventory
package com.poseidon.sem_classsystem.ai;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandlerModifiable;

// SEM has no heal mechanic at all - a wounded unit just stays wounded.
// This goal lets a unit self-administer medical supplies it carries:
// when health drops below the configured fraction and the cooldown has
// elapsed, it finds an item tagged sem_classsystem:unit_healing in its
// storage slots (6+) and runs that item's own finishUsingItem() on itself.
// Survival Instinct medkits/bandages/injectors work unmodified because
// their procedures act on any LivingEntity, not only players.
//
// The goal holds NO flags and never moves the unit - the heal is a
// one-tick action inside start(), so it never fights the combat goals
// for control of MOVE/LOOK.
public class UnitHealGoal extends Goal {

    // Items the unit is allowed to consume. Ships with Survival Instinct
    // medkit/bandage/syringe ids; datapacks can add any consumable.
    public static final TagKey<Item> HEAL_TAG = TagKey.create(
            Registries.ITEM,
            new ResourceLocation(SemClassSystem.MODID, "unit_healing"));

    // SEM inventory layout: 0=main hand, 1=offhand, 2-5=armor, 6+=storage.
    private static final int STORAGE_START = 6;
    // Re-evaluate once per second, not every tick - inventory scans cost.
    private static final int SCAN_INTERVAL = 20;

    private final Mob unit;
    private int healCooldown;
    private int scanTimer;

    public UnitHealGoal(Mob unit) {
        this.unit = unit;
    }

    @Override
    public boolean canUse() {
        if (this.unit.level().isClientSide) {
            return false;
        }
        // Tick the cooldown only while the goal is being evaluated - one
        // decrement per evaluation tick, same cadence SEM's goals see.
        if (this.healCooldown > 0) {
            this.healCooldown--;
            return false;
        }
        if (--this.scanTimer > 0) {
            return false;
        }
        this.scanTimer = SCAN_INTERVAL;
        double threshold = this.unit.getMaxHealth() * ClassSystemConfig.healBelowFraction();
        return this.unit.getHealth() < threshold && findHealSlot() >= 0;
    }

    // Instant: the heal happens entirely inside start() - the goal ends on
    // the next evaluation.
    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        IItemHandlerModifiable inv = inventory();
        if (inv == null) {
            return;
        }
        int slot = findHealSlot(inv);
        if (slot < 0) {
            return;
        }
        ItemStack stack = inv.getStackInSlot(slot);
        // Run the item's own use logic on a ONE-ITEM COPY: the copy gets
        // the vanilla edible path (eat + effects) while SI's procedure
        // applies its heal/regen to the unit. We then shrink the real
        // stack ourselves - deterministic consumption no matter how the
        // item handles counts for non-players.
        ItemStack single = stack.copy();
        single.setCount(1);
        single.finishUsingItem(this.unit.level(), this.unit);
        stack.shrink(1);
        inv.setStackInSlot(slot, stack);
        this.unit.swing(InteractionHand.OFF_HAND);
        this.healCooldown = ClassSystemConfig.healCooldownTicks();
    }

    // First storage slot holding a tagged heal item, -1 when none.
    private int findHealSlot() {
        IItemHandlerModifiable inv = inventory();
        return inv == null ? -1 : findHealSlot(inv);
    }

    private int findHealSlot(IItemHandlerModifiable inv) {
        for (int slot = STORAGE_START; slot < inv.getSlots(); slot++) {
            if (inv.getStackInSlot(slot).is(HEAL_TAG)) {
                return slot;
            }
        }
        return -1;
    }

    // The unit's item-handler capability (SEM exposes its UnitInventoryHandler).
    @javax.annotation.Nullable
    private IItemHandlerModifiable inventory() {
        return this.unit.getCapability(ForgeCapabilities.ITEM_HANDLER)
                .map(handler -> handler instanceof IItemHandlerModifiable modifiable
                        ? modifiable : null)
                .orElse(null);
    }
}
