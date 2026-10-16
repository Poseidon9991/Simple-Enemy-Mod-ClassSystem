// Network package - client->server: claim/unclaim a map chunk
package com.poseidon.sem_classsystem.network;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.entity.ai.UnitChunkDutyGoal;
import com.poseidon.sem_classsystem.world.PlayerChunkClaims;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// Right-click menu "Claim chunk" / "Unclaim chunk". Claims are written into
// the overworld SavedData keyed by dimension string so they persist.
public class PacketClaimChunk {

    private final String dimension;
    private final int chunkX;
    private final int chunkZ;
    private final boolean claim;    // false = unclaim

    public PacketClaimChunk(String dimension, int chunkX, int chunkZ, boolean claim) {
        this.dimension = dimension;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.claim = claim;
    }

    public static void encode(PacketClaimChunk msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.dimension, 64);
        buf.writeVarInt(msg.chunkX);
        buf.writeVarInt(msg.chunkZ);
        buf.writeBoolean(msg.claim);
    }

    public static PacketClaimChunk decode(FriendlyByteBuf buf) {
        return new PacketClaimChunk(buf.readUtf(64), buf.readVarInt(),
                buf.readVarInt(), buf.readBoolean());
    }

    public static void handle(PacketClaimChunk msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sender = ctx.get().getSender();
            if (sender == null) {
                return;
            }
            ServerLevel overworld = sender.getServer().overworld();
            PlayerChunkClaims claims = PlayerChunkClaims.get(overworld);

            if (msg.claim) {
                int used = claims.claimCount(sender.getUUID(), msg.dimension);
                int cap = ClassSystemConfig.maxClaimedChunks();
                PlayerChunkClaims.ClaimResult result =
                        claims.claim(sender.getUUID(), msg.dimension, msg.chunkX, msg.chunkZ,
                                cap, ClassSystemConfig.requireContiguousClaims());
                switch (result) {
                    case OK -> tell(sender, "Chunk claimed [" + msg.chunkX + ", " + msg.chunkZ
                            + "] (" + (used + 1) + "/" + cap + ")", ChatFormatting.GREEN);
                    case ALREADY_MINE ->
                            tell(sender, "You already own that chunk.", ChatFormatting.YELLOW);
                    case TAKEN ->
                            tell(sender, "That chunk is claimed by another player.", ChatFormatting.RED);
                    case FULL ->
                            tell(sender, "Claim limit reached (" + cap + " chunks).", ChatFormatting.RED);
                    case NOT_ADJACENT ->
                            tell(sender, "New claims must border one of your claimed chunks.",
                                    ChatFormatting.RED);
                }
            } else {
                boolean removed = claims.unclaim(sender.getUUID(), msg.dimension,
                        msg.chunkX, msg.chunkZ);
                if (removed) {
                    // Units posted to or routed through the released chunk
                    // lose their duty - the claim backing it is gone.
                    UnitChunkDutyGoal.clearDutyInChunk(sender.getServer(),
                            sender.getUUID(), msg.dimension, msg.chunkX, msg.chunkZ);
                }
                tell(sender, removed ? "Chunk released." : "That chunk is not yours.",
                        removed ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void tell(ServerPlayer player, String text, ChatFormatting color) {
        player.displayClientMessage(Component.literal(text).withStyle(color), true);
    }
}
