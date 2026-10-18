// Entity.ai package - militia greets the villagers it protects
package com.poseidon.sem_classsystem.entity.ai;

import com.poseidon.sem_classsystem.entity.VdfUnitEntity;              // the militia unit this drives
import net.minecraft.sounds.SoundEvents;                               // villager hum
import net.minecraft.world.entity.LivingEntity;                        // the villager/golem being greeted
import net.minecraft.world.entity.ai.goal.Goal;                        // vanilla goal base class
import net.minecraft.world.entity.animal.IronGolem;                    // village golem
import net.minecraft.world.entity.npc.AbstractVillager;                // villager + wandering trader base

import java.util.EnumSet;                                              // goal flag set (LOOK)

// Flavor beat: an idle militia member that finds a villager or golem at arm's
// length stops, faces it, and hums a greeting before moving on. Purely
// cosmetic - LOOK flag only, yields to any combat or movement goal.
public class VdfInteractGoal extends Goal {

    private static final double GREET_RANGE_SQR = 3.5D * 3.5D;          // must be right next to them to chat
    private static final int GREET_TICKS = 50;                          // how long the greeting pause lasts
    private static final int COOLDOWN_TICKS = 400;                      // min ticks between greetings (~20 s)

    private final VdfUnitEntity unit;                                   // the militia unit this goal drives
    private LivingEntity greeted;                                       // the villager/golem we're greeting
    private int greetTicks;                                             // remaining greet duration
    private int cooldown;                                               // ticks until we may greet again

    public VdfInteractGoal(VdfUnitEntity unit) {
        this.unit = unit;                                               // store the unit for all goal phases
        this.setFlags(EnumSet.of(Flag.LOOK));                           // only borrows the head - walk legs keep going
    }

    @Override
    public boolean canUse() {
        if (this.cooldown > 0) {                                        // greeted recently - leave them be
            this.cooldown--;
            return false;
        }
        if (this.unit.getTarget() != null                               // busy fighting
                || this.unit.tickCount % 40 != 0) {                     // cheap gate: check twice a second
            return false;
        }
        this.greeted = this.findGreetTarget();                          // villager or golem close enough to hail
        return this.greeted != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.greeted != null                                     // still has someone to greet
                && this.greeted.isAlive()                               // ...who is still alive
                && this.unit.getTarget() == null                        // ...and no combat broke out
                && this.greetTicks > 0;                                 // ...while the greeting runs
    }

    @Override
    public void start() {
        this.greetTicks = GREET_TICKS;                                  // hold the pose for a couple of seconds
        this.unit.level().playSound(null, this.unit.blockPosition(),    // the signature villager "hmm"
                SoundEvents.VILLAGER_YES,
                this.unit.getSoundSource(), 1.0F,
                0.9F + this.unit.getRandom().nextFloat() * 0.2F);       // slight pitch variety per greeting
    }

    @Override
    public void tick() {
        if (this.greeted != null) {
            this.unit.getLookControl().setLookAt(this.greeted);         // face the neighbor while greeting
        }
        this.greetTicks--;                                              // run the greeting clock down
    }

    @Override
    public void stop() {
        this.greeted = null;                                            // clear the greet partner
        this.cooldown = COOLDOWN_TICKS;                                 // don't hum at the next villager immediately
    }

    // Nearest villager or golem inside arm's reach - the two neighbors a
    // militia would actually stop for.
    private LivingEntity findGreetTarget() {
        LivingEntity villager = this.unit.level().getNearestEntity(     // closest villager in range
                AbstractVillager.class,
                net.minecraft.world.entity.ai.targeting.TargetingConditions.DEFAULT,
                this.unit,
                this.unit.getX(), this.unit.getY(), this.unit.getZ(),
                this.unit.getBoundingBox().inflate(3.5D));
        if (villager != null && villager.distanceToSqr(this.unit) <= GREET_RANGE_SQR) {
            return villager;
        }
        LivingEntity golem = this.unit.level().getNearestEntity(        // else the village golem
                IronGolem.class,
                net.minecraft.world.entity.ai.targeting.TargetingConditions.DEFAULT,
                this.unit,
                this.unit.getX(), this.unit.getY(), this.unit.getZ(),
                this.unit.getBoundingBox().inflate(3.5D));
        return golem != null && golem.distanceToSqr(this.unit) <= GREET_RANGE_SQR ? golem : null;
    }
}
