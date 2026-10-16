package com.poseidon.sem_classsystem.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.EnumSet;
import java.util.UUID;

// Chunk duty: the unit holds or patrols a player-claimed chunk, assigned
// from the world map (PacketAssignDuty). State rides on the unit's
// persistent NBT so it survives save/load; any fresh SEM order clears it
// (see MixinPmcUnitDutyClear), which keeps "duty" strictly a background job.
//
// Priority 2 - above SEM's CommanderOrderGoal (priority 3, MOVE flag) so a
// posted guard actually drives movement, but below combat/melee/self-heal
// (priority 1-2 combat goals win while a target is up; canUse also bails
// the moment the unit has a live target).
public class UnitChunkDutyGoal extends Goal {

    public static final int DUTY_GUARD = 1;
    public static final int DUTY_PATROL = 2;
    public static final int DUTY_ROUTE = 3;
    public static final int DUTY_POST = 4;

    private static final String TAG_DUTY = "semcs_duty";        // 0 none, 1 guard, 2 patrol, 3 route, 4 post
    private static final String TAG_X = "semcs_duty_x";         // anchor chunk coords
    private static final String TAG_Z = "semcs_duty_z";
    private static final String TAG_DIM = "semcs_duty_dim";
    private static final String TAG_CHUNKS = "semcs_duty_chunks"; // guard/patrol region (ChunkPos.asLong)
    private static final String TAG_ROUTE = "semcs_route";      // BlockPos.asLong waypoints
    private static final String TAG_ROUTE_IDX = "semcs_route_idx";
    private static final String TAG_POST = "semcs_post";        // guard-post binding (BlockPos.asLong);
                                                                // survives duty clears so "return to post" works

    private static final int PATROL_RADIUS = 7;                 // blocks around chunk centre
    private static final int WAIT_MIN = 60;
    private static final int WAIT_SPAN = 80;
    private static final int ROUTE_WAIT_MIN = 20;               // waypoint dwell
    private static final int ROUTE_WAIT_SPAN = 41;              // 20-60 ticks
    private static final double ROUTE_REACH_SQR = 6.25D;        // ~2.5 blocks

    private final PmcUnitEntity unit;
    private int waitTicks = 0;
    private boolean routeWalking = false;

    public UnitChunkDutyGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    // ---- NBT state ----------------------------------------------------------

    public static void assign(PmcUnitEntity unit, String dimension,
                              int chunkX, int chunkZ, int mode) {
        assign(unit, dimension, chunkX, chunkZ, mode,
                new long[]{ChunkPos.asLong(chunkX, chunkZ)});
    }

    // GUARD/PATROL over a merged claim region: `region` is every chunk in the
    // connected patch (PacketAssignDuty flood-fills it from the claims map).
    // The anchor chunk stays in TAG_X/Z so the map tether has a home; the
    // region array is what the unit actually walks.
    public static void assign(PmcUnitEntity unit, String dimension,
                              int chunkX, int chunkZ, int mode, long[] region) {
        CompoundTag data = unit.getPersistentData();
        data.putInt(TAG_DUTY, mode);
        data.putInt(TAG_X, chunkX);
        data.putInt(TAG_Z, chunkZ);
        data.putString(TAG_DIM, dimension);
        if (region == null || region.length == 0) {
            region = new long[]{ChunkPos.asLong(chunkX, chunkZ)};
        }
        data.putLongArray(TAG_CHUNKS, region);
        data.remove(TAG_ROUTE);
        data.remove(TAG_ROUTE_IDX);
    }

    // ROUTE duty: cyclic waypoint loop. The first node's chunk doubles as the
    // marker's duty chunk so the map has somewhere to hang the tether.
    public static void assignRoute(PmcUnitEntity unit, String dimension, long[] route) {
        if (route.length == 0) {
            return;
        }
        CompoundTag data = unit.getPersistentData();
        BlockPos first = BlockPos.of(route[0]);
        data.putInt(TAG_DUTY, DUTY_ROUTE);
        data.putInt(TAG_X, first.getX() >> 4);
        data.putInt(TAG_Z, first.getZ() >> 4);
        data.putString(TAG_DIM, dimension);
        data.putLongArray(TAG_ROUTE, route);
        data.putInt(TAG_ROUTE_IDX, 0);
    }

    public static void clear(PmcUnitEntity unit) {
        CompoundTag data = unit.getPersistentData();
        data.remove(TAG_DUTY);
        data.remove(TAG_X);
        data.remove(TAG_Z);
        data.remove(TAG_DIM);
        data.remove(TAG_ROUTE);
        data.remove(TAG_ROUTE_IDX);
        data.remove(TAG_CHUNKS);
        // NOTE: TAG_POST (the guard-post binding) deliberately survives -
        // a normal order clears the duty, not the post the unit belongs to.
    }

    // ---- Guard-post binding ---------------------------------------------------

