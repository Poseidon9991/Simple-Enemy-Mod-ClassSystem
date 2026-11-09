package com.poseidon.sem_classsystem.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.network.PacketOpenWornVest;
import com.poseidon.sem_classsystem.network.SemcsNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

// Client keybinding handler for opening the tactical vest / armor plate GUI.
// Default key is 'V' (standard tactical vest / plate carrier hotkey).
public final class SemcsClientKeyHandler {

    public static final KeyMapping KEY_OPEN_VEST = new KeyMapping(
            "key.sem_classsystem.open_vest",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            "key.categories.sem_classsystem");

    private SemcsClientKeyHandler() {
    }

    @Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ModBusEvents {
        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(KEY_OPEN_VEST);
        }
    }

    @Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class ForgeBusEvents {
        @SubscribeEvent
        public static void onKeyInput(InputEvent.Key event) {
            if (KEY_OPEN_VEST.consumeClick()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && mc.screen == null) {
                    SemcsNetwork.CHANNEL.sendToServer(new PacketOpenWornVest());
                }
            }
        }
    }
}
