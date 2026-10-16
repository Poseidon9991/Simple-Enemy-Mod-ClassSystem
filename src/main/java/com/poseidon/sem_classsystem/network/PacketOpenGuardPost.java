// Network package - server->client: open the guard post screen with a snapshot
package com.poseidon.sem_classsystem.network;

import com.poseidon.sem_classsystem.block.GuardPostBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

// Everything the post UI needs in one shot: slot contents (uuid + cached
// name, so a dead/unloaded unit still labels) and the nearby posts that
// have a free slot for the transfer buttons.
public class PacketOpenGuardPost {

    public record Slot(UUID unit, String name) {
    }

    private final BlockPos pos;
    private final Slot primary;
    private final Slot reserve;
    private final List<BlockPos> nearbyPosts;

    public PacketOpenGuardPost(BlockPos pos, GuardPostBlockEntity be,
                               List<BlockPos> nearbyPosts) {
        this(pos,
                be.primary() == null ? null : new Slot(be.primary(), be.primaryName()),
                be.reserve() == null ? null : new Slot(be.reserve(), be.reserveName()),
                nearbyPosts);
    }

    private PacketOpenGuardPost(BlockPos pos, Slot primary, Slot reserve,
                                List<BlockPos> nearbyPosts) {
        this.pos = pos;
        this.primary = primary;
        this.reserve = reserve;
        this.nearbyPosts = nearbyPosts;
    }

    public BlockPos pos() {
        return this.pos;
    }

    public Slot primary() {
        return this.primary;
    }

    public Slot reserve() {
        return this.reserve;
    }

    public List<BlockPos> nearbyPosts() {
        return this.nearbyPosts;
    }

    public static void encode(PacketOpenGuardPost msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        writeSlot(buf, msg.primary);
        writeSlot(buf, msg.reserve);
        buf.writeVarInt(msg.nearbyPosts.size());
        for (BlockPos p : msg.nearbyPosts) {
            buf.writeBlockPos(p);
        }
    }

    private static void writeSlot(FriendlyByteBuf buf, Slot slot) {
        buf.writeBoolean(slot != null);
        if (slot != null) {
            buf.writeUUID(slot.unit());
            buf.writeUtf(slot.name(), 32);
        }
    }

    public static PacketOpenGuardPost decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        Slot primary = readSlot(buf);
        Slot reserve = readSlot(buf);
        int count = buf.readVarInt();
        if (count < 0 || count > 512) {
            throw new io.netty.handler.codec.DecoderException("nearby post count " + count);
        }
        List<BlockPos> nearby = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            nearby.add(buf.readBlockPos());
        }
        return new PacketOpenGuardPost(pos, primary, reserve, nearby);
    }

    private static Slot readSlot(FriendlyByteBuf buf) {
        return buf.readBoolean() ? new Slot(buf.readUUID(), buf.readUtf(32)) : null;
    }

    public static void handle(PacketOpenGuardPost msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.poseidon.sem_classsystem.client.ClientPacketHooks
                        .openGuardPost(msg)));
        ctx.get().setPacketHandled(true);
    }
}
