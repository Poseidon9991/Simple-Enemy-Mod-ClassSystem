// Network package - server->client: night raid map ping
package com.poseidon.sem_classsystem.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// Sent alongside the raid warning: the client paints a pulsing marker (or a
// screen-edge chevron off-screen) on the world map at the spawn centre.
// Expires client-side after a couple of minutes.
public class PacketRaidPing {

    private final String dimension;
    private final double x;
    private final double z;

    public String dimension() {
        return this.dimension;
    }

    public double x() {
        return this.x;
    }

    public double z() {
        return this.z;
    }

    public PacketRaidPing(String dimension, double x, double z) {
        this.dimension = dimension;
        this.x = x;
        this.z = z;
    }

    public static void encode(PacketRaidPing msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.dimension, 64);
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.z);
    }

    public static PacketRaidPing decode(FriendlyByteBuf buf) {
        return new PacketRaidPing(buf.readUtf(64), buf.readDouble(), buf.readDouble());
    }

    public static void handle(PacketRaidPing msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.poseidon.sem_classsystem.client.ClientPacketHooks
                        .onRaidPing(msg)));
        ctx.get().setPacketHandled(true);
    }
}
