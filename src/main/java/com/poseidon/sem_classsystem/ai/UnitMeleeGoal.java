// AI package - vanilla-style melee attack for units holding non-gun weapons
package com.poseidon.sem_classsystem.ai;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

// SEM's only attack path is RangedGunAttackGoal - a unit holding a
// machete or fire axe literally cannot swing it. This goal is the vanilla
// melee attack with one extra gate: it only runs while the main hand
// holds a non-gun item that actually adds attack damage (SwordItem,
// axes, Survival Instinct bats/maces/hammers - anything whose MAINHAND
// attribute modifiers include ATTACK_DAMAGE > 0).
// doHurtTarget reads the ATTACK_DAMAGE attribute, which already includes
// the held item's modifiers - so weapon damage, enchants and knockback
// all work without us computing anything.
public class UnitMeleeGoal extends MeleeAttackGoal {

    public UnitMeleeGoal(PathfinderMob mob) {
        // Slightly over base speed - a soldier presses the attack.
        super(mob, 1.15, true);
    }

    @Override
    public boolean canUse() {
        return ClassSystemConfig.meleeAttackEnabled()
                && WeaponClassSupport.isMeleeWeapon(this.mob.getMainHandItem())
                && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return ClassSystemConfig.meleeAttackEnabled()
                && WeaponClassSupport.isMeleeWeapon(this.mob.getMainHandItem())
                && super.canContinueToUse();
    }
}
