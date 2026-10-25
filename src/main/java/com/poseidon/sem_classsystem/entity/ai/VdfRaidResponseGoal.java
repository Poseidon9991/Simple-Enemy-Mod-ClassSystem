// Entity.ai package - militia rallies to the bell when a raid hits
package com.poseidon.sem_classsystem.entity.ai;

import com.poseidon.sem_classsystem.entity.VdfUnitEntity;              // the militia unit this drives
import net.minecraft.core.BlockPos;                                    // raid + village center positions
import net.minecraft.server.level.ServerLevel;                         // getRaidAt lives on ServerLevel
import net.minecraft.sounds.SoundEvents;                               // the raid horn alarm
import net.minecraft.sounds.SoundSource;                               // ambient sound channel
import net.minecraft.world.entity.ai.goal.Goal;                        // vanilla goal base class
import net.minecraft.world.entity.raid.Raid;                           // the active raid object
import net.minecraft.world.entity.raid.Raider;                         // raid members to hunt
import net.minecraft.world.phys.AABB;                                  // raider scan box

import java.util.EnumSet;                                              // goal flag set (MOVE)
import java.util.List;                                                 // raider scan results

// Raid response: while a pillager raid is active at the village, the militia
// converges on the raid center and points its guns at every Raider in the
// bubble. Patrol yields while this runs (it outranks it), and normal combat
// goals still handle the actual shooting - this goal is the rally, not the
// trigger finger.
public class VdfRaidResponseGoal extends Goal {

    private static final int POLL_INTERVAL = 20;                        // ticks between raid checks (cheap: raids are rare)
    private static final double ENGAGE_RADIUS = 48.0D;                  // how far out raiders get engaged
    private static final double MOVE_SPEED = 1.0D;                      // rally is a jog, not a stroll

    private final VdfUnitEntity unit;                                   // the militia unit this goal drives
    private int pollCooldown = 0;                                       // ticks until the next raid poll
    private Raid activeRaid;                                            // the raid we're responding to (null when calm)
    private boolean hornPlayed;                                         // one horn per raid, not one per tick

    public VdfRaidResponseGoal(VdfUnitEntity unit) {
        this.unit = unit;                                               // store the unit for all goal phases
        this.setFlags(EnumSet.of(Flag.MOVE));                           // claims movement while rallying
    }

    @Override
    public boolean canUse() {
        if (--this.pollCooldown > 0) {
            return this.activeRaid != null;                             // already responding - keep going between polls
        }
        this.pollCooldown = POLL_INTERVAL;                              // reset the poll timer
        this.activeRaid = this.findRaid();                              // look for an active raid at our village
        if (this.activeRaid != null && !this.hornPlayed) {              // first sighting of this raid
            this.hornPlayed = true;                                     // mark the horn spent
            this.unit.level().playSound(null, this.unit.blockPosition(), // alarm: raid horn at the militia's post
                    SoundEvents.RAID_HORN.get(), SoundSource.HOSTILE, 2.0F, 1.0F);
        }
        return this.activeRaid != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.activeRaid == null || !this.activeRaid.isActive()) {   // raid over or won - stand down
            return false;
        }
        return true;                                                    // still raiding - keep rallying
    }

    @Override
    public void tick() {
        Raid raid = this.activeRaid;
        if (raid == null) {
            return;                                                     // defensive: goal only ticks while raiding
        }

        BlockPos center = raid.getCenter();                             // where the raid is actually anchored
        if (this.unit.getNavigation().isDone()) {                       // re-issue only when the last leg ended -
            this.unit.getNavigation().moveTo(                           // every-tick moveTo rebuilds the path and
                    center.getX() + 0.5, center.getY(),                 // makes the rally stutter-step
                            center.getZ() + 0.5, MOVE_SPEED);
        }

        if (this.unit.getTarget() instanceof Raider) {                  // already locked onto a raider
            return;                                                     // combat goals handle the rest
        }

        AABB box = new AABB(center).inflate(ENGAGE_RADIUS);             // raider hunt box around the raid center
        List<Raider> raiders = this.unit.level().getEntitiesOfClass(    // all raid members in range
                Raider.class, box, Raider::isAlive);
        if (!raiders.isEmpty()) {
            this.unit.setTarget(raiders.get(                            // aim at the closest raider
                    raiders.size() > 1
                            ? nearestIndex(raiders)
                            : 0));
        }
    }

    @Override
    public void stop() {
        this.activeRaid = null;                                         // drop the raid reference
        this.hornPlayed = false;                                        // re-arm the horn for the next raid
    }

    // The raid attached to our village - queried at the unit's position so a
    // militia that wandered still detects the raid it guards. ServerLevel only.
    private Raid findRaid() {
        if (!(this.unit.level() instanceof ServerLevel server)) {       // raids are a server-side concept
            return null;
        }
        BlockPos anchor = this.unit.getVillageCenter() != null          // prefer the assigned bell...
                ? this.unit.getVillageCenter()
                : this.unit.blockPosition();                            // ...else fall back to where we stand
        return server.getRaidAt(anchor);                                // null = no active raid at the village
    }

    private int nearestIndex(List<Raider> raiders) {                    // index of the raider nearest to the unit
        int best = 0;
        double bestD2 = Double.MAX_VALUE;
        for (int i = 0; i < raiders.size(); i++) {
            double d2 = raiders.get(i).distanceToSqr(this.unit);        // squared distance is enough to compare
            if (d2 < bestD2) {
                bestD2 = d2;
                best = i;
            }
        }
        return best;
    }
}
