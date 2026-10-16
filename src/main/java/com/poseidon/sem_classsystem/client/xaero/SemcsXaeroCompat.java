// Client.xaero package - hangs the unit renderer on Xaero's element handler
package com.poseidon.sem_classsystem.client.xaero;

import com.poseidon.sem_classsystem.client.UnitMarkerStore;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import xaero.map.WorldMap;
import xaero.map.gui.GuiMap;

// Soft compat with Xaero's World Map. WorldMap.mapElementRenderHandler is a
// public static field and add() is public - the same door Xaero's own
// waypoint renderer goes through. Registration happens on the first map
// screen instead of in setup because Xaero builds that handler in its own
// deferred work with no ordering guarantee against ours.
//
// Only reached when xaeroworldmap is present (ModList gate in the client
// setup); the mixin's side is gated by XaeroSemcsMixinPlugin.
public final class SemcsXaeroCompat {

    public static final String MODID = "xaeroworldmap";

    // The handler we last hung on - Xaero can rebuild it between worlds, so
    // track the instance rather than a boolean to re-add without stacking.
    private static Object hungOn;

    private SemcsXaeroCompat() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(SemcsXaeroCompat.class);
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof GuiMap)) {
            return;
        }
        if (WorldMap.mapElementRenderHandler == null
                || hungOn == WorldMap.mapElementRenderHandler) {
            return;
        }
        WorldMap.mapElementRenderHandler.add(UnitMarkerElements.INSTANCE);
        hungOn = WorldMap.mapElementRenderHandler;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        UnitMarkerStore.clear();
        RoutePlot.cancel();   // a half-drawn route must not leak across worlds
        hungOn = null;   // force a re-hang on the next world's handler
    }
}
