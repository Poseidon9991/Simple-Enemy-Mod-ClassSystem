// Client package - SEM-OS desktop shown by the Field Tablet item
package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.entity.ai.UnitChunkDutyGoal;
import com.poseidon.sem_classsystem.faction.UnitClass;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.map.UnitMarker;
import com.poseidon.sem_classsystem.network.PacketTeleportUnits;
import com.poseidon.sem_classsystem.network.SemcsNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// A pocket OS: dark desktop, a taskbar, app icons. The Squad app is the
// roster the Field Tablet exists for - every owned unit (any dimension)
// with health, ammo reserve (or an infinity mark when the unit carries a
// creative/all-type TaCZ ammo box), class, and food/medical supply chips.
// Clicking a row toggles map selection so the roster and world map agree.
// All numbers arrive on the wire in UnitMarker; the client never scans
// entity inventories itself.
public class TabletScreen extends Screen {

    private enum App {SQUAD, TELEPORT, INTEL}

    private static final int TASKBAR_H = 20;
    private static final int ICON_SIZE = 30;
    private static final int ROW_H = 15;

    private App openApp = null;
    private int scroll = 0;

    // Window geometry, refreshed each frame so hit tests use what was drawn.
    private int winX, winY, winW, winH;

    // Teleport app click zones, rebuilt during render:
    //   positive code = waypoint index, -1 = "to me", -2 = "to crosshair"
    private final List<int[]> tpTargets = new ArrayList<>();
    private List<com.poseidon.sem_classsystem.client.xaero.SemcsWaypoints.WaypointEntry>
            waypoints = List.of();
    private int rosterViewTop = 0;
    private int rosterViewH = 0;

