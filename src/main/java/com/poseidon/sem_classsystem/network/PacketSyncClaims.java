// Network package - server->client snapshot of chunk claims in a dimension
package com.poseidon.sem_classsystem.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

// Every claim in the viewer's dimension, each tagged with its owner so the
// client can colour "mine" green and "theirs" amber. Full replacement like
// the marker packet.
public class PacketSyncClaims {

    public record ClaimEntry(UUID owner, int chunkX, int chunkZ) {
    }

    private final String dimension;
    private final List<ClaimEntry> claims;

    public PacketSyncClaims(String dimension, List<ClaimEntry> claims) {
        this.dimension = dimension;
        this.claims = claims;
    }

    public String dimension() {
        return this.dimension;
    }

    public List<ClaimEntry> claims() {
        return this.claims;
    }

    public static void encode(PacketSyncClaims msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.dimension, 64);
        buf.writeVarInt(msg.claims.size());
        for (ClaimEntry c : msg.claims) {
            buf.writeUUID(c.owner());
            buf.writeVarInt(c.chunkX());
            buf.writeVarInt(c.chunkZ());
        }
    }

    public static PacketSyncClaims decode(FriendlyByteBuf buf) {
        String dim = buf.readUtf(64);
        int count = buf.readVarInt();
        if (count < 0 || count > 65536) {
            throw new io.netty.handler.codec.DecoderException("claim count " + count);
        }
        List<ClaimEntry> claims = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            claims.add(new ClaimEntry(buf.readUUID(), buf.readVarInt(), buf.readVarInt()));
        }
        return new PacketSyncClaims(dim, claims);
    }

    public static void handle(PacketSyncClaims msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.poseidon.sem_classsystem.client.ClientPacketHooks
                        .onClaims(msg)));
        ctx.get().setPacketHandled(true);
    }
}
