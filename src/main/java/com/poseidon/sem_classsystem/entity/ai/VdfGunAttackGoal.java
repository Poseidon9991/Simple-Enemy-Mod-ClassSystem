// AI package - VDF militia combat behaviours
package com.poseidon.sem_classsystem.entity.ai;

import com.poseidon.sem_classsystem.entity.VdfUnitEntity;
import com.poseidon.sem_classsystem.faction.UnitClassAttributes;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

// Standalone gun-attack goal for the independent VDF entity - replaces the
// slice of SEM's role-package combat (RangedGunAttackGoal) the militia used.
//
// Behaviour, in SEM's spirit but small:
//   - advance on the target until inside STOP range, then stand and fire
//   - hold aim while engaged; TaCZ's own cooldown gates the real fire rate
//     so we call shoot() every ready tick and let the gun decide
//   - reloads from the dummy-ammo reserve the militia gun was built with
//   - spread comes from the same UnitClassAttributes.spreadMultiplier()
//     every other shooter uses, so [vdf_unit] spread config + veterancy
//     accuracy still apply
public class VdfGunAttackGoal extends Goal {

    // Stand-and-shoot once this close; keeps militia out of the melee scrum.
    private static final double STOP_RANGE_SQR = 15.0D * 15.0D;
    // Ticks of continuous line-of-sight required before the first shot -
    // models the "shoulder the rifle" moment so contact isn't instant death.
    private static final int AIM_SETTLE_TICKS = 8;

    private final VdfUnitEntity unit;
    private final double speedModifier;
    private int seeTime;

    public VdfGunAttackGoal(VdfUnitEntity unit, double speedModifier) {
        this.unit = unit;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean holdsGun() {
        return IGun.getIGunOrNull(this.unit.getMainHandItem()) != null;
    }

    @Override
    public boolean canUse() {
        LivingEntity target = this.unit.getTarget();
        return target != null && target.isAlive() && this.holdsGun();
    }

    @Override
    public boolean canContinueToUse() {
        return this.canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.seeTime = 0;
        this.unit.setAggressive(true);
        // Draw the weapon first - TaCZ's draw cooldown blocks early shots
        // on its own, matching SEM's "shoulder arms" delay.
        IGunOperator.fromLivingEntity(this.unit).draw(this.unit::getMainHandItem);
    }

    @Override
    public void stop() {
        this.unit.setAggressive(false);
        IGunOperator.fromLivingEntity(this.unit).aim(false);
        this.unit.getNavigation().stop();
    }

    @Override
    public void tick() {
        LivingEntity target = this.unit.getTarget();
        if (target == null) {
            return;
        }
        double distSqr = this.unit.distanceToSqr(target);
        boolean canSee = this.unit.getSensing().hasLineOfSight(target);
        this.seeTime = canSee ? this.seeTime + 1 : 0;

        // Close in while far or blind; hold position inside the stop bubble.
        if (distSqr > STOP_RANGE_SQR || !canSee) {
            this.unit.getNavigation().moveTo(target, this.speedModifier);
        } else {
            this.unit.getNavigation().stop();
        }
        this.unit.getLookControl().setLookAt(target, 30.0F, 30.0F);

        IGunOperator operator = IGunOperator.fromLivingEntity(this.unit);
        operator.aim(true);

        net.minecraft.world.item.ItemStack gunStack = this.unit.getMainHandItem();
        IGun iGun = IGun.getIGunOrNull(gunStack);
        if (iGun != null && iGun.getCurrentAmmoCount(gunStack) <= 0) {
            operator.reload();                                       // dummy-ammo reserve refills
            return;
        }
        if (this.seeTime < AIM_SETTLE_TICKS) {
            return;                                                  // still shouldering
        }

        // Aim at the target's eye line, then add per-shot scatter scaled by
        // the shared spread multiplier (faction config x veterancy x needs).
        double dx = target.getX() - this.unit.getX();
        double dz = target.getZ() - this.unit.getZ();
        double dy = target.getEyeY() - this.unit.getEyeY();
        double flat = Math.sqrt(dx * dx + dz * dz);
        float spread = (float) (2.0D * UnitClassAttributes.spreadMultiplier(this.unit));
        final float pitch = (float) (-Mth.atan2(dy, flat) * Mth.RAD_TO_DEG)
                + (this.unit.getRandom().nextFloat() - 0.5F) * spread;
        final float yaw = (float) (-Mth.atan2(dx, dz) * Mth.RAD_TO_DEG)
                + (this.unit.getRandom().nextFloat() - 0.5F) * spread;
        operator.shoot(() -> pitch, () -> yaw);
    }
}
