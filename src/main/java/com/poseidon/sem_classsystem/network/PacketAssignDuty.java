// Network package - client->server: post selected units on a claimed chunk
package com.poseidon.sem_classsystem.network;

import com.poseidon.sem_classsystem.entity.ai.UnitChunkDutyGoal;
import com.poseidon.sem_classsystem.world.PlayerChunkClaims;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

// Assigns GUARD (hold the chunk centre) or PATROL (walk a loop inside the
// chunk) to owned units. Requires the target chunk to be claimed by the
// sender - that is the whole point of the claim system.
public class PacketAssignDuty {

    public static final int MODE_GUARD = 1;
    public static final int MODE_PATROL = 2;
    public static final int MODE_PERIMETER = 3;

    // A perimeter route caps at this many waypoints - bigger patches get a
    // thinned-out loop rather than an unbounded waypoint list.
    private static final int MAX_PERIMETER_POINTS = 24;

    private final List<Integer> unitIds;
    private final String dimension;
    private final int chunkX;
    private final int chunkZ;
    private final int mode;

    public PacketAssignDuty(List<Integer> unitIds, String dimension,
                            int chunkX, int chunkZ, int mode) {
        this.unitIds = unitIds;
        this.dimension = dimension;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.mode = mode;
    }

    public static void encode(PacketAssignDuty msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.unitIds.size());
        for (int id : msg.unitIds) {
            buf.writeVarInt(id);
        }
        buf.writeUtf(msg.dimension, 64);
        buf.writeVarInt(msg.chunkX);
        buf.writeVarInt(msg.chunkZ);
        buf.writeVarInt(msg.mode);
    }

    public static PacketAssignDuty decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > PacketAssignRoute.MAX_UNITS) {
            throw new io.netty.handler.codec.DecoderException("duty unit count " + count);
        }
        List<Integer> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(buf.readVarInt());
        }
        return new PacketAssignDuty(ids, buf.readUtf(64), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(PacketAssignDuty msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null || !(sender.level() instanceof ServerLevel level)) {
                return;
            }
            String dim = level.dimension().location().toString();
            if (!dim.equals(msg.dimension)) {
                return;   // duty only applies inside the unit's own dimension
            }
            PlayerChunkClaims claims = PlayerChunkClaims.get(sender.getServer().overworld());
            if (!claims.isClaimedBy(sender.getUUID(), dim, msg.chunkX, msg.chunkZ)) {
                sender.displayClientMessage(Component.literal(
                        "That chunk is not claimed by you.").withStyle(ChatFormatting.RED), true);
                return;
            }

            // The duty area is the whole connected claim patch the clicked
            // chunk belongs to - "guard this" means the territory, not one tile.
            long[] region = claims.connectedRegion(
                    sender.getUUID(), dim, msg.chunkX, msg.chunkZ);

            // PERIMETER converts the patch into a route around its boundary
            // chunks and lands on the existing route-duty machinery.
            long[] route = msg.mode == MODE_PERIMETER
                    ? perimeterRoute(level, region) : null;

            int assigned = 0;
            for (int entityId : msg.unitIds) {
                Entity e = level.getEntity(entityId);
                if (!(e instanceof PmcUnitEntity unit) || !unit.isOwnedBy(sender)) {
                    continue;
                }
                unit.setTarget(null);
                unit.releaseMovementLock();
                unit.setOrder(OrderType.HOLD_POSITION);   // also clears stale duty via mixin
                unit.resetCommanderGoalCooldown();
                if (route != null) {
                    if (route.length == 0) {
                        continue;
                    }
                    UnitChunkDutyGoal.assignRoute(unit, dim, route);
                } else {
                    UnitChunkDutyGoal.assign(unit, dim, msg.chunkX, msg.chunkZ, msg.mode, region);
                }
                assigned++;
            }
            String label = switch (msg.mode) {
                case MODE_PATROL -> "patrolling";
                case MODE_PERIMETER -> "patrolling the perimeter of";
                default -> "guarding";
            };
            String area = region.length > 1
                    ? "region of " + region.length + " chunks"
                    : "chunk [" + msg.chunkX + ", " + msg.chunkZ + "]";
            sender.displayClientMessage(Component.literal(
                    "§aDuty assigned: " + assigned + " unit(s) " + label
                            + " " + area), true);
        });
        ctx.get().setPacketHandled(true);
    }

    // Boundary chunks (a claimed chunk with at least one edge facing outside
    // the patch) ordered into a loop by angle around the patch centroid, then
    // capped at MAX_PERIMETER_POINTS. Angular ordering walks the outline well
    // enough for blob-shaped territory; deeply concave patches get a slightly
    // chorded path, which still loops the perimeter.
    private static long[] perimeterRoute(ServerLevel level, long[] region) {
        java.util.Set<Long> patch = new java.util.HashSet<>();
        for (long c : region) {
            patch.add(c);
        }
        List<double[]> boundary = new ArrayList<>();
        double cx = 0, cz = 0;
        for (long c : patch) {
            int x = ChunkPos.getX(c), z = ChunkPos.getZ(c);
            boolean boundaryChunk = !patch.contains(ChunkPos.asLong(x + 1, z))
                    || !patch.contains(ChunkPos.asLong(x - 1, z))
                    || !patch.contains(ChunkPos.asLong(x, z + 1))
                    || !patch.contains(ChunkPos.asLong(x, z - 1));
            if (!boundaryChunk) {
                continue;
            }
            boundary.add(new double[]{(x << 4) + 8.0, (z << 4) + 8.0});
            cx += (x << 4) + 8.0;
            cz += (z << 4) + 8.0;
        }
        if (boundary.isEmpty()) {
            return new long[0];
        }
        cx /= boundary.size();
        cz /= boundary.size();
        final double fcx = cx, fcz = cz;
        boundary.sort(Comparator.comparingDouble(
                p -> Math.atan2(p[1] - fcz, p[0] - fcx)));
        while (boundary.size() > MAX_PERIMETER_POINTS) {
            // Thin evenly: drop every second point - keeps the loop shape.
            for (int i = boundary.size() - 1; i >= 0; i -= 2) {
                boundary.remove(i);
            }
        }
        long[] route = new long[boundary.size()];
        for (int i = 0; i < boundary.size(); i++) {
            BlockPos surface = level.getHeightmapPos(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    new BlockPos((int) boundary.get(i)[0], 64,
                            (int) boundary.get(i)[1]));
            route[i] = surface.asLong();
        }
        return route;
    }
}
