package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.compat.SemcsCurios;
import com.poseidon.sem_classsystem.item.NvgMode;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

// Client-only NVG toggle state and soft-dep Curios query bridge.
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientNvgState {

    private static boolean enabled = true;

    private ClientNvgState() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean toggle() {
        enabled = !enabled;
        return enabled;
    }

    public static NvgMode getEquippedMode(Player player) {
        if (player == null || !ModList.get().isLoaded("curios")) {
            return NvgMode.NONE;
        }
        return SemcsCurios.getEquippedNvgMode(player);
    }

    public static NvgMode getActiveMode(Player player) {
        if (!enabled) {
            return NvgMode.NONE;
        }
        return getEquippedMode(player);
    }

    @SubscribeEvent
    public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        enabled = true;
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        enabled = true;
    }
}
