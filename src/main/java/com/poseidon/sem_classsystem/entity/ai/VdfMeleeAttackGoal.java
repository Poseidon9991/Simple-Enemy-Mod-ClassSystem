// AI package - VDF militia combat behaviours
package com.poseidon.sem_classsystem.entity.ai;

import com.poseidon.sem_classsystem.entity.VdfUnitEntity;
import com.tacz.guns.api.item.IGun;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

// Vanilla melee goal gated on "no gun in hand" - melee militia swing their
// blades while gunners handle their own ranged goal. Without the gate a
// gunner would try to close to melee range while also shooting.
public class VdfMeleeAttackGoal extends MeleeAttackGoal {

    private final VdfUnitEntity unit;

    public VdfMeleeAttackGoal(VdfUnitEntity unit, double speedModifier, boolean followUnseen) {
        super(unit, speedModifier, followUnseen);
        this.unit = unit;
    }

    @Override
    public boolean canUse() {
        return IGun.getIGunOrNull(this.unit.getMainHandItem()) == null && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return IGun.getIGunOrNull(this.unit.getMainHandItem()) == null && super.canContinueToUse();
    }
}
