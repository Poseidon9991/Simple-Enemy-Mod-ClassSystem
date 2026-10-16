// Client.xaero package - reads the player's Xaero waypoints for the tablet's
// teleport app. Only loaded through ModList-gated calls, mirroring how
// SemcsXaeroCompat gates the map element renderer.
package com.poseidon.sem_classsystem.client.xaero;

import net.minecraftforge.fml.ModList;
import xaero.common.XaeroMinimapSession;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.common.minimap.waypoints.WaypointSet;
import xaero.common.minimap.waypoints.WaypointWorld;
import xaero.common.minimap.waypoints.WaypointsManager;

import java.util.ArrayList;
import java.util.List;

public final class SemcsWaypoints {

    private SemcsWaypoints() {
    }

    public static boolean available() {
        return ModList.get().isLoaded("xaerominimap");
    }

    public record WaypointEntry(String name, int x, int y, int z) {
    }

    // Waypoints of the world + set the player is currently in. Empty when
    // Xaero's minimap is absent or no session exists yet.
    public static List<WaypointEntry> current() {
        if (!available()) {
            return List.of();
        }
        return read();
    }

    private static List<WaypointEntry> read() {
        XaeroMinimapSession session = XaeroMinimapSession.getCurrentSession();
        if (session == null) {
            return List.of();
        }
        WaypointsManager manager = session.getWaypointsManager();
        if (manager == null) {
            return List.of();
        }
        WaypointWorld world = manager.getCurrentWorld();
        WaypointSet set = world == null ? null : world.getCurrentSet();
        if (set == null) {
            return List.of();
        }
        List<WaypointEntry> out = new ArrayList<>();
        for (Waypoint w : set.getList()) {
            out.add(new WaypointEntry(
                    w.getLocalizedName() == null ? w.getName() : w.getLocalizedName(),
                    w.getX(), w.getY(), w.getZ()));
        }
        return out;
    }
}