    // Bind a unit to a guard post. Binding is separate from duty: the post
    // slot is owned by the block entity, the unit tag just remembers where
    // "home" is so the map's Return-to-post order can send it back.
    public static void bindPost(PmcUnitEntity unit, BlockPos post) {
        unit.getPersistentData().putLong(TAG_POST, post.asLong());
    }

    public static void unbindPost(PmcUnitEntity unit) {
        unit.getPersistentData().remove(TAG_POST);
    }

    // Bound post position, or null when unbound.
    public static BlockPos boundPost(PmcUnitEntity unit) {
        CompoundTag data = unit.getPersistentData();
        return data.contains(TAG_POST) ? BlockPos.of(data.getLong(TAG_POST)) : null;
    }

    // POST duty: march to the bound post and hold position on it. The post
    // chunk doubles as the map-tether anchor like the other duty modes.
    public static void assignPost(PmcUnitEntity unit, String dimension, BlockPos post) {
        CompoundTag data = unit.getPersistentData();
        data.putInt(TAG_DUTY, DUTY_POST);
        data.putInt(TAG_X, post.getX() >> 4);
        data.putInt(TAG_Z, post.getZ() >> 4);
        data.putString(TAG_DIM, dimension);
        data.putLong(TAG_POST, post.asLong());
        data.remove(TAG_ROUTE);
        data.remove(TAG_ROUTE_IDX);
        data.remove(TAG_CHUNKS);
    }

    public static int dutyOf(PmcUnitEntity unit) {
        return unit.getPersistentData().getInt(TAG_DUTY);
    }

    public static int dutyChunkX(PmcUnitEntity unit) {
        return unit.getPersistentData().getInt(TAG_X);
    }

    public static int dutyChunkZ(PmcUnitEntity unit) {
        return unit.getPersistentData().getInt(TAG_Z);
    }

    // Route waypoints (BlockPos.asLong); empty unless the unit is on route duty.
    public static long[] dutyRoute(PmcUnitEntity unit) {
        return unit.getPersistentData().getLongArray(TAG_ROUTE);
    }

    // Guard/patrol region (ChunkPos.asLong). Falls back to the anchor chunk
    // alone for units that saved before regions existed.
    public static long[] dutyChunks(PmcUnitEntity unit) {
        CompoundTag data = unit.getPersistentData();
        long[] region = data.getLongArray(TAG_CHUNKS);
        if (region.length == 0 && data.getInt(TAG_DUTY) != 0) {
            return new long[]{ChunkPos.asLong(
                    data.getInt(TAG_X), data.getInt(TAG_Z))};
        }
        return region;
    }

    // Called after a successful unclaim: the owner's units posted to the
    // removed chunk (guard/patrol) or routed through it lose their duty.
    public static void clearDutyInChunk(MinecraftServer server, UUID owner,
                                        String dim, int chunkX, int chunkZ) {
        ResourceLocation loc = ResourceLocation.tryParse(dim);
        if (loc == null) {
            return;
        }
        ServerLevel level = server.getLevel(
                ResourceKey.create(Registries.DIMENSION, loc));
        if (level == null) {
            return;   // dimension not loaded - no ticking units to clear
        }
        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        for (Entity e : level.getAllEntities()) {
            if (!(e instanceof PmcUnitEntity unit)
                    || !owner.equals(unit.getOwnerUUID())) {
                continue;
            }
            CompoundTag data = unit.getPersistentData();
            int duty = data.getInt(TAG_DUTY);
            if (duty == 0 || duty == DUTY_POST
                    || !dim.equals(data.getString(TAG_DIM))) {
                continue;   // posts aren't tied to claims
            }
            boolean touches;
            if (duty == DUTY_ROUTE) {
                touches = routeTouchesChunk(data.getLongArray(TAG_ROUTE), chunkKey);
            } else {
                // Region duty: any chunk of the merged patch unclaimed frees
                // the unit - its territory just changed shape under it.
                touches = data.getInt(TAG_X) == chunkX && data.getInt(TAG_Z) == chunkZ;
                if (!touches) {
                    for (long c : dutyChunks(unit)) {
                        if (c == chunkKey) {
                            touches = true;
                            break;
                        }
                    }
                }
            }
            if (touches) {
                clear(unit);
            }
        }
    }

