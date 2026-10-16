// Client package - packet landing pads, kept Xaero-free on purpose
package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.network.PacketOpenGuardPost;
import com.poseidon.sem_classsystem.network.PacketRaidPing;
import com.poseidon.sem_classsystem.network.PacketSyncClaims;
import com.poseidon.sem_classsystem.network.PacketSyncUnitMarkers;
import net.minecraft.client.Minecraft;

// The S2C packet handlers bounce through DistExecutor into here so the
// packet classes never name client types. Same rule as UnitMarkerStore:
// nothing in this file may reference xaero.* - it loads on every install.
public final class ClientPacketHooks {

    private ClientPacketHooks() {
    }

    public static void onUnitMarkers(PacketSyncUnitMarkers msg) {
        UnitMarkerStore.acceptMarkers(msg.markers());
    }

    public static void onClaims(PacketSyncClaims msg) {
        UnitMarkerStore.acceptClaims(msg.dimension(), msg.claims());
    }

    // FieldTabletItem.use() -> DistExecutor -> here: boot the SEM-OS desktop.
    public static void openTablet() {
        Minecraft.getInstance().setScreen(new TabletScreen());
    }

    // GuardPostBlock.use() -> server snapshot -> here.
    public static void openGuardPost(PacketOpenGuardPost msg) {
        Minecraft.getInstance().setScreen(new GuardPostScreen(msg));
    }

    // ~2 minutes of game time (2400 ticks) from receipt, riding client level
    // ticks so a pause or slow TPS does not stretch the ping.
    public static void onRaidPing(PacketRaidPing msg) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        UnitMarkerStore.acceptRaidPing(msg.dimension(), msg.x(), msg.z(),
                Minecraft.getInstance().level.getGameTime() + 2400L);
    }

    // Casualty board snapshot for the INTEL app.
    public static void onCasualties(java.util.List<String> lines) {
        UnitMarkerStore.acceptCasualties(lines);
    }
}
