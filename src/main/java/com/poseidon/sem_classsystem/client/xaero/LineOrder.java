// Client.xaero package - drag-out line MOVE orders on the world map
package com.poseidon.sem_classsystem.client.xaero;

import com.poseidon.sem_classsystem.client.UnitMarkerStore;
import com.poseidon.sem_classsystem.map.UnitMarker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.network.ModNetworking;
import net.nekoyuni.SimpleEnemyMod.network.packets.PacketIssueOrder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// A left-drag with >=2 units selected lays a line from A to B and files the
// selection along it, nearest-first. Dispatch rides SEM's own
// PacketIssueOrder exactly like the single-target MOVE menu entry.
//
// No Xaero type here on purpose - the drawing helpers take screen pixels
// MixinSemcsGuiMap has already projected, so this stays a plain
// GuiGraphics painter.
public final class LineOrder {

    private static final double DASH_LEN = 4.0;
    private static final double DASH_GAP = 4.0;

    private LineOrder() {
    }

    // Selected ids sorted by where each unit's marker projects onto A->B, so
    // the formation fills the line without units crossing over each other.
    public static List<Integer> sortedIds(Vec3 a, Vec3 b) {
        Vec3 axis = b.subtract(a);
        Map<Integer, Vec3> pos = new HashMap<>();
        for (UnitMarker m : UnitMarkerStore.markers()) {
            pos.put(m.entityId(), new Vec3(m.x(), m.y(), m.z()));
        }
        List<Integer> ids = new ArrayList<>(UnitMarkerStore.selectedUnitIds());
        ids.sort(Comparator.comparingDouble(id -> {
            Vec3 p = pos.get(id);
            return p == null ? 0.0D : (p.x - a.x) * axis.x + (p.z - a.z) * axis.z;
        }));
        return ids;
    }

    // Slot i of n on A->B, endpoints included - for a straight segment this
    // uniform t is the arc-length parameterization, so units land evenly.
    public static Vec3 slot(Vec3 a, Vec3 b, int i, int n) {
        double t = n <= 1 ? 0.5D : (double) i / (n - 1);
        return new Vec3(Mth.lerp(t, a.x, b.x), Mth.lerp(t, a.y, b.y),
                Mth.lerp(t, a.z, b.z));
    }

    // One MOVE_TO_POSITION per selected unit at its slot; then drop the
    // selection like the MOVE menu entry does.
    public static void dispatch(Vec3 a, Vec3 b) {
        List<Integer> ids = sortedIds(a, b);
        if (ids.size() < 2) {
            return;
        }
        for (int i = 0; i < ids.size(); i++) {
            ModNetworking.CHANNEL.sendToServer(new PacketIssueOrder(
                    ids.get(i), OrderType.MOVE_TO_POSITION,
                    slot(a, b, i, ids.size()), 0, -1));
        }
        UnitMarkerStore.clearSelection();
        hint("message.sem_classsystem.map.line_order", ids.size());
    }

    // A dashed screen-space line - the drag preview and duty lines both use it.
    public static void dashedLine(GuiGraphics g, double x1, double y1,
                                  double x2, double y2, int argb) {
        double dx = x2 - x1, dy = y2 - y1;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0) {
            return;
        }
        double ux = dx / len, uy = dy / len;
        double period = DASH_LEN + DASH_GAP;
        double march = (System.currentTimeMillis() / 1000.0 * 8.0) % period;
        for (double d = -march; d < len; d += period) {
            double s = Math.max(0.0D, d);
            double e = Math.min(len, d + DASH_LEN);
            for (double t = s; t < e; t += 2.0) {
                int px = (int) Math.round(x1 + ux * t);
                int py = (int) Math.round(y1 + uy * t);
                g.fill(px - 1, py - 1, px + 1, py + 1, argb);
            }
        }
    }

    // A small filled pip - a route node or a unit's line-order destination.
    public static void node(GuiGraphics g, int x, int y, int argb) {
        g.fill(x - 3, y - 3, x + 3, y + 3, 0x60000000);
        g.fill(x - 2, y - 2, x + 2, y + 2, argb);
    }

    private static void hint(String key, Object... args) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(key, args)
                    .withStyle(ChatFormatting.GREEN), true);
        }
    }
}
