package com.poseidon.sem_classsystem.network;

import com.poseidon.sem_classsystem.client.ClientPacketHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// Server -> Client packet: synchronizes a player's Type-95 Mana Barrier,
// Spell Sniping toggle, current mana reserve, and barrier impact flash pulse.
public record PacketSyncTanyaState(
        int entityId,
        boolean barrierActive,
        boolean infusionActive,
        int manaTicks,
        int maxManaTicks,
        boolean hitFlash
) {

    public static void encode(PacketSyncTanyaState msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.entityId);
        buf.writeBoolean(msg.barrierActive);
        buf.writeBoolean(msg.infusionActive);
        buf.writeVarInt(msg.manaTicks);
        buf.writeVarInt(msg.maxManaTicks);
        buf.writeBoolean(msg.hitFlash);
    }

    public static PacketSyncTanyaState decode(FriendlyByteBuf buf) {
        int entityId = buf.readInt();
        boolean barrierActive = buf.readBoolean();
        boolean infusionActive = buf.readBoolean();
        int manaTicks = Math.max(0, buf.readVarInt());
        int maxManaTicks = Math.max(1, buf.readVarInt());
        boolean hitFlash = buf.readBoolean();
        return new PacketSyncTanyaState(entityId, barrierActive, infusionActive, manaTicks, maxManaTicks, hitFlash);
    }

    public static void handle(PacketSyncTanyaState msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHooks.onTanyaState(msg)));
        ctx.setPacketHandled(true);
    }
}
