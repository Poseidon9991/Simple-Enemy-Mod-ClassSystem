// Network package - server->client casualty board for the tablet INTEL app
package com.poseidon.sem_classsystem.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// A full replacement snapshot of the caller's KIA lines - same philosophy as
// the marker sync (wholesale swap, no merge bookkeeping). Small and capped.
public class PacketSyncIntel {

    private static final int MAX_LINES = 32;
    private static final int MAX_LEN = 96;

    private final List<String> casualties;

    public PacketSyncIntel(List<String> casualties) {
        this.casualties = casualties;
    }

    public static void encode(PacketSyncIntel msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.casualties.size());
        for (String line : msg.casualties) {
            buf.writeUtf(line, MAX_LEN);
        }
    }

    public static PacketSyncIntel decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_LINES) {
            throw new io.netty.handler.codec.DecoderException("intel line count " + count);
        }
        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(buf.readUtf(MAX_LEN));
        }
        return new PacketSyncIntel(lines);
    }

    public static void handle(PacketSyncIntel msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.poseidon.sem_classsystem.client.ClientPacketHooks
                        .onCasualties(msg.casualties)));
        ctx.get().setPacketHandled(true);
    }
}
