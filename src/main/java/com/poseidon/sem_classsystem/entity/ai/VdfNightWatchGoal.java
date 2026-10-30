// Entity.ai package - militia holds watch posts by night, shelters from rain
package com.poseidon.sem_classsystem.entity.ai;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;          // [vdf] nightWatch toggle + patrolRadius
import com.poseidon.sem_classsystem.entity.VdfUnitEntity;              // the militia unit this drives
import net.minecraft.core.BlockPos;                                    // watch post + village center positions
import net.minecraft.server.level.ServerLevel;                         // PoiManager lives server-side
import net.minecraft.world.entity.ai.goal.Goal;                        // vanilla goal base class
import net.minecraft.world.entity.ai.util.DefaultRandomPos;            // random reachable positions
import net.minecraft.world.entity.ai.village.poi.PoiManager;           // village POI index
import net.minecraft.world.entity.ai.village.poi.PoiTypes;             // HOME poi key (villager beds)
import net.minecraft.world.level.pathfinder.Path;                      // nav result (null check for reachability)
import net.minecraft.world.phys.Vec3;                                  // position vectors for toward-picks

import java.util.EnumSet;                                              // goal flag set (MOVE)

// Night watch: after dark (or while caught in the rain) the militia stops
// free-patrolling and takes posts near the village's beds, holding each post
// for a stretch and dropping into short crouch scans. Rain overrides this to
// hunt any roofed spot inside the leash. Outranks VdfPatrolGoal (3 < 4) but
// still yields to raid response and every combat goal.
public class VdfNightWatchGoal extends Goal {

    private static final int HOLD_MIN = 100;                            // base ticks spent at one watch post
    private static final int HOLD_JITTER = 120;                         // +0..120 so posts aren't synchronized
    private static final int REPATH = 15;                               // retry cadence when nothing reachable
    private static final int CROUCH_MIN = 60;                           // a crouch scan lasts 3-7 seconds
    private static final int CROUCH_JITTER = 80;
    private static final int CROUCH_GAP = 160;                          // stand tall a while between scans
    private static final double WALK_SPEED = 0.6D;                      // watch rounds stay unhurried
    private static final int SHELTER_TRIES = 8;                         // random roofed-spot attempts per repick

    private final VdfUnitEntity unit;                                   // the militia unit this goal drives
    private int holdTicks = 0;                                          // ticks left standing the current post
    private int crouchTicks = 0;                                        // ticks left in the current crouch scan
    private int crouchCooldown = 0;                                     // ticks until the next crouch scan

    public VdfNightWatchGoal(VdfUnitEntity unit) {
        this.unit = unit;                                               // store the unit for all goal phases
        this.setFlags(EnumSet.of(Flag.MOVE));                           // claims movement to outrank patrol
    }

    @Override
    public boolean canUse() {
        if (!ClassSystemConfig.vdfNightWatch()                          // feature disabled in [vdf]
                || this.unit.getTarget() != null                        // fighting - combat goals own movement
                || this.unit.getVillageCenter() == null                 // unassigned militia has no watch
                || this.unit.tickCount % 10 != 0) {                     // cheap gate: evaluate twice a second
            return false;
        }
        return this.isNight() || this.isExposedToRain();                // watch starts at dusk or in the open rain
    }

    @Override
    public boolean canContinueToUse() {
        if (this.unit.getTarget() != null || this.unit.getVillageCenter() == null) {
            return false;                                               // combat or lost anchor ends the watch
        }
        return this.isNight() || this.isExposedToRain();                // dawn or dry sky hands movement back
    }

    @Override
    public void stop() {
        this.unit.setShiftKeyDown(false);                               // never leave a watch crouch latched on
        this.unit.getNavigation().stop();                               // drop any stale watch-round path
        this.holdTicks = 0;                                             // re-enter clean next dusk
        this.crouchTicks = 0;
    }

