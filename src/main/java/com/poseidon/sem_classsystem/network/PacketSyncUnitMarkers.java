// Network package - server->client snapshot of map-visible units
package com.poseidon.sem_classsystem.network;

import com.poseidon.sem_classsystem.map.UnitMarker;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// Full replacement each send, never a merge: a unit that stops being sent
// (dead, unloaded, chunk out of range) must vanish, and wholesale swap is
// the only variant of that with no per-marker expiry bookkeeping.
public class PacketSyncUnitMarkers {

    private final List<UnitMarker> markers;

    public PacketSyncUnitMarkers(List<UnitMarker> markers) {
        this.markers = markers;
    }

    public List<UnitMarker> markers() {
        return this.markers;
    }

    public static void encode(PacketSyncUnitMarkers msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.markers.size());
        for (UnitMarker m : msg.markers) {
            buf.writeVarInt(m.entityId());
            buf.writeDouble(m.x());
            buf.writeDouble(m.y());
            buf.writeDouble(m.z());
            buf.writeUtf(m.dimension(), 64);
            buf.writeInt(m.rgb());
            buf.writeByte(m.classOrd());
            buf.writeByte(m.allegiance().ordinal());
            buf.writeFloat(m.healthFrac());
            buf.writeUtf(m.name(), 32);
            buf.writeByte(m.orderOrd());
            buf.writeByte(m.dutyMode());
            buf.writeVarInt(m.dutyChunkX());
            buf.writeVarInt(m.dutyChunkZ());
            buf.writeVarInt(m.route().length);
            for (long wp : m.route()) {
                buf.writeLong(wp);
            }
            buf.writeUtf(m.rankTitle(), 16);
            buf.writeFloat(m.health());
            buf.writeFloat(m.maxHealth());
            buf.writeByte(m.factionOrd());
            buf.writeVarInt(m.ammoRounds());
            buf.writeBoolean(m.ammoInfinite());
            buf.writeBoolean(m.hasFood());
            buf.writeBoolean(m.hasMedical());
            buf.writeByte(m.hunger());
            buf.writeByte(m.energy());
            buf.writeByte(m.morale());
        }
    }

    // Wire-supplied counts size allocations below - cap them like the
    // client->server packets so a bad packet can't force a huge allocation.
    private static final int MAX_MARKERS = 4096;

    public static PacketSyncUnitMarkers decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_MARKERS) {
            throw new io.netty.handler.codec.DecoderException("unit marker count " + count);
        }
        List<UnitMarker> markers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int entityId = buf.readVarInt();
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            String dimension = buf.readUtf(64);
            int rgb = buf.readInt();
            int classOrd = buf.readByte();
            UnitMarker.Allegiance allegiance =
                    UnitMarker.Allegiance.values()[buf.readByte()];
            float healthFrac = buf.readFloat();
            String name = buf.readUtf(32);
            int orderOrd = buf.readByte();
            int dutyMode = buf.readByte();
            int dutyChunkX = buf.readVarInt();
            int dutyChunkZ = buf.readVarInt();
            int routeLen = buf.readVarInt();
            // Routes max out at 24 waypoints (perimeter patrols) - allow
            // headroom, reject garbage.
            if (routeLen < 0 || routeLen > 64) {
                throw new io.netty.handler.codec.DecoderException("marker route length " + routeLen);
            }
            long[] route = new long[routeLen];
            for (int w = 0; w < route.length; w++) {
                route[w] = buf.readLong();
            }
            markers.add(new UnitMarker(
                    entityId, x, y, z, dimension, rgb, classOrd, allegiance,
                    healthFrac, name, orderOrd, dutyMode, dutyChunkX,
                    dutyChunkZ, route,
                    buf.readUtf(16), buf.readFloat(), buf.readFloat(),
                    buf.readByte(), buf.readVarInt(), buf.readBoolean(),
                    buf.readBoolean(), buf.readBoolean(),
                    buf.readByte(), buf.readByte(), buf.readByte()));
        }
        return new PacketSyncUnitMarkers(markers);
    }

    public static void handle(PacketSyncUnitMarkers msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.poseidon.sem_classsystem.client.ClientPacketHooks
                        .onUnitMarkers(msg)));
        ctx.get().setPacketHandled(true);
    }
}
