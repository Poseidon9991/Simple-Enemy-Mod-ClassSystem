// AI package - runtime diagnostics for the unit item features
package com.poseidon.sem_classsystem.ai;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.faction.UnitClass;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

// Server-side state the goal mixin records, plus the report builder behind
// /semcs debug. Tracks which units actually received our injected goals so
// the command can prove goal registration happened (or didn't).
public final class UnitDebugSupport {

    // Populated by MixinAbstractUnitGoals when it injects the melee + heal
    // goals. Weak so unloaded entities drop out on their own.
    public static final Set<Mob> GOAL_UNITS =
            Collections.newSetFromMap(new WeakHashMap<>());

    private UnitDebugSupport() {
    }

    // One diagnostic report for a unit - every line answers "would the melee
    // goal / heal goal fire for this unit right now?"
    public static List<String> report(AbstractUnit unit) {
        List<String> out = new ArrayList<>();
        out.add("== " + unit.getType().toShortString() + " ==");
        out.add("goals injected: " + GOAL_UNITS.contains(unit));

        WeaponClass assigned = WeaponClassSupport.get(unit);
        WeaponClass effective = WeaponClassSupport.getEffective(unit);
        out.add("class: " + assigned + " | effective: " + effective
                + " | faction: " + UnitClass.of(unit));

        ItemStack hand = unit.getMainHandItem();
        out.add("main hand: " + hand);
        out.add("melee weapon: " + WeaponClassSupport.isMeleeWeapon(hand)
                + " | meleeAttackEnabled: " + ClassSystemConfig.meleeAttackEnabled()
                + " | target: " + unit.getTarget());

        // Heal: mirror of UnitHealGoal's own scan, slot by slot
        int healSlot = -1;
        String healItem = "-";
        var handler = unit.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        if (handler instanceof IItemHandlerModifiable inv) {
            for (int slot = 6; slot < inv.getSlots(); slot++) {
                ItemStack s = inv.getStackInSlot(slot);
                if (s.is(UnitHealGoal.HEAL_TAG)) {
                    healSlot = slot;
                    healItem = s.toString();
                    break;
                }
            }
            out.add("inventory: yes (" + inv.getSlots() + " slots)");
        } else {
            out.add("inventory: NO CAPABILITY");
        }
        out.add("heal item: slot " + healSlot + " " + healItem);
        out.add(String.format("health: %.1f/%.1f (heal below %.0f%%, cooldown %d ticks)",
                unit.getHealth(), unit.getMaxHealth(),
                ClassSystemConfig.healBelowFraction() * 100.0,
                ClassSystemConfig.healCooldownTicks()));
        return out;
    }
}