    @Override
    public void tick() {
        BlockPos center = this.unit.getVillageCenter();
        if (center == null) {
            return;                                                     // nothing to watch over
        }

        double leash = ClassSystemConfig.vdfPatrolRadius();
        double distSq = this.unit.distanceToSqr(
                center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
        if (distSq > leash * leash) {                                   // drifted outside the village bubble
            if (this.unit.getNavigation().isDone()) {
                this.unit.getNavigation().moveTo(                       // walk back toward the bell
                        center.getX() + 0.5, center.getY(), center.getZ() + 0.5, 1.0D);
            }
            return;                                                     // leash recovery beats watch posts
        }

        if (!this.unit.getNavigation().isDone()) {
            this.unit.setShiftKeyDown(false);                           // never sneak-walk the rounds
            return;                                                     // still moving to the last post
        }

        this.tickCrouchPulse();                                         // stand watch - crouch scans on a post

        if (--this.holdTicks > 0) {
            return;                                                     // hold the post until the next round
        }

        BlockPos next = this.pickWatchSpot(center, leash);
        if (next == null || !this.tryWalkTo(next)) {
            this.holdTicks = REPATH;                                    // nowhere reachable - retry shortly
            return;
        }
        this.holdTicks = HOLD_MIN + this.unit.getRandom().nextInt(HOLD_JITTER); // settle in for the watch
    }

    // Posts prefer the village's beds; in rain a roofed spot wins outright.
    private BlockPos pickWatchSpot(BlockPos center, double leash) {
        if (this.isRaining()) {
            BlockPos dry = this.pickShelterSpot(center, leash);         // any roof inside the leash
            if (dry != null) {
                return dry;
            }
        }
        BlockPos bed = this.pickHomePoi(center, leash);                 // watch over the sleepers
        if (bed != null) {
            Vec3 near = DefaultRandomPos.getPosTowards(                 // the doorway, not the bed itself
                    this.unit, 8, 4, Vec3.atBottomCenterOf(bed), 1.5D);
            if (near != null) {
                return BlockPos.containing(near);
            }
        }
        BlockPos dry = this.pickShelterSpot(center, leash);             // no bed - still prefer a roof if any
        if (dry != null) {
            return dry;
        }
        Vec3 random = DefaultRandomPos.getPos(this.unit, 12, 4);        // last resort: plain watch corner
        return random != null ? BlockPos.containing(random) : null;
    }

    private BlockPos pickHomePoi(BlockPos center, double leash) {
        if (!(this.unit.level() instanceof ServerLevel server)) {
            return null;                                                // POI index is server-side only
        }
        return server.getPoiManager().getRandom(
                        holder -> holder.is(PoiTypes.HOME),             // beds only - the watch guards sleepers
                        pos -> pos.distSqr(center) <= leash * leash,
                        PoiManager.Occupancy.ANY,
                        center, 32,
                        this.unit.getRandom())
                .orElse(null);
    }

    // Finds a roofed position inside the leash by sampling random reachable
    // spots and keeping the first that can't see the sky.
    private BlockPos pickShelterSpot(BlockPos center, double leash) {
        for (int i = 0; i < SHELTER_TRIES; i++) {
            Vec3 v = DefaultRandomPos.getPos(this.unit, 12, 4);
            if (v == null) {
                continue;
            }
            BlockPos pos = BlockPos.containing(v);
            if (pos.distSqr(center) > leash * leash) {
                continue;                                               // stay inside the village bubble
            }
            if (!this.unit.level().canSeeSky(pos)) {
                return pos;                                             // roofed - out of the rain
            }
        }
        return null;
    }

    // While standing a post the militia periodically drops into a crouch for
    // a few seconds and pans its gaze across the perimeter - reads as actively
    // scanning the dark instead of standing paralyzed.
    private void tickCrouchPulse() {
        double scanAngle = (this.unit.tickCount * 0.045D) + (this.unit.getId() * 1.7D);
        double lookX = this.unit.getX() + Math.cos(scanAngle) * 6.0D;
        double lookZ = this.unit.getZ() + Math.sin(scanAngle) * 6.0D;
        this.unit.getLookControl().setLookAt(lookX, this.unit.getEyeY() - 0.15D, lookZ, 18.0F, 18.0F);

        if (this.crouchTicks > 0) {
            this.unit.setShiftKeyDown(true);
            if (--this.crouchTicks <= 0) {
                this.unit.setShiftKeyDown(false);                       // scan over - stand back up
                this.crouchCooldown = CROUCH_GAP + this.unit.getRandom().nextInt(CROUCH_GAP);
            }
            return;
        }
        if (--this.crouchCooldown <= 0) {                               // time for another scan
            this.crouchTicks = CROUCH_MIN + this.unit.getRandom().nextInt(CROUCH_JITTER);
        }
    }

    private boolean tryWalkTo(BlockPos pos) {
        Path path = this.unit.getNavigation().createPath(pos, 0);       // path-check before committing
        if (path == null || !path.canReach()) {
            return false;                                               // unreachable post - caller retries
        }
        this.unit.getNavigation().moveTo(path, WALK_SPEED);
        return true;
    }

    private boolean isNight() {
        return !this.unit.level().isDay();                              // sky darkened past day phase
    }

    private boolean isRaining() {
        return this.unit.level().isRaining();
    }

    private boolean isExposedToRain() {
        return this.isRaining()
                && this.unit.level().canSeeSky(this.unit.blockPosition()); // wet and unsheltered
    }
}
