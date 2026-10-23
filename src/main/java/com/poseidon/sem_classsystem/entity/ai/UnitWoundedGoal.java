// Entity.ai package - badly hurt units break contact and fall back
package com.poseidon.sem_classsystem.entity.ai;

import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.EnumSet;
import java.util.List;

// Under 30% HP with a live target, an owned unit breaks contact for a few
// seconds: runs away from the threat - toward the nearest same-owner medic
// when one's close, else straight back - then re-engages (a cooldown stops
// it flickering in and out of retreat). Keep firing while moving: this is
// a fighting withdrawal, not a rout. Sleeping units never trigger.
public class UnitWoundedGoal extends Goal {

    private static final double TRIGGER_FRAC = 0.30D;
    private static final double MEDIC_RANGE_SQR = 24.0D * 24.0D;
    private static final int RETREAT_TICKS = 60;
    private static final int COOLDOWN_TICKS = 160;

    private final PmcUnitEntity unit;
    private int cooldown = 0;
    private int retreatTicks = 0;

    public UnitWoundedGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.cooldown > 0) {
            this.cooldown--;
            return false;
        }
        return this.unit.getOwnerUUID() != null
                && !this.unit.isSleeping()
                && this.unit.getTarget() != null
                && this.unit.getHealth() < this.unit.getMaxHealth() * TRIGGER_FRAC;
    }

    @Override
    public boolean canContinueToUse() {
        // Stop early when the threat dies or we're stable again (>50%).
        return this.retreatTicks > 0
                && this.unit.getTarget() != null
                && this.unit.getHealth() < this.unit.getMaxHealth() * 0.5D;
    }

    @Override
    public void start() {
        this.retreatTicks = RETREAT_TICKS;
    }

    @Override
    public void stop() {
        this.cooldown = COOLDOWN_TICKS;
    }

    @Override
    public void tick() {
        this.retreatTicks--;
        LivingEntity threat = this.unit.getTarget();
        if (threat == null) {
            return;
        }
        // Prefer falling back toward a medic - the goal names triage to us.
        PmcUnitEntity medic = nearestMedic();
        double tx, tz;
        if (medic != null) {
            tx = medic.getX();
            tz = medic.getZ();
        } else {
            double dx = this.unit.getX() - threat.getX();
            double dz = this.unit.getZ() - threat.getZ();
            double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
            tx = this.unit.getX() + dx / len * 10.0;
            tz = this.unit.getZ() + dz / len * 10.0;
        }
        if (this.unit.getNavigation().isDone()) {
            this.unit.getNavigation().moveTo(tx, this.unit.getY(), tz, 1.15D);
        }
    }

    private PmcUnitEntity nearestMedic() {
        List<PmcUnitEntity> allies = this.unit.level().getEntitiesOfClass(
                PmcUnitEntity.class,
                this.unit.getBoundingBox().inflate(24.0),
                e -> e != this.unit && e.isAlive()
                        && this.unit.getOwnerUUID().equals(e.getOwnerUUID()));
        PmcUnitEntity best = null;
        double bestD = MEDIC_RANGE_SQR;
        for (PmcUnitEntity ally : allies) {
            if (WeaponClassSupport.get(ally) != WeaponClass.MEDIC) {
                continue;
            }
            double d = this.unit.distanceToSqr(ally);
            if (d < bestD) {
                bestD = d;
                best = ally;
            }
        }
        return best;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