    public TabletScreen() {
        super(Component.literal("SEM-OS"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- rendering ---------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        // Wallpaper - dark slate gradient with a faint survey grid.
        g.fillGradient(0, 0, this.width, this.height, 0xFF0C1420, 0xFF182433);
        for (int x = 0; x < this.width; x += 40) {
            g.fill(x, TASKBAR_H, x + 1, this.height, 0x10FFFFFF);
        }
        for (int y = TASKBAR_H; y < this.height; y += 40) {
            g.fill(0, y, this.width, y + 1, 0x10FFFFFF);
        }

        renderTaskbar(g);
        renderIcons(g, mouseX, mouseY);
        if (this.openApp == App.SQUAD) {
            renderSquad(g, mouseX, mouseY);
        } else if (this.openApp == App.TELEPORT) {
            renderTeleport(g, mouseX, mouseY);
        } else if (this.openApp == App.INTEL) {
            renderIntel(g, mouseX, mouseY);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderTaskbar(GuiGraphics g) {
        g.fill(0, 0, this.width, TASKBAR_H, 0xFF0A0E14);
        g.drawString(this.font, "SEM-OS", 6, 6, 0xFF6FE3C1, false);
        Minecraft mc = Minecraft.getInstance();
        String right = mc.player == null ? "" : mc.player.getGameProfile().getName();
        if (mc.level != null) {
            long t = (mc.level.getDayTime() + 6000L) % 24000L;
            right = String.format("%02d:%02d  %s", t / 1000, (t % 1000) / 17, right);
        }
        g.drawString(this.font, right,
                this.width - 6 - this.font.width(right), 6, 0xFF8FA3B8, false);
    }

    private void renderIcons(GuiGraphics g, int mouseX, int mouseY) {
        int cx = this.width / 2;
        int cy = this.height / 2 - 30;
        drawAppIcon(g, cx - 103, cy, mouseX, mouseY, 0xFF2E7D46, "Squad", () -> {
            // three squad chevrons
            g.fill(cx - 103 + 8, cy + 8, cx - 103 + 12, cy + 22, 0xFFDFF5E8);
            g.fill(cx - 103 + 13, cy + 8, cx - 103 + 17, cy + 22, 0xFFB8E4C8);
            g.fill(cx - 103 + 18, cy + 8, cx - 103 + 22, cy + 22, 0xFF8FD3AC);
        });
        drawAppIcon(g, cx - 15, cy, mouseX, mouseY, 0xFF5B3A8E, "Teleport", () -> {
            // portal swirl: diamond ring + dot
            int ix = cx - 15;
            g.fill(ix + 13, cy + 7, ix + 17, cy + 11, 0xFFD8C8FF);
            g.fill(ix + 9, cy + 11, ix + 13, cy + 15, 0xFFB89AF0);
            g.fill(ix + 17, cy + 11, ix + 21, cy + 15, 0xFFB89AF0);
            g.fill(ix + 13, cy + 15, ix + 17, cy + 19, 0xFFD8C8FF);
            g.fill(ix + 13, cy + 11, ix + 17, cy + 15, 0xFFF4ECFF);
        });
        drawAppIcon(g, cx + 73, cy, mouseX, mouseY, 0xFF8A6A24, "Intel", () -> {
            // signal bars
            g.fill(cx + 73 + 6, cy + 16, cx + 73 + 10, cy + 22, 0xFFF5E6C0);
            g.fill(cx + 73 + 12, cy + 12, cx + 73 + 16, cy + 22, 0xFFF5E6C0);
            g.fill(cx + 73 + 18, cy + 8, cx + 73 + 22, cy + 22, 0xFFF5E6C0);
        });
    }

    private void drawAppIcon(GuiGraphics g, int x, int y, int mouseX, int mouseY,
                             int color, String label, Runnable glyph) {
        boolean hover = mouseX >= x && mouseX < x + ICON_SIZE
                && mouseY >= y && mouseY < y + ICON_SIZE;
        g.fill(x - 1, y - 1, x + ICON_SIZE + 1, y + ICON_SIZE + 1,
                hover ? 0xFFFFFFFF : 0x66000000);
        g.fill(x, y, x + ICON_SIZE, y + ICON_SIZE, color);
        glyph.run();
        g.drawCenteredString(this.font, label,
                x + ICON_SIZE / 2, y + ICON_SIZE + 4,
                hover ? 0xFFFFFFFF : 0xFFB9C6D4);
    }

    // Window chrome shared by both apps; returns content origin via fields.
    private void openWindow(GuiGraphics g, String title) {
        this.winW = Math.min(560, this.width - 50);
        this.winH = Math.min(300, this.height - 70);
        this.winX = (this.width - this.winW) / 2;
        this.winY = (this.height - this.winH) / 2 + 8;
        g.fill(this.winX - 1, this.winY - 1, this.winX + this.winW + 1,
                this.winY + this.winH + 1, 0xFF3D4E63);
        g.fill(this.winX, this.winY, this.winX + this.winW,
                this.winY + this.winH, 0xFF141C28);
        // title bar
        g.fill(this.winX, this.winY, this.winX + this.winW, this.winY + 18, 0xFF1D2A3A);
        g.drawString(this.font, title, this.winX + 8, this.winY + 5, 0xFF6FE3C1, false);
        // close button
        int bx = this.winX + this.winW - 16;
        g.fill(bx, this.winY + 4, bx + 12, this.winY + 14, 0xFF8A2F2F);
        g.drawCenteredString(this.font, "x", bx + 6, this.winY + 6, 0xFFFFFFFF);
    }

    private List<UnitMarker> ownedUnits() {
        List<UnitMarker> own = new ArrayList<>();
        for (UnitMarker m : UnitMarkerStore.markers()) {
            if (m.allegiance() == UnitMarker.Allegiance.OWN) {
                own.add(m);
            }
        }
        own.sort(Comparator.comparing(UnitMarker::name)
                .thenComparingInt(UnitMarker::entityId));
        return own;
    }

    private void renderSquad(GuiGraphics g, int mouseX, int mouseY) {
        List<UnitMarker> units = ownedUnits();
        openWindow(g, "Squad Roster - " + units.size() + " unit(s)");

        int cx = this.winX + 10;
        int cy = this.winY + 26;
        int cw = this.winW - 20;
        // column layout
        int colName = cx;
        int colClass = cx + 118;
        int colHp = cx + 182;
        int colAmmo = cx + 268;
        int colMed = cx + 316;
        int colFood = cx + 348;
        int colStatus = cx + 386;
        int colNeeds = cx + 430;

        g.drawString(this.font, "CALLSIGN", colName, cy, 0xFF5E7086, false);
        g.drawString(this.font, "CLASS", colClass, cy, 0xFF5E7086, false);
        g.drawString(this.font, "HP", colHp, cy, 0xFF5E7086, false);
        g.drawString(this.font, "AMMO", colAmmo, cy, 0xFF5E7086, false);
        g.drawString(this.font, "MED", colMed, cy, 0xFF5E7086, false);
        g.drawString(this.font, "FOOD", colFood, cy, 0xFF5E7086, false);
        g.drawString(this.font, "STATUS", colStatus, cy, 0xFF5E7086, false);
        g.drawString(this.font, "NEEDS", colNeeds, cy, 0xFF5E7086, false);
        cy += 11;

        int viewTop = cy;
        int viewH = this.winY + this.winH - 10 - viewTop;
        this.rosterViewTop = viewTop;
        this.rosterViewH = viewH;
        int contentH = units.size() * ROW_H;
        int maxScroll = Math.max(0, contentH - viewH);
        this.scroll = Math.min(this.scroll, maxScroll);

        g.enableScissor(cx - 2, viewTop, cx + cw + 2, viewTop + viewH);
        int ry = viewTop - this.scroll;
        for (UnitMarker m : units) {
            if (ry + ROW_H > viewTop && ry < viewTop + viewH) {
                boolean hover = mouseX >= cx && mouseX < cx + cw
                        && mouseY >= ry && mouseY < ry + ROW_H
                        && mouseY >= viewTop && mouseY < viewTop + viewH;
                if (hover) {
                    g.fill(cx - 2, ry, cx + cw, ry + ROW_H - 1, 0xFF22344A);
                }
                if (UnitMarkerStore.isSelected(m)) {
                    g.fill(cx - 2, ry, cx, ry + ROW_H - 1, 0xFF6FE3C1);
                }
                String name = (m.rankTitle().isEmpty() ? "" : m.rankTitle() + " ")
                        + (m.name().isEmpty() ? "Unit #" + m.entityId() : m.name());
                g.drawString(this.font, this.font.plainSubstrByWidth(name, 112),
                        colName, ry + 4, 0xFFE8F0F8, false);
                g.drawString(this.font, classLabel(m), colClass, ry + 4,
                        0xFFB9C6D4, false);
                renderHp(g, m, colHp, ry + 4);
                if (m.ammoInfinite()) {
                    g.drawString(this.font, "INF", colAmmo, ry + 4, 0xFF6FE3C1, false);
                } else {
                    g.drawString(this.font, String.valueOf(m.ammoRounds()),
                            colAmmo, ry + 4,
                            m.ammoRounds() > 0 ? 0xFFE8F0F8 : 0xFF8A4A4A, false);
                }
                g.drawString(this.font, m.hasMedical() ? "+" : "-", colMed,
                        ry + 4, m.hasMedical() ? 0xFF6FE3C1 : 0xFF4A5563, false);
                g.drawString(this.font, m.hasFood() ? "*" : "-", colFood,
                        ry + 4, m.hasFood() ? 0xFFE3B86F : 0xFF4A5563, false);
                g.drawString(this.font, statusLabel(m), colStatus, ry + 4,
                        m.dutyMode() != 0 ? 0xFFE3B86F : 0xFF8FA3B8, false);
                renderNeeds(g, m, colNeeds, ry + 4);
            }
            ry += ROW_H;
        }
        g.disableScissor();

        if (units.isEmpty()) {
            g.drawCenteredString(this.font,
                    "No units in this dimension - recruit or regroup here.",
                    this.winX + this.winW / 2, viewTop + viewH / 2, 0xFF5E7086);
        }
        if (maxScroll > 0) {
            int trackX = this.winX + this.winW - 6;
            g.fill(trackX, viewTop, trackX + 3, viewTop + viewH, 0xFF0A0E14);
            int thumbH = Math.max(12, viewH * viewH / contentH);
            int thumbY = viewTop + (viewH - thumbH) * this.scroll / maxScroll;
            g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFF6FE3C1);
        }
    }

    private void renderHp(GuiGraphics g, UnitMarker m, int x, int y) {
        int w = 40;
        int filled = Math.round(m.healthFrac() * w);
        int col = m.healthFrac() > 0.5F ? 0xFF4BC06A
                : m.healthFrac() > 0.25F ? 0xFFE3B86F : 0xFFC0403A;
        g.fill(x, y + 1, x + w, y + 5, 0xFF0A0E14);
        g.fill(x, y + 1, x + filled, y + 5, col);
        String hp = (int) m.health() + "/" + (int) m.maxHealth();
        g.drawString(this.font, hp, x + w + 4, y - 1, 0xFFB9C6D4, false);
    }

    // [needs] mode: three tiny vertical bars - hunger (amber), energy
    // (teal), morale (green). Units show "-" when needs are disabled.
    private void renderNeeds(GuiGraphics g, UnitMarker m, int x, int y) {
        if (m.hunger() < 0) {
            g.drawString(this.font, "-", x, y, 0xFF4A5563, false);
            return;
        }
        needBar(g, x, y, m.hunger() / 20.0, 0xFFE3B86F);
        needBar(g, x + 5, y, m.energy() / 20.0, 0xFF6FE3C1);
        needBar(g, x + 10, y, m.morale() / 100.0, 0xFFA6E36F);
    }

    private static void needBar(GuiGraphics g, int x, int y, double frac, int color) {
        int h = Math.max(0, Math.min(8, (int) Math.round(frac * 8)));
        g.fill(x, y - 2, x + 3, y + 6, 0xFF0A0E14);                 // trough
        if (h > 0) {
            g.fill(x, y + 6 - h, x + 3, y + 6, color);              // fill from bottom
        }
    }

    // TELEPORT app: multi-select units, then beam them to the player, the
    // block under the crosshair, or a Xaero waypoint. Unit selection is
    // shared with the map, so clicks here and clicks on map icons agree.
    private void renderTeleport(GuiGraphics g, int mouseX, int mouseY) {
        this.tpTargets.clear();
        this.waypoints =
                com.poseidon.sem_classsystem.client.xaero.SemcsWaypoints.current();
        List<UnitMarker> units = ownedUnits();
        int selected = 0;
        for (UnitMarker m : units) {
            if (UnitMarkerStore.isSelected(m)) {
                selected++;
            }
        }
        openWindow(g, "Teleport - " + selected + "/" + units.size() + " selected");

        int cx = this.winX + 10;
        int cw = this.winW - 20;
        int y = this.winY + 26;

        // quick targets
        int btnW = 120;
        drawTpButton(g, mouseX, mouseY, cx, y, btnW, "TO ME", -1);
        drawTpButton(g, mouseX, mouseY, cx + btnW + 8, y, btnW, "TO CROSSHAIR", -2);
        y += 18;

        // waypoint targets
        g.drawString(this.font, "Waypoints:", cx, y, 0xFF5E7086, false);
        y += 10;
        if (this.waypoints.isEmpty()) {
            g.drawString(this.font,
                    com.poseidon.sem_classsystem.client.xaero.SemcsWaypoints.available()
                            ? "- none in this dimension -"
                            : "- Xaero's Minimap not installed -",
                    cx, y, 0xFF4A5563, false);
            y += 12;
        } else {
            for (int i = 0; i < this.waypoints.size(); i++) {
                var wp = this.waypoints.get(i);
                g.drawString(this.font, this.font.plainSubstrByWidth(wp.name(), 140),
                        cx, y + 3, 0xFFE8F0F8, false);
                g.drawString(this.font, wp.x() + ", " + wp.y() + ", " + wp.z(),
                        cx + 150, y + 3, 0xFFB9C6D4, false);
                int gx = cx + cw - 40;
                boolean hov = mouseX >= gx && mouseX < gx + 36
                        && mouseY >= y && mouseY < y + 12;
                g.fill(gx, y, gx + 36, y + 12, hov ? 0xFF6B4FA6 : 0xFF3A2A5E);
                g.drawCenteredString(this.font, "GO", gx + 18, y + 2, 0xFFFFFFFF);
                this.tpTargets.add(new int[]{gx, y, 36, i});
                y += 14;
            }
            y += 2;
        }

        // unit multi-select roster
        g.drawString(this.font, "Units (click to select):", cx, y, 0xFF5E7086, false);
        y += 10;
        int viewTop = y;
        int viewH = this.winY + this.winH - 10 - viewTop;
        this.rosterViewTop = viewTop;
        this.rosterViewH = viewH;
        int contentH = units.size() * ROW_H;
        int maxScroll = Math.max(0, contentH - viewH);
        this.scroll = Math.min(this.scroll, maxScroll);

        g.enableScissor(cx - 2, viewTop, cx + cw + 2, viewTop + viewH);
        int ry = viewTop - this.scroll;
        for (UnitMarker m : units) {
            if (ry + ROW_H > viewTop && ry < viewTop + viewH) {
                boolean sel = UnitMarkerStore.isSelected(m);
                boolean hover = mouseX >= cx && mouseX < cx + cw
                        && mouseY >= ry && mouseY < ry + ROW_H
                        && mouseY >= viewTop && mouseY < viewTop + viewH;
                if (hover) {
                    g.fill(cx - 2, ry, cx + cw, ry + ROW_H - 1, 0xFF22344A);
                }
                // checkbox
                g.fill(cx, ry + 3, cx + 8, ry + 11,
                        sel ? 0xFF6FE3C1 : 0xFF2A3442);
                g.fill(cx + 1, ry + 4, cx + 7, ry + 10,
                        sel ? 0xFF143326 : 0xFF141C28);
                String name = (m.rankTitle().isEmpty() ? "" : m.rankTitle() + " ")
                        + (m.name().isEmpty() ? "Unit #" + m.entityId() : m.name());
                g.drawString(this.font, this.font.plainSubstrByWidth(name, 160),
                        cx + 14, ry + 3, sel ? 0xFF6FE3C1 : 0xFFE8F0F8, false);
                g.drawString(this.font, classLabel(m), cx + 190, ry + 3,
                        0xFFB9C6D4, false);
                g.drawString(this.font, statusLabel(m), cx + cw - 60, ry + 3,
                        0xFF8FA3B8, false);
            }
            ry += ROW_H;
        }
        g.disableScissor();
        if (units.isEmpty()) {
            g.drawCenteredString(this.font,
                    "No owned units in this dimension.",
                    this.winX + this.winW / 2, viewTop + viewH / 2, 0xFF5E7086);
        }
        if (maxScroll > 0) {
            int trackX = this.winX + this.winW - 6;
            g.fill(trackX, viewTop, trackX + 3, viewTop + viewH, 0xFF0A0E14);
            int thumbH = Math.max(12, viewH * viewH / contentH);
            int thumbY = viewTop + (viewH - thumbH) * this.scroll / maxScroll;
            g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFF6FE3C1);
        }
    }

    private void drawTpButton(GuiGraphics g, int mouseX, int mouseY,
                              int x, int y, int w, String label, int code) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 14;
        g.fill(x, y, x + w, y + 14, hover ? 0xFF6B4FA6 : 0xFF3A2A5E);
        g.drawCenteredString(this.font, label, x + w / 2, y + 3, 0xFFFFFFFF);
        this.tpTargets.add(new int[]{x, y, w, code});
    }

    private void renderIntel(GuiGraphics g, int mouseX, int mouseY) {
        openWindow(g, "Intel");
        Minecraft mc = Minecraft.getInstance();
        int x = this.winX + 12;
        int y = this.winY + 28;
        int line = 12;

        List<UnitMarker> own = ownedUnits();
        int spotted = 0;
        for (UnitMarker m : UnitMarkerStore.markers()) {
            if (m.allegiance() != UnitMarker.Allegiance.OWN) {
                spotted++;
            }
        }
        String dim = mc.level != null
                ? mc.level.dimension().location().toString() : "?";
        int claims = mc.player != null
                ? UnitMarkerStore.claimCount(mc.player.getUUID()) : 0;

        g.drawString(this.font, "Commander:  "
                + (mc.player == null ? "?" : mc.player.getGameProfile().getName()),
                x, y, 0xFFE8F0F8, false);
        y += line;
        g.drawString(this.font, "Units active:  " + own.size() + " own / "
                + spotted + " spotted contacts", x, y, 0xFFB9C6D4, false);
        y += line;
        g.drawString(this.font, "Claims in " + dim + ":  " + claims + " / "
                + ClassSystemConfig.maxClaimedChunks(), x, y, 0xFFB9C6D4, false);
        y += line;
        g.drawString(this.font, "Contiguous claims:  "
                + (ClassSystemConfig.requireContiguousClaims() ? "enforced" : "off"),
                x, y, 0xFFB9C6D4, false);
        y += line;
        g.drawString(this.font, "Map selection:  "
                + UnitMarkerStore.selectedUnitIds().size() + " unit(s)",
                x, y, 0xFFB9C6D4, false);
        y += line;

        double[] ping = mc.level == null ? null
                : UnitMarkerStore.raidPing(dim, mc.level.getGameTime());
        if (ping == null) {
            g.drawString(this.font, "Raid alert:  none", x, y, 0xFF8FA3B8, false);
        } else {
            double dist = mc.player == null ? 0
                    : Math.sqrt(mc.player.distanceToSqr(ping[0], mc.player.getY(), ping[1]));
            g.drawString(this.font, "RAID ALERT:  hostiles at " + (int) ping[0]
                    + ", " + (int) ping[1] + " (~" + (int) dist + "m)",
                    x, y, 0xFFC0403A, false);
        }
    }

    // ---- input ---------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // App icons (same geometry as renderIcons)
            int cx = this.width / 2;
            int cy = this.height / 2 - 30;
            if (hitIcon(mouseX, mouseY, cx - 103, cy)) {
                UiSounds.click();
                this.openApp = App.SQUAD;
                this.scroll = 0;
                return true;
            }
            if (hitIcon(mouseX, mouseY, cx - 15, cy)) {
                UiSounds.click();
                this.openApp = App.TELEPORT;
                this.scroll = 0;
                return true;
            }
            if (hitIcon(mouseX, mouseY, cx + 73, cy)) {
                UiSounds.click();
                this.openApp = App.INTEL;
                return true;
            }
            if (this.openApp != null) {
                // window close button
                int bx = this.winX + this.winW - 16;
                if (mouseX >= bx && mouseX < bx + 12
                        && mouseY >= this.winY + 4 && mouseY < this.winY + 14) {
                    UiSounds.click();
                    this.openApp = null;
                    return true;
                }
                if (this.openApp == App.TELEPORT
                        && clickTeleportTarget(mouseX, mouseY)) {
                    return true;
                }
                if ((this.openApp == App.SQUAD || this.openApp == App.TELEPORT)
                        && clickRosterRow(mouseX, mouseY)) {
                    UiSounds.click();
                    return true;
                }
                // clicks inside the window are consumed so the desktop
                // behind can't be poked through
                if (mouseX >= this.winX && mouseX < this.winX + this.winW
                        && mouseY >= this.winY && mouseY < this.winY + this.winH) {
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean hitIcon(double mx, double my, int x, int y) {
        return mx >= x && mx < x + ICON_SIZE && my >= y && my < y + ICON_SIZE;
    }

    private boolean clickTeleportTarget(double mouseX, double mouseY) {
        for (int[] t : this.tpTargets) {
            if (mouseX < t[0] || mouseX >= t[0] + t[2]
                    || mouseY < t[1] || mouseY >= t[1] + 14) {
                continue;
            }
            List<Integer> ids = new ArrayList<>(
                    UnitMarkerStore.selectedUnitIds());
            if (ids.isEmpty()) {
                return true;
            }
            BlockPos target = null;
            int mode = PacketTeleportUnits.MODE_PLAYER;
            if (t[3] == -2) {   // crosshair block
                Minecraft mc = Minecraft.getInstance();
                if (mc.hitResult instanceof BlockHitResult bhr) {
                    target = bhr.getBlockPos().relative(bhr.getDirection());
                }
                if (target == null) {
                    return true;
                }
                mode = PacketTeleportUnits.MODE_POS;
            } else if (t[3] >= 0) {   // waypoint
                var wp = this.waypoints.get(t[3]);
                target = new BlockPos(wp.x(), wp.y() + 1, wp.z());
                mode = PacketTeleportUnits.MODE_POS;
            }
            SemcsNetwork.CHANNEL.sendToServer(
                    new PacketTeleportUnits(ids, mode, target));
            UiSounds.confirm();
            return true;
        }
        return false;
    }

    // Row click toggles that unit's map selection - roster and map stay in
    // sync because both read UnitMarkerStore.
    private boolean clickRosterRow(double mouseX, double mouseY) {
        if (mouseX < this.winX + 8 || mouseX >= this.winX + this.winW - 8
                || mouseY < this.rosterViewTop
                || mouseY >= this.rosterViewTop + this.rosterViewH) {
            return false;
        }
        int row = (int) ((mouseY - this.rosterViewTop + this.scroll) / ROW_H);
        List<UnitMarker> units = ownedUnits();
        if (row < 0 || row >= units.size()) {
            return false;
        }
        UnitMarkerStore.toggleSelected(units.get(row));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (this.openApp == App.SQUAD) {
            this.scroll = Math.max(0, this.scroll - (int) (delta * ROW_H));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 && this.openApp != null) {   // ESC backs out of the app first
            this.openApp = null;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---- labels ----------------------------------------------------------------

    private static String classLabel(UnitMarker m) {
        if (m.classOrd() >= 0
                && m.classOrd() < WeaponClass.values().length) {
            return ClassSystemConfig.classNickname(
                    WeaponClass.values()[m.classOrd()]);
        }
        if (m.factionOrd() >= 0
                && m.factionOrd() < UnitClass.values().length) {
            return UnitClass.values()[m.factionOrd()].name();
        }
        return "-";
    }

    private static String statusLabel(UnitMarker m) {
        return switch (m.dutyMode()) {
            case UnitChunkDutyGoal.DUTY_GUARD -> "Guard";
            case UnitChunkDutyGoal.DUTY_PATROL -> "Patrol";
            case UnitChunkDutyGoal.DUTY_ROUTE -> "Route";
            case UnitChunkDutyGoal.DUTY_POST -> "Post";
            default -> orderLabel(m.orderOrd());
        };
    }

    private static String orderLabel(int orderOrd) {
        OrderType[] values = OrderType.values();
        if (orderOrd < 0 || orderOrd >= values.length) {
            return "-";
        }
        String n = values[orderOrd].name().replace('_', ' ').toLowerCase();
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
