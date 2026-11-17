package com.poseidon.sem_classsystem.network;

import com.poseidon.sem_classsystem.event.TanyaFlightEvents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// Client -> Server packet: toggles Type-95 Mana Barrier or Spell Sniping
// infusion while wearing the full Imperial Aerial Mage (Tanya) armor set.
public class PacketToggleTanyaAbility {

    public static final byte ABILITY_BARRIER = 0;
    public static final byte ABILITY_SPELL_BULLETS = 1;

    private final byte ability;

    public PacketToggleTanyaAbility(byte ability) {
        this.ability = ability;
    }

    public static void encode(PacketToggleTanyaAbility msg, FriendlyByteBuf buf) {
        buf.writeByte(msg.ability);
    }

    public static PacketToggleTanyaAbility decode(FriendlyByteBuf buf) {
        return new PacketToggleTanyaAbility(buf.readByte());
    }

    public static void handle(PacketToggleTanyaAbility msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || !player.isAlive()) {
                return;
            }
            if (msg.ability == ABILITY_BARRIER) {
                TanyaFlightEvents.toggleBarrier(player);
            } else if (msg.ability == ABILITY_SPELL_BULLETS) {
                TanyaFlightEvents.toggleSpellBullets(player);
            }
        });
        ctx.setPacketHandled(true);
    }
}
