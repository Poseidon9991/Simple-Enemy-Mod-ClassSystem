// Client.xaero package - hangs the unit renderer on Xaero's Minimap
package com.poseidon.sem_classsystem.client.xaero;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import xaero.common.HudMod;

// Soft compat with Xaero's Minimap. The element door is
// HudMod.INSTANCE.getMinimap().getOverMapRendererHandler().add(...) - the same
// shape as the world map's mapElementRenderHandler, no mixin needed.
// Registration is lazy: getMinimap() is null until Xaero finishes its own
// init, so a client tick hook waits for the handler to exist.
//
// Only reached when xaerominimap is present (ModList gate in the client
// setup).
public final class SemcsMinimapCompat {

    public static final String MODID = "xaerominimap";

    private static boolean hung;

    private SemcsMinimapCompat() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(SemcsMinimapCompat.class);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (hung || event.phase != TickEvent.Phase.END) {
            return;
        }
        if (HudMod.INSTANCE == null || HudMod.INSTANCE.getMinimap() == null
                || HudMod.INSTANCE.getMinimap().getOverMapRendererHandler() == null) {
            return;
        }
        HudMod.INSTANCE.getMinimap().getOverMapRendererHandler()
                .add(UnitMinimapElements.INSTANCE);
        hung = true;
    }
}