    private static boolean routeTouchesChunk(long[] route, long chunkKey) {
        for (long wp : route) {
            BlockPos p = BlockPos.of(wp);
            if (ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4) == chunkKey) {
                return true;
            }
        }
        return false;
    }

    // ---- Goal ---------------------------------------------------------------

    @Override
    public boolean canUse() {
        CompoundTag data = this.unit.getPersistentData();
        int duty = data.getInt(TAG_DUTY);
        if (duty == 0 || this.unit.level().isClientSide) {
            return false;
        }
        // A posted guard NEVER yields movement, even in combat: it holds
        // Flag.MOVE so MoveToAttackRangeGoal cannot pull it off the post,
        // while the gun goal (LOOK only) still lets it fire. Every other
        // duty pauses the moment a fight starts.
        if (duty != DUTY_POST && this.unit.getTarget() != null) {
            return false;
        }
        String dim = this.unit.level().dimension().location().toString();
        return dim.equals(data.getString(TAG_DIM));
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    // An interrupted leg (combat, new order) must re-path on resume instead
    // of being mistaken for an arrival that skips the waypoint.
    @Override
    public void stop() {
        this.routeWalking = false;
    }

    @Override
    public void tick() {
        CompoundTag data = this.unit.getPersistentData();
        int mode = data.getInt(TAG_DUTY);

        if (mode == DUTY_ROUTE) {
            this.tickRoute(data);
            return;
        }

        if (mode == DUTY_POST) {
            // Guard post: hard 2-block tether - in or out of combat the unit
            // always returns to the block. It can still aim and shoot while
            // standing on it (the gun goal only needs LOOK).
            BlockPos post = BlockPos.of(data.getLong(TAG_POST));
            Vec3 centre = Vec3.atCenterOf(post.above());
            if (this.unit.distanceToSqr(centre) > 4.0D) {
                if (this.unit.getNavigation().isDone()) {
                    this.unit.getNavigation().moveTo(centre.x, centre.y, centre.z, 1.0D);
                }
            } else if (!this.unit.getNavigation().isDone()) {
                this.unit.getNavigation().stop();
            }
            return;
        }

        // The merged claim patch this duty covers (anchor chunk alone when a
        // unit was assigned before regions existed).
        long[] region = data.getLongArray(TAG_CHUNKS);
        if (region.length == 0) {
            region = new long[]{ChunkPos.asLong(
                    data.getInt(TAG_X), data.getInt(TAG_Z))};
        }

        if (mode == DUTY_GUARD) {
            // GUARD: each unit picks a stable home chunk out of the patch by
            // entity id, so a squad spreads across the territory instead of
            // stacking on one centre. ~2 blocks of slack.
            long home = region[Math.floorMod(this.unit.getId(), region.length)];
            Vec3 centre = Vec3.atCenterOf(new ChunkPos(home)
                    .getWorldPosition().atY((int) this.unit.getY()));
            if (this.unit.distanceToSqr(centre) > 4.0D
                    && this.unit.getNavigation().isDone()) {
                this.unit.getNavigation().moveTo(centre.x, centre.y, centre.z, 0.9D);
            }
            return;
        }

        // PATROL: wander the whole patch - random chunk, random point inside
        // it, dwell, repeat.
        if (this.waitTicks > 0) {
            this.waitTicks--;
            return;
        }
        if (!this.unit.getNavigation().isDone()) {
            return;
        }
        var random = this.unit.getRandom();
        long pick = region[random.nextInt(region.length)];
        Vec3 centre = Vec3.atCenterOf(new ChunkPos(pick)
                .getWorldPosition().atY((int) this.unit.getY()));
        double tx = centre.x + (random.nextDouble() - 0.5D) * 2.0D * PATROL_RADIUS;
        double tz = centre.z + (random.nextDouble() - 0.5D) * 2.0D * PATROL_RADIUS;
        this.unit.getNavigation().moveTo(tx, centre.y, tz, 0.8D);
        this.waitTicks = WAIT_MIN + random.nextInt(WAIT_SPAN);
    }

    // ROUTE: walk the current waypoint, dwell 20-60 ticks, advance cyclically.
    // A leg that the navigation gives up on (unreachable node) counts as
    // arrived - a bad waypoint must not stall the loop forever.
    private void tickRoute(CompoundTag data) {
        long[] route = data.getLongArray(TAG_ROUTE);
        if (route.length == 0) {
            data.putInt(TAG_DUTY, 0);
            return;
        }
        if (this.waitTicks > 0) {
            this.waitTicks--;
            return;
        }
        int idx = Math.floorMod(data.getInt(TAG_ROUTE_IDX), route.length);
        BlockPos wp = BlockPos.of(route[idx]);

        if (this.routeWalking) {
            if (!this.unit.getNavigation().isDone()) {
                return;
            }
            this.routeWalking = false;
        } else if (this.unit.distanceToSqr(Vec3.atCenterOf(wp)) > ROUTE_REACH_SQR) {
            this.unit.getNavigation().moveTo(
                    wp.getX() + 0.5D, wp.getY(), wp.getZ() + 0.5D, 0.9D);
            this.routeWalking = true;
            return;
        }
        this.waitTicks = ROUTE_WAIT_MIN + this.unit.getRandom().nextInt(ROUTE_WAIT_SPAN);
        data.putInt(TAG_ROUTE_IDX, (idx + 1) % route.length);
    }
}
