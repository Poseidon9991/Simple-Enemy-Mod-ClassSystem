// Client-only - synchronized Type-95 Imperial Aerial Mage barrier & spell-bullet state.
package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.SemClassSystem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientTanyaState {

    public static final class Entry {
        public boolean barrierActive;
        public boolean infusionActive;
        public int manaTicks;
        public int maxManaTicks;
        public long lastHitTimeMs;
    }

    private static final Map<Integer, Entry> STATES = new ConcurrentHashMap<>();

    private ClientTanyaState() {
    }

    public static void update(int entityId, boolean barrierActive, boolean infusionActive,
                              int manaTicks, int maxManaTicks, boolean hitFlash) {
        Entry e = STATES.computeIfAbsent(entityId, id -> new Entry());
        e.barrierActive = barrierActive;
        e.infusionActive = infusionActive;
        e.manaTicks = Math.max(0, manaTicks);
        e.maxManaTicks = Math.max(1, maxManaTicks);
        if (hitFlash) {
            e.lastHitTimeMs = System.currentTimeMillis();
        }
    }

    public static Entry get(int entityId) {
        return STATES.get(entityId);
    }

    public static boolean isBarrierActive(int entityId) {
        Entry e = STATES.get(entityId);
        return e != null && e.barrierActive && e.manaTicks > 0;
    }

    public static boolean isInfusionActive(int entityId) {
        Entry e = STATES.get(entityId);
        return e != null && e.infusionActive && e.manaTicks > 0;
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        STATES.clear();
    }
}
