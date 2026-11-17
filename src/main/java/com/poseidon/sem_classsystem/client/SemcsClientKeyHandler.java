package com.poseidon.sem_classsystem.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.item.NvgMode;
import com.poseidon.sem_classsystem.network.PacketOpenWornVest;
import com.poseidon.sem_classsystem.network.PacketToggleNvg;
import com.poseidon.sem_classsystem.network.PacketToggleTanyaAbility;
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

    public static final KeyMapping KEY_TANYA_BARRIER = new KeyMapping(
            "key.sem_classsystem.tanya_barrier",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            "key.categories.sem_classsystem");

    public static final KeyMapping KEY_TANYA_SPELL_BULLETS = new KeyMapping(
            "key.sem_classsystem.tanya_spell_bullets",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_N,
            "key.categories.sem_classsystem");

    public static final KeyMapping KEY_TOGGLE_NVG = new KeyMapping(
            "key.sem_classsystem.toggle_nvg",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "key.categories.sem_classsystem");

    private SemcsClientKeyHandler() {
    }

    @Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ModBusEvents {
        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(KEY_OPEN_VEST);
            event.register(KEY_TANYA_BARRIER);
            event.register(KEY_TANYA_SPELL_BULLETS);
            event.register(KEY_TOGGLE_NVG);
        }
    }

    @Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class ForgeBusEvents {
        @SubscribeEvent
        public static void onKeyInput(InputEvent.Key event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.screen != null) {
                return;
            }
            if (KEY_OPEN_VEST.consumeClick()) {
                SemcsNetwork.CHANNEL.sendToServer(new PacketOpenWornVest());
            }
            if (KEY_TANYA_BARRIER.consumeClick()) {
                SemcsNetwork.CHANNEL.sendToServer(
                        new PacketToggleTanyaAbility(PacketToggleTanyaAbility.ABILITY_BARRIER));
            }
            if (KEY_TANYA_SPELL_BULLETS.consumeClick()) {
                SemcsNetwork.CHANNEL.sendToServer(
                        new PacketToggleTanyaAbility(PacketToggleTanyaAbility.ABILITY_SPELL_BULLETS));
            }
            if (KEY_TOGGLE_NVG.consumeClick()) {
                if (ClientNvgState.getEquippedMode(mc.player) != NvgMode.NONE) {
                    boolean next = ClientNvgState.toggle();
                    SemcsNetwork.CHANNEL.sendToServer(new PacketToggleNvg(next));
                }
            }
        }
    }
}
