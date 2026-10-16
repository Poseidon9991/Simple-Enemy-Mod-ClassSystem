// Network package - client->server: send bound units back to their posts
package com.poseidon.sem_classsystem.network;

import com.poseidon.sem_classsystem.block.GuardPostBlockEntity;
import com.poseidon.sem_classsystem.entity.ai.UnitChunkDutyGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// "Return to post" on the map: re-activate post duty for every selected
// unit that still has a binding. When the returning unit is a post's
// PRIMARY, a reserve that was covering stands down to free fire.
public class PacketReturnToPost {

    private final List<Integer> unitIds;

    public PacketReturnToPost(List<Integer> unitIds) {
        this.unitIds = unitIds;
    }

    public static void encode(PacketReturnToPost msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.unitIds.size());
        for (int id : msg.unitIds) {
            buf.writeVarInt(id);
        }
    }

    public static PacketReturnToPost decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > PacketAssignRoute.MAX_UNITS) {
            throw new io.netty.handler.codec.DecoderException("return-to-post count " + count);
        }
        List<Integer> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(buf.readVarInt());
        }
        return new PacketReturnToPost(ids);
    }

    public static void handle(PacketReturnToPost msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null || !(sender.level() instanceof ServerLevel level)) {
                return;
            }
            int sent = 0;
            for (int id : msg.unitIds) {
                if (!(level.getEntity(id) instanceof PmcUnitEntity unit)
                        || !unit.isOwnedBy(sender)) {
                    continue;
                }
                BlockPos post = UnitChunkDutyGoal.boundPost(unit);
                if (post == null
                        || !(level.getBlockEntity(post)
                        instanceof GuardPostBlockEntity be)
                        || !be.binds(unit.getUUID())) {
                    continue;   // binding is stale or the post is gone
                }
                UnitChunkDutyGoal.assignPost(unit,
                        level.dimension().location().toString(), post);
                if (unit.getUUID().equals(be.primary())) {
                    be.standDownReserve(level);   // primary back -> reserve released
                }
                sent++;
            }
            if (sent > 0) {
                sender.displayClientMessage(Component.literal(
                        sent + " unit(s) returning to post."), true);
            } else {
                sender.displayClientMessage(Component.literal(
                        "No selected unit is bound to a guard post."), true);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
