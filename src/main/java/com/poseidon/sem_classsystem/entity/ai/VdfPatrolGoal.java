// Entity.ai package - militia walks the village on a waypoint loop
package com.poseidon.sem_classsystem.entity.ai;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;          // [vdf] patrolRadius
import com.poseidon.sem_classsystem.entity.VdfUnitEntity;              // the militia unit this drives
import net.minecraft.core.BlockPos;                                    // waypoint + village center positions
import net.minecraft.core.Holder;                                      // POI type holders from the PoiManager scan
import net.minecraft.world.entity.ai.goal.Goal;                        // vanilla goal base class
import net.minecraft.world.entity.ai.util.DefaultRandomPos;            // random reachable positions near a point
import net.minecraft.world.entity.ai.village.poi.PoiManager;           // village POI index (bells, beds, job sites)
import net.minecraft.world.entity.ai.village.poi.PoiType;              // POI type class for the holder predicate
import net.minecraft.world.entity.ai.village.poi.PoiTypes;             // MEETING/HOME poi keys
import net.minecraft.world.level.pathfinder.Path;                      // nav result (null check for reachability)
import net.minecraft.world.phys.Vec3;                                  // random-walk target vectors

import java.util.EnumSet;                                              // goal flag set (MOVE)

// Idle patrol: the militia walks a slow waypoint loop between the village's
// points of interest (bell, beds, workstations) and always stays inside
// patrolRadius of its assigned bell. Any combat target (or an active raid
// handled by VdfRaidResponseGoal) makes this yield immediately.
public class VdfPatrolGoal extends Goal {

    private static final int RESCAN_INTERVAL = 60;                      // ticks between POI waypoint picks
    private static final int STUCK_REPATH = 100;                        // force a new waypoint after this long stuck
    private static final double WALK_SPEED = 0.55D;                     // patrol is a stroll, not a sprint

    private final VdfUnitEntity unit;                                   // the militia unit this goal drives
    private int scanCooldown = 0;                                       // ticks until the next POI scan
    private int stuckTicks = 0;                                         // ticks spent with no usable path

    public VdfPatrolGoal(VdfUnitEntity unit) {
        this.unit = unit;                                               // store the unit for all goal phases
        this.setFlags(EnumSet.of(Flag.MOVE));                           // claims movement - combat goals win ties
    }

    @Override
    public boolean canUse() {
        BlockPos center = this.unit.getVillageCenter();                 // bell this militia is posted to
        if (center == null                                              // unassigned militia has nothing to patrol
                || this.unit.getTarget() != null                        // fighting - combat goals own the move flag
                || this.unit.tickCount % 20 != 0) {                     // cheap gate: only evaluate every second
            return false;
        }
        return true;                                                    // continuous goal - stays usable while walking
    }

    @Override
    public boolean canContinueToUse() {
        return this.unit.getTarget() == null                            // stop the instant something hostile shows
                && this.unit.getVillageCenter() != null;                // ...or the anchor got cleared
    }

    @Override
    public void stop() {
        this.unit.getNavigation().stop();                               // halt leftover path when combat/greeting preempts us
        this.stuckTicks = 0;                                            // re-enter with a clean stuck clock
    }

    @Override
    public void tick() {
        BlockPos center = this.unit.getVillageCenter();                 // re-read - SavedData restores it async
        if (center == null) {
            return;                                                     // nothing to patrol around
        }

        double leash = ClassSystemConfig.vdfPatrolRadius();             // config leash from the bell
        double distSq = this.unit.distanceToSqr(                        // current distance to the bell, squared
                center.getX() + 0.5, center.getY(), center.getZ() + 0.5);

        if (distSq > leash * leash) {                                   // wandered outside the village bubble
            if (this.unit.getNavigation().isDone()) {                   // re-issue only once the last try finished
                this.unit.getNavigation().moveTo(                       // ...walk straight back toward the bell
                        center.getX() + 0.5, center.getY(), center.getZ() + 0.5, 1.0D);
            }
            return;                                                     // leash recovery beats waypoint walking
        }

        if (!this.unit.getNavigation().isDone()) {
            return;                                                     // still walking to the last waypoint
        }
        if (--this.scanCooldown > 0) {
            return;                                                     // hold at the post until the next leg
        }
        // Waypoint pick: prefer a spot near a real POI, else a plain random
        // stroll point - and only commit when a reachable path exists. Beds
        // sit inside sealed houses, so we aim for the POI's vicinity rather
        // than the block itself; on a miss we retry soon instead of freezing
        // in place until the next scan window.
        BlockPos next = null;
        BlockPos poi = this.pickPoiWaypoint(center, leash);
        if (poi != null) {
            Vec3 near = DefaultRandomPos.getPosTowards(                 // reachable spot beside the POI
                    this.unit, 8, 4, Vec3.atBottomCenterOf(poi), 1.5D);
            next = near != null ? BlockPos.containing(near) : null;
        }
        if (next == null || !this.tryWalkTo(next)) {
            Vec3 random = DefaultRandomPos.getPos(this.unit, 12, 4);    // POI leg failed - plain stroll
            next = random != null ? BlockPos.containing(random) : null;
            if (next != null && this.tryWalkTo(next)) {
                this.stuckTicks = 0;
                this.scanCooldown = RESCAN_INTERVAL + this.unit.getRandom().nextInt(40);
                return;
            }
            if (++this.stuckTicks > STUCK_REPATH) {                     // nothing reachable for a long while
                this.stuckTicks = 0;
            }
            this.scanCooldown = 15;                                     // retry soon - never freeze in place
            return;
        }
        this.stuckTicks = 0;
        this.scanCooldown = RESCAN_INTERVAL + this.unit.getRandom().nextInt(40); // jittered hold so squads don't march in sync
    }

    // Commits the unit to a path only when the destination is reachable -
    // returns false so the caller can fall back instead of idling.
    private boolean tryWalkTo(BlockPos pos) {
        Path path = this.unit.getNavigation().createPath(pos, 0);
        if (path == null || !path.canReach()) {
            return false;
        }
        this.unit.getNavigation().moveTo(path, WALK_SPEED);
        return true;
    }

    // Picks a random POI (bell, bed, job site) inside the patrol bubble.
    // PoiManager.findClosest needs a type predicate - we take any of the
    // village-ish types so the route feels like a real militia round.
    private BlockPos pickPoiWaypoint(BlockPos center, double leash) {
        if (!(this.unit.level() instanceof net.minecraft.server.level.ServerLevel server)) {
            return null;                                                // POI index is server-side only
        }
        return server.getPoiManager().getRandom(                        // random POI of matching types nearby
                        holder -> isVillagePoi(holder),                 // type filter
                        pos -> pos.distSqr(center) <= leash * leash,    // inside the leash
                        PoiManager.Occupancy.ANY,                       // occupied POIs count too
                        center, 32,                                     // search 32 blocks out from the bell
                        this.unit.getRandom())                          // per-entity random source
                .orElse(null);                                          // none found -> caller falls back to random walk
    }

    private static boolean isVillagePoi(Holder<PoiType> holder) {       // which POI types count as "the round"
        return holder.is(PoiTypes.MEETING)                              // the village bell - always a stop
                || holder.is(PoiTypes.HOME);                            // villager beds - check on sleepers
    }
}
