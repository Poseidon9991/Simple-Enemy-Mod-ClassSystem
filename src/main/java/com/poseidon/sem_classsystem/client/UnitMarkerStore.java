// Client package - the player's picture of units + claims, as last synced
package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.map.UnitMarker;
import com.poseidon.sem_classsystem.network.PacketSyncClaims;
import net.minecraft.world.level.ChunkPos;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Deliberately free of any Xaero type: packet handlers write here and must
// classload on an install with no map mod. Everything Xaero-shaped lives in
// client.xaero.
//
// Markers arrive as a wholesale replacement per sync (see
// PacketSyncUnitMarkers); selection is keyed on entity id so a despawned
// unit quietly drops out of the set on the next accept.
public final class UnitMarkerStore {

    private static List<UnitMarker> markers = List.of();
    private static final Map<Integer, UnitMarker> BY_ID = new HashMap<>();
    private static final Set<Integer> SELECTED = new HashSet<>();

    // dimension -> owner -> chunk longs, from PacketSyncClaims
    private static String claimsDimension = "";
    private static final Map<UUID, Set<Long>> CLAIMS = new HashMap<>();

    // Night raid ping (PacketRaidPing): dimension + centre + the client
    // level game time it stops being drawn at. pingExpiry -1 = no ping.
    private static String pingDimension = "";
    private static double pingX;
    private static double pingZ;
    private static long pingExpiry = -1;

    private UnitMarkerStore() {
    }

    // ---- markers ------------------------------------------------------------

    public static void acceptMarkers(List<UnitMarker> incoming) {
        markers = List.copyOf(incoming);
        BY_ID.clear();
        for (UnitMarker m : markers) {
            BY_ID.put(m.entityId(), m);
        }
        SELECTED.removeIf(id -> !BY_ID.containsKey(id));
    }

    public static List<UnitMarker> markers() {
        return markers;
    }

    @Nullable
    public static UnitMarker marker(int entityId) {
        return BY_ID.get(entityId);
    }

    // ---- selection ------------------------------------------------------------

    public static boolean isSelected(UnitMarker marker) {
        return SELECTED.contains(marker.entityId());
    }

    // OWN toggles as a commandable pick; HOSTILE toggles as an attack
    // designate; FRIENDLY clicks do nothing. Returns whether the click was
    // meaningful so the caller can swallow it.
    public static boolean toggleSelected(UnitMarker marker) {
        if (marker.allegiance() == UnitMarker.Allegiance.FRIENDLY) {
            return false;
        }
        if (!SELECTED.remove(marker.entityId())) {
            SELECTED.add(marker.entityId());
        }
        return true;
    }

    // Box select ADDS own units only - hostiles get designated by click.
    public static void addSelected(UnitMarker marker) {
        if (marker.allegiance() == UnitMarker.Allegiance.OWN) {
            SELECTED.add(marker.entityId());
        }
    }

    public static void clearSelection() {
        SELECTED.clear();
    }

    // Live entity ids of selected OWN units - what order packets iterate.
    public static Set<Integer> selectedUnitIds() {
        Set<Integer> ids = new HashSet<>();
        for (int id : SELECTED) {
            UnitMarker m = BY_ID.get(id);
            if (m != null && m.allegiance() == UnitMarker.Allegiance.OWN) {
                ids.add(id);
            }
        }
        return ids;
    }

    // Entity id of the designated HOSTILE when exactly one is selected, -1 else.
    public static int selectedHostileTargetId() {
        int id = -1;
        int count = 0;
        for (int sel : SELECTED) {
            UnitMarker m = BY_ID.get(sel);
            if (m == null || m.allegiance() != UnitMarker.Allegiance.HOSTILE) {
                continue;
            }
            count++;
            id = sel;
            if (count > 1) {
                return -1;
            }
        }
        return count == 1 ? id : -1;
    }

    // ---- claims -----------------------------------------------------------

    public static void acceptClaims(String dimension, List<PacketSyncClaims.ClaimEntry> entries) {
        claimsDimension = dimension;
        CLAIMS.clear();
        for (PacketSyncClaims.ClaimEntry e : entries) {
            CLAIMS.computeIfAbsent(e.owner(), o -> new HashSet<>())
                    .add(ChunkPos.asLong(e.chunkX(), e.chunkZ()));
        }
    }

    public static String claimsDimension() {
        return claimsDimension;
    }

    public static Map<UUID, Set<Long>> claims() {
        return CLAIMS;
    }

    @Nullable
    public static UUID claimOwner(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        for (Map.Entry<UUID, Set<Long>> e : CLAIMS.entrySet()) {
            if (e.getValue().contains(key)) {
                return e.getKey();
            }
        }
        return null;
    }

    public static boolean isClaimedBy(UUID owner, int chunkX, int chunkZ) {
        Set<Long> mine = CLAIMS.get(owner);
        return mine != null && mine.contains(ChunkPos.asLong(chunkX, chunkZ));
    }

    public static int claimCount(UUID owner) {
        Set<Long> mine = CLAIMS.get(owner);
        return mine == null ? 0 : mine.size();
    }

    // ---- raid ping ----------------------------------------------------------

    public static void acceptRaidPing(String dimension, double x, double z,
                                      long expiryGameTime) {
        pingDimension = dimension;
        pingX = x;
        pingZ = z;
        pingExpiry = expiryGameTime;
    }

    // The live ping, or null when none/expired - the caller passes the client
    // level's game time so expiry rides world ticks, not wall clock.
    @Nullable
    public static double[] raidPing(String dimension, long gameTime) {
        if (pingExpiry < 0 || gameTime >= pingExpiry
                || !pingDimension.equals(dimension)) {
            return null;
        }
        return new double[]{pingX, pingZ};
    }

    // ---- session teardown ---------------------------------------------------

    public static void clear() {
        markers = List.of();
        BY_ID.clear();
        SELECTED.clear();
        claimsDimension = "";
        CLAIMS.clear();
        pingDimension = "";
        pingExpiry = -1;
    }
}
