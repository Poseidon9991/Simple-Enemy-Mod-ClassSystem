// Network package - client->server: teleport owned units to a spot
package com.poseidon.sem_classsystem.network;

import com.poseidon.sem_classsystem.entity.ai.UnitChunkDutyGoal;
import com.poseidon.sem_classsystem.event.GuardPostEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// Tablet teleport app: send selected units to the player, a waypoint or
// the block under the crosshair. Teleporting counts as a fresh command -
// it clears duty (a posted guard's reserve gets promoted) but keeps the
// post binding so Return-to-post still knows where home is.
public class PacketTeleportUnits {

    public static final int MODE_PLAYER = 0;
    public static final int MODE_POS = 1;

    private final List<Integer> unitIds;
    private final int mode;
    private final BlockPos target;

    public PacketTeleportUnits(List<Integer> unitIds, int mode, BlockPos target) {
        this.unitIds = unitIds;
        this.mode = mode;
        this.target = target;
    }

    public static void encode(PacketTeleportUnits msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.unitIds.size());
        for (int id : msg.unitIds) {
            buf.writeVarInt(id);
        }
        buf.writeByte(msg.mode);
        buf.writeBoolean(msg.target != null);
        if (msg.target != null) {
            buf.writeBlockPos(msg.target);
        }
    }

    public static PacketTeleportUnits decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > PacketAssignRoute.MAX_UNITS) {
            throw new io.netty.handler.codec.DecoderException("teleport unit count " + count);
        }
        List<Integer> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(buf.readVarInt());
        }
        int mode = buf.readByte();
        BlockPos target = buf.readBoolean() ? buf.readBlockPos() : null;
        return new PacketTeleportUnits(ids, mode, target);
    }

    public static void handle(PacketTeleportUnits msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null || !(sender.level() instanceof ServerLevel level)) {
                return;
            }
            BlockPos anchor = msg.mode == MODE_PLAYER
                    ? sender.blockPosition() : msg.target;
            if (anchor == null) {
                return;
            }
            // MODE_POS coordinates come from the client (waypoint/crosshair) -
            // clamp Y into world bounds so a bad target can't drop a unit
            // into the void or above the build ceiling.
            int ty = Math.min(Math.max(anchor.getY(),
                    level.getMinBuildHeight() + 1), level.getMaxBuildHeight() - 2);
            anchor = new BlockPos(anchor.getX(), ty, anchor.getZ());
            int moved = 0;
            int i = 0;
            for (int id : msg.unitIds) {
                if (!(level.getEntity(id) instanceof PmcUnitEntity unit)
                        || !unit.isOwnedBy(sender)) {
                    continue;
                }
                // Ring spread so squad members don't stack in one block.
                double dx = ((i % 3) - 1) * 1.2D;
                double dz = (((i / 3) % 3) - 1) * 1.2D;
                int duty = UnitChunkDutyGoal.dutyOf(unit);
                if (duty != 0) {
                    UnitChunkDutyGoal.clear(unit);
                    GuardPostEvents.onPostDutyCleared(unit, duty);
                }
                unit.teleportTo(anchor.getX() + 0.5D + dx,
                        anchor.getY() + 1.0D, anchor.getZ() + 0.5D + dz);
                unit.getNavigation().stop();
                moved++;
                i++;
            }
            if (moved > 0) {
                level.playSound(null, anchor, SoundEvents.ENDERMAN_TELEPORT,
                        SoundSource.NEUTRAL, 0.8F, 1.1F);
                sender.displayClientMessage(Component.literal(
                        "Teleported " + moved + " unit(s)."), true);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
