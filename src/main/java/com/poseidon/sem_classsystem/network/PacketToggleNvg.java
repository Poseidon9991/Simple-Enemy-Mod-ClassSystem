package com.poseidon.sem_classsystem.network;

import com.poseidon.sem_classsystem.compat.SemcsCurios;
import com.poseidon.sem_classsystem.item.NvgMode;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

// Client -> Server packet: toggles the player's worn head-mounted NVG active/standby
// state and updates Night Vision accordingly.
public record PacketToggleNvg(boolean enabled) {

    public static final String TAG_NVG_OFF = "semcs_nvg_off";
    public static final int NVG_EFFECT_DURATION = 260;

    public static void encode(PacketToggleNvg msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.enabled);
    }

    public static PacketToggleNvg decode(FriendlyByteBuf buf) {
        return new PacketToggleNvg(buf.readBoolean());
    }

    public static void handle(PacketToggleNvg msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || !player.isAlive()) {
                return;
            }
            player.getPersistentData().putBoolean(TAG_NVG_OFF, !msg.enabled);
            if (!ModList.get().isLoaded("curios")) {
                return;
            }
            NvgMode mode = SemcsCurios.getEquippedNvgMode(player);
            if (mode == NvgMode.NONE) {
                return;
            }
            if (msg.enabled) {
                player.addEffect(new MobEffectInstance(
                        MobEffects.NIGHT_VISION, NVG_EFFECT_DURATION, 0, true, false, true));
                player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.35F, 1.55F);
                player.displayClientMessage(
                        Component.translatable("message.sem_classsystem.nvg_on")
                                .withStyle(ChatFormatting.GREEN),
                        true);
            } else {
                MobEffectInstance existing = player.getEffect(MobEffects.NIGHT_VISION);
                if (existing != null && existing.getDuration() <= NVG_EFFECT_DURATION + 20) {
                    player.removeEffect(MobEffects.NIGHT_VISION);
                }
                player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.PLAYERS, 0.35F, 0.85F);
                player.displayClientMessage(
                        Component.translatable("message.sem_classsystem.nvg_off")
                                .withStyle(ChatFormatting.GRAY),
                        true);
            }
        });
        ctx.setPacketHandled(true);
    }

    @Mod.EventBusSubscriber(modid = com.poseidon.sem_classsystem.SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class ServerSyncEvents {
        @SubscribeEvent
        public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
            event.getEntity().getPersistentData().remove(TAG_NVG_OFF);
        }
    }
}
