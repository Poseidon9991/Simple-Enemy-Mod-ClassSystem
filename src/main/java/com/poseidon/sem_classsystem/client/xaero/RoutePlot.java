// Client.xaero package - route plotting mode on the world map
package com.poseidon.sem_classsystem.client.xaero;

import com.poseidon.sem_classsystem.client.UnitMarkerStore;
import com.poseidon.sem_classsystem.network.PacketAssignRoute;
import com.poseidon.sem_classsystem.network.SemcsNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

// While armed, clicks on the map lay out a patrol route instead of selecting:
// left adds a node (max 16, must sit in a chunk the local player claims),
// right undoes the last one, ENTER/Confirm sends it, ESC/Cancel drops it.
//
// Client-only and deliberately dumb - a node list plus the unit ids and the
// map dimension captured at arm time, so a marker sync mid-plot cannot quietly
// change who the route is for. Everything that acts on it lives in
// MixinSemcsGuiMap (clicks, drawing, the two buttons).
public final class RoutePlot {

    private static final List<BlockPos> NODES = new ArrayList<>();
    private static List<Integer> units = List.of();
    private static String dimension = "";
    private static boolean armed;

    private RoutePlot() {
    }

    // Enter plotting for the current selection. No selection, no mode.
    public static boolean arm(String mapDimension) {
        List<Integer> sel = new ArrayList<>(UnitMarkerStore.selectedUnitIds());
        if (sel.isEmpty()) {
            return false;
        }
        units = sel;
        dimension = mapDimension;
        NODES.clear();
        armed = true;
        return true;
    }

    public static boolean armed() {
        return armed;
    }

    public static List<BlockPos> nodes() {
        return NODES;
    }

    public static String dimension() {
        return dimension;
    }

    // Left-click add: refused with a hint when the node's chunk is not one of
    // the local player's claims (server re-checks the same rule).
    public static boolean add(BlockPos node) {
        if (NODES.size() >= PacketAssignRoute.MAX_WAYPOINTS) {
            hint("message.sem_classsystem.map.route_full", PacketAssignRoute.MAX_WAYPOINTS);
            return false;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null
                || !UnitMarkerStore.isClaimedBy(player.getUUID(),
                        node.getX() >> 4, node.getZ() >> 4)) {
            hint("message.sem_classsystem.map.route_unclaimed");
            return false;
        }
        NODES.add(node);
        return true;
    }

    // Right-click undo: drops the last node laid.
    public static void removeLast() {
        if (!NODES.isEmpty()) {
            NODES.remove(NODES.size() - 1);
        }
    }

    public static void cancel() {
        armed = false;
        units = List.of();
        dimension = "";
        NODES.clear();
    }

    public static boolean canConfirm() {
        return NODES.size() >= 2;
    }

    // Send the plotted route to the captured units. Answers nodes sent.
    public static int confirm() {
        int count = NODES.size();
        if (count >= 2 && !units.isEmpty()) {
            long[] waypoints = new long[count];
            for (int i = 0; i < count; i++) {
                waypoints[i] = NODES.get(i).asLong();
            }
            SemcsNetwork.CHANNEL.sendToServer(
                    new PacketAssignRoute(units, dimension, waypoints));
            hint("message.sem_classsystem.map.route_sent", units.size(), count);
        }
        cancel();
        return count;
    }

    private static void hint(String key, Object... args) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(key, args)
                    .withStyle(ChatFormatting.YELLOW), true);
        }
    }
}
