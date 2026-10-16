// Network package - client->server: assign a plotted patrol route
package com.poseidon.sem_classsystem.network;

import com.poseidon.sem_classsystem.entity.ai.UnitChunkDutyGoal;
import com.poseidon.sem_classsystem.world.PlayerChunkClaims;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// Route duty: the unit walks a cyclic loop of waypoints plotted on the world
// map. Same server-side contract as PacketAssignDuty - every waypoint's chunk
// must be claimed by the sender and every unit must be theirs.
public class PacketAssignRoute {

    public static final int MAX_WAYPOINTS = 16;

    private final List<Integer> unitIds;
    private final String dimension;
    private final long[] waypoints;      // BlockPos.asLong each

    public PacketAssignRoute(List<Integer> unitIds, String dimension, long[] waypoints) {
        this.unitIds = unitIds;
        this.dimension = dimension;
        this.waypoints = waypoints;
    }

    public static void encode(PacketAssignRoute msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.unitIds.size());
        for (int id : msg.unitIds) {
            buf.writeVarInt(id);
        }
        buf.writeUtf(msg.dimension, 64);
        buf.writeVarInt(msg.waypoints.length);
        for (long wp : msg.waypoints) {
            buf.writeLong(wp);
        }
    }

    // Client-supplied counts size allocations below - cap them before use so
    // a hostile client cannot make the server allocate gigabytes.
    public static final int MAX_UNITS = 512;

    public static PacketAssignRoute decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_UNITS) {
            throw new io.netty.handler.codec.DecoderException("route unit count " + count);
        }
        List<Integer> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(buf.readVarInt());
        }
        String dim = buf.readUtf(64);
        int wpCount = buf.readVarInt();
        if (wpCount < 0 || wpCount > MAX_WAYPOINTS) {
            throw new io.netty.handler.codec.DecoderException("route waypoint count " + wpCount);
        }
        long[] waypoints = new long[wpCount];
        for (int i = 0; i < wpCount; i++) {
            waypoints[i] = buf.readLong();
        }
        return new PacketAssignRoute(ids, dim, waypoints);
    }

    public static void handle(PacketAssignRoute msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null || !(sender.level() instanceof ServerLevel level)) {
                return;
            }
            String dim = level.dimension().location().toString();
            if (!dim.equals(msg.dimension)) {
                return;   // duty only applies inside the unit's own dimension
            }
            if (msg.waypoints.length < 2 || msg.waypoints.length > MAX_WAYPOINTS) {
                return;
            }
            PlayerChunkClaims claims = PlayerChunkClaims.get(sender.getServer().overworld());
            for (long wp : msg.waypoints) {
                BlockPos p = BlockPos.of(wp);
                if (!claims.isClaimedBy(sender.getUUID(), dim, p.getX() >> 4, p.getZ() >> 4)) {
                    sender.displayClientMessage(Component.literal(
                            "§cEvery waypoint must sit inside a chunk you claimed."), true);
                    return;
                }
            }

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
                UnitChunkDutyGoal.assignRoute(unit, dim, msg.waypoints);
                assigned++;
            }
            sender.displayClientMessage(Component.literal(
                    "§aRoute assigned: " + assigned + " unit(s), "
                            + msg.waypoints.length + " waypoint(s)"), true);
        });
        ctx.get().setPacketHandled(true);
    }
}
