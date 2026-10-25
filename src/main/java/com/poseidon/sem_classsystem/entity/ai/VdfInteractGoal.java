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
    private static final int REPAIR_COOLDOWN_TICKS = 1200;              // min ticks between golem field-patches (60 s)
    private static final float GOLEM_REPAIR_AMOUNT = 4.0F;              // HP restored when patching up the village golem

    private final VdfUnitEntity unit;                                   // the militia unit this goal drives
    private LivingEntity greeted;                                       // the villager/golem we're greeting
    private int greetTicks;                                             // remaining greet duration
    private int cooldown;                                               // ticks until we may greet again
    private int repairCooldown;                                         // ticks until we may patch a golem again

    public VdfInteractGoal(VdfUnitEntity unit) {
        this.unit = unit;                                               // store the unit for all goal phases
        // MOVE+LOOK: without MOVE the patrol keeps strolling while this goal
        // locks the head on a villager - a sideways crab-walk that reads as
        // "walking weird". Claiming MOVE lets the greeting halt the stroll.
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.repairCooldown > 0) {
            this.repairCooldown--;
        }
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
        if (this.greeted instanceof IronGolem golem
                && golem.getHealth() < golem.getMaxHealth()
                && this.repairCooldown <= 0) {
            golem.heal(GOLEM_REPAIR_AMOUNT);                            // field-patch the village's iron defender
            this.repairCooldown = REPAIR_COOLDOWN_TICKS;
            this.unit.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            this.unit.level().playSound(null, golem.blockPosition(),
                    SoundEvents.IRON_GOLEM_REPAIR,
                    this.unit.getSoundSource(), 1.0F,
                    0.95F + this.unit.getRandom().nextFloat() * 0.1F);
            return;
        }
        this.unit.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
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
