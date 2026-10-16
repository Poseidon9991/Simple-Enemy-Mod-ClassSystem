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

// A field terminal, not a desktop: olive-drab device chrome, amber stencil
// text, scanlines. The Squad app is the roster the Field Tablet exists for -
// every owned unit (any dimension) with health, ammo reserve (or an infinity
// mark when the unit carries a creative/all-type TaCZ ammo box), class, and
// food/medical supply chips. Clicking a row toggles map selection so the
// roster and world map agree; the order bar issues SEM orders to every
// selected unit through SEM's own PacketIssueOrder wire.
// All numbers arrive on the wire in UnitMarker; the client never scans
// entity inventories itself.
public class TabletScreen extends Screen {

    private enum App {SQUAD, TELEPORT, INTEL}

    private enum SortField {NAME, CLASS, HP, AMMO, NEEDS, STATUS}

    private static final int TASKBAR_H = 20;
    private static final int ICON_SIZE = 30;
    private static final int ROW_H = 15;

    // Field-tablet palette: dark olive chassis, khaki text, amber accents.
    private static final int COL_BG_TOP = 0xFF0A0D07;
    private static final int COL_BG_BOT = 0xFF151B0E;
    private static final int COL_TASKBAR = 0xFF060804;
    private static final int COL_ACCENT = 0xFFD8B36A;    // amber stencil
    private static final int COL_ACCENT_DIM = 0xFF8A8F6A;
    private static final int COL_PANEL = 0xFF12170C;
    private static final int COL_PANEL_EDGE = 0xFF3E4428;
    private static final int COL_TITLE = 0xFF1B2210;
    private static final int COL_TEXT = 0xFFE4E8D8;
    private static final int COL_SUB = 0xFFA8B08C;
    private static final int COL_ROW_HOVER = 0xFF1E2A14;
    private static final int COL_BAD = 0xFFC04A3A;
    private static final int COL_OK = 0xFF6FAE4E;

    private App openApp = null;
    private int scroll = 0;
    private SortField sortField = SortField.NAME;
    private boolean sortAsc = true;
    private boolean filterCrit = false;

    // Window geometry, refreshed each frame so hit tests use what was drawn.
    private int winX, winY, winW, winH;

    // Teleport app click zones, rebuilt during render:
    //   positive code = waypoint index, -1 = "to me", -2 = "to crosshair"
    private final List<int[]> tpTargets = new ArrayList<>();
    private List<com.poseidon.sem_classsystem.client.xaero.SemcsWaypoints.WaypointEntry>
            waypoints = List.of();
    private int rosterViewTop = 0;
    private int rosterViewH = 0;

    // Click zones rebuilt during render each frame:
    //   headerSorts  {x, y, w, sortFieldOrdinal}
    //   orderButtons {x, y, w, orderOrdinal | -1 select-all | -2 clear}
    //   critButton   {x, y, w, h} or null
    private final List<int[]> headerSorts = new ArrayList<>();
    private final List<int[]> orderButtons = new ArrayList<>();
    private int[] critButton = null;

    public TabletScreen() {
        super(Component.literal("FIELD-LINK"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- rendering ---------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        // Chassis wallpaper - dark olive gradient with a faint survey grid.
        g.fillGradient(0, 0, this.width, this.height, COL_BG_TOP, COL_BG_BOT);
        for (int x = 0; x < this.width; x += 40) {
            g.fill(x, TASKBAR_H, x + 1, this.height, 0x0CFFFFFF);
        }
        for (int y = TASKBAR_H; y < this.height; y += 40) {
            g.fill(0, y, this.width, y + 1, 0x0CFFFFFF);
        }
        // Scanlines - cheap phosphor-screen texture across the whole display.
        for (int y = TASKBAR_H + 2; y < this.height; y += 4) {
            g.fill(0, y, this.width, y + 1, 0x08FFFFFF);
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
        g.fill(0, 0, this.width, TASKBAR_H, COL_TASKBAR);
        g.drawString(this.font, "FIELD-LINK // SEM TACNET", 6, 6, COL_ACCENT, false);
        // Status pips next to the title - pure chrome, no function.
        g.fill(6 + this.font.width("FIELD-LINK // SEM TACNET") + 6, 8,
                6 + this.font.width("FIELD-LINK // SEM TACNET") + 10, 10, COL_OK);
        g.fill(6 + this.font.width("FIELD-LINK // SEM TACNET") + 12, 8,
                6 + this.font.width("FIELD-LINK // SEM TACNET") + 16, 10, COL_ACCENT);
        Minecraft mc = Minecraft.getInstance();
        String right = mc.player == null ? "" : mc.player.getGameProfile().getName();
        if (mc.level != null) {
            long t = (mc.level.getDayTime() + 6000L) % 24000L;
            right = String.format("%02d:%02d  %s", t / 1000, (t % 1000) / 17, right);
        }
        g.drawString(this.font, right,
                this.width - 6 - this.font.width(right), 6, COL_SUB, false);
    }

    private void renderIcons(GuiGraphics g, int mouseX, int mouseY) {
        int cx = this.width / 2;
        int cy = this.height / 2 - 30;
        drawAppIcon(g, cx - 103, cy, mouseX, mouseY, 0xFF4A5A28, "Squad", () -> {
            // three squad chevrons
            g.fill(cx - 103 + 8, cy + 8, cx - 103 + 12, cy + 22, 0xFFDFF5E8);
            g.fill(cx - 103 + 13, cy + 8, cx - 103 + 17, cy + 22, 0xFFB8E4C8);
            g.fill(cx - 103 + 18, cy + 8, cx - 103 + 22, cy + 22, 0xFF8FD3AC);
        });
        drawAppIcon(g, cx - 15, cy, mouseX, mouseY, 0xFF5A5230, "Teleport", () -> {
            // portal swirl: diamond ring + dot
            int ix = cx - 15;
            g.fill(ix + 13, cy + 7, ix + 17, cy + 11, 0xFFD8C8FF);
            g.fill(ix + 9, cy + 11, ix + 13, cy + 15, 0xFFB89AF0);
            g.fill(ix + 17, cy + 11, ix + 21, cy + 15, 0xFFB89AF0);
            g.fill(ix + 13, cy + 15, ix + 17, cy + 19, 0xFFD8C8FF);
            g.fill(ix + 13, cy + 11, ix + 17, cy + 15, 0xFFF4ECFF);
        });
        drawAppIcon(g, cx + 73, cy, mouseX, mouseY, 0xFF6B4A24, "Intel", () -> {
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
                hover ? 0xFFFFFFFF : COL_SUB);
    }

    // Window chrome shared by the apps: a thick rugged bezel with corner
    // screws, khaki title strip, hard red close key. Returns content origin
    // via fields.
    private void openWindow(GuiGraphics g, String title) {
        this.winW = Math.min(560, this.width - 50);
        this.winH = Math.min(300, this.height - 70);
        this.winX = (this.width - this.winW) / 2;
        this.winY = (this.height - this.winH) / 2 + 8;
        g.fill(this.winX - 2, this.winY - 2, this.winX + this.winW + 2,
                this.winY + this.winH + 2, 0xFF2A2E1A);
        g.fill(this.winX - 1, this.winY - 1, this.winX + this.winW + 1,
                this.winY + this.winH + 1, COL_PANEL_EDGE);
        g.fill(this.winX, this.winY, this.winX + this.winW,
                this.winY + this.winH, COL_PANEL);
        // corner screws on the bezel
        for (int sx : new int[]{this.winX - 1, this.winX + this.winW - 1}) {
            for (int sy : new int[]{this.winY - 1, this.winY + this.winH - 1}) {
                g.fill(sx, sy, sx + 2, sy + 2, 0xFF9AA27C);
            }
        }
        // title strip
        g.fill(this.winX, this.winY, this.winX + this.winW, this.winY + 18, COL_TITLE);
        g.fill(this.winX, this.winY + 18, this.winX + this.winW, this.winY + 19,
                0xFF2E351C);
        g.drawString(this.font, title.toUpperCase(java.util.Locale.ROOT),
                this.winX + 8, this.winY + 5, COL_ACCENT, false);
        // close key
        int bx = this.winX + this.winW - 16;
        g.fill(bx, this.winY + 4, bx + 12, this.winY + 14, 0xFF7A2E24);
        g.drawCenteredString(this.font, "x", bx + 6, this.winY + 6, 0xFFFFFFFF);
    }

    private List<UnitMarker> ownedUnits() {
        List<UnitMarker> own = new ArrayList<>();
        for (UnitMarker m : UnitMarkerStore.markers()) {
            if (m.allegiance() == UnitMarker.Allegiance.OWN) {
                own.add(m);
            }
        }
        if (this.filterCrit) {
            own.removeIf(m -> !isCritical(m));
        }
        Comparator<UnitMarker> cmp = switch (this.sortField) {
            case CLASS -> Comparator.comparingInt(UnitMarker::classOrd);
            case HP -> Comparator.comparingDouble(UnitMarker::healthFrac);
            case AMMO -> Comparator.comparingInt(
                    (UnitMarker m) -> m.ammoInfinite() ? Integer.MAX_VALUE : m.ammoRounds());
            case NEEDS -> Comparator.comparingInt(this::needScore);
            case STATUS -> Comparator.comparingInt(
                    (UnitMarker m) -> (m.downed() ? -2 : 0) - m.dutyMode());
            case NAME -> Comparator.comparing(UnitMarker::name);
        };
        own.sort((this.sortAsc ? cmp : cmp.reversed())
                .thenComparingInt(UnitMarker::entityId));
        return own;
    }

    // Needs floor: lowest of hunger/energy/morale (normalised), -1 counts
    // as "no needs data" and sorts last.
    private int needScore(UnitMarker m) {
        if (m.hunger() < 0) {
            return Integer.MAX_VALUE;
        }
        return Math.min(m.hunger(), Math.min(m.energy(), m.morale() / 5));
    }

    private static boolean isCritical(UnitMarker m) {
        if (m.downed() || m.healthFrac() < 0.35F) {
            return true;
        }
        return m.hunger() >= 0
                && (m.hunger() <= 2 || m.energy() <= 1 || m.morale() < 25);
    }

    private void renderSquad(GuiGraphics g, int mouseX, int mouseY) {
        this.headerSorts.clear();
        this.orderButtons.clear();
        this.critButton = null;
        List<UnitMarker> units = ownedUnits();
        int selected = 0;
        for (UnitMarker m : units) {
            if (UnitMarkerStore.isSelected(m)) {
                selected++;
            }
        }
        openWindow(g, "Squad Roster - " + units.size() + " unit(s)"
                + (selected > 0 ? " - " + selected + " selected" : "")
                + (this.filterCrit ? " [CRIT]" : ""));

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

        // Sortable headers - click toggles field / direction, arrow shows state.
        drawHeader(g, colName, cy, 112, "CALLSIGN", SortField.NAME);
        drawHeader(g, colClass, cy, 60, "CLASS", SortField.CLASS);
        drawHeader(g, colHp, cy, 82, "HP", SortField.HP);
        drawHeader(g, colAmmo, cy, 44, "AMMO", SortField.AMMO);
        g.drawString(this.font, "MED", colMed, cy, COL_ACCENT_DIM, false);
        g.drawString(this.font, "FOOD", colFood, cy, COL_ACCENT_DIM, false);
        drawHeader(g, colStatus, cy, 42, "STATUS", SortField.STATUS);
        drawHeader(g, colNeeds, cy, 40, "NEEDS", SortField.NEEDS);
        // CRIT filter toggle at the right edge of the header row
        int fbx = this.winX + this.winW - 30;
        boolean fHover = mouseX >= fbx && mouseX < fbx + 22 && mouseY >= cy - 2 && mouseY < cy + 9;
        g.fill(fbx, cy - 2, fbx + 22, cy + 9,
                this.filterCrit ? 0xFF7A2E24 : fHover ? 0xFF3A4030 : 0xFF232818);
        g.drawCenteredString(this.font, "CRIT", fbx + 11, cy,
                this.filterCrit ? 0xFFFFFFFF : COL_ACCENT_DIM);
        this.critButton = new int[]{fbx, cy - 2, 22, 11};
        cy += 11;

        int viewTop = cy;
        int viewH = this.winY + this.winH - 30 - viewTop;   // 20px order bar + pad
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
                    g.fill(cx - 2, ry, cx + cw, ry + ROW_H - 1, COL_ROW_HOVER);
                }
                if (UnitMarkerStore.isSelected(m)) {
                    g.fill(cx - 2, ry, cx, ry + ROW_H - 1, COL_ACCENT);
                }
                String name = (m.squad().isEmpty() ? "" : "[" + m.squad() + "] ")
                        + (m.rankTitle().isEmpty() ? "" : m.rankTitle() + " ")
                        + (m.name().isEmpty() ? "Unit #" + m.entityId() : m.name());
                g.drawString(this.font, this.font.plainSubstrByWidth(name, 112),
                        colName, ry + 4, m.downed() ? COL_BAD : COL_TEXT, false);
                g.drawString(this.font, classLabel(m), colClass, ry + 4,
                        COL_SUB, false);
                renderHp(g, m, colHp, ry + 4);
                if (m.ammoInfinite()) {
                    g.drawString(this.font, "INF", colAmmo, ry + 4, COL_ACCENT, false);
                } else {
                    g.drawString(this.font, String.valueOf(m.ammoRounds()),
                            colAmmo, ry + 4,
                            m.ammoRounds() > 0 ? COL_TEXT : COL_BAD, false);
                }
                g.drawString(this.font, m.hasMedical() ? "+" : "-", colMed,
                        ry + 4, m.hasMedical() ? COL_ACCENT : 0xFF4A5538, false);
                g.drawString(this.font, m.hasFood() ? "*" : "-", colFood,
                        ry + 4, m.hasFood() ? 0xFFE3B86F : 0xFF4A5538, false);
                g.drawString(this.font, statusLabel(m), colStatus, ry + 4,
                        m.downed() ? COL_BAD
                                : m.dutyMode() != 0 ? 0xFFE3B86F : COL_SUB, false);
                renderNeeds(g, m, colNeeds, ry + 4);
            }
            ry += ROW_H;
        }
        g.disableScissor();

        if (units.isEmpty()) {
            g.drawCenteredString(this.font,
                    this.filterCrit ? "No critical units."
                            : "No units in this dimension - recruit or regroup here.",
                    this.winX + this.winW / 2, viewTop + viewH / 2, COL_ACCENT_DIM);
        }
        if (maxScroll > 0) {
            int trackX = this.winX + this.winW - 6;
            g.fill(trackX, viewTop, trackX + 3, viewTop + viewH, COL_TASKBAR);
            int thumbH = Math.max(12, viewH * viewH / contentH);
            int thumbY = viewTop + (viewH - thumbH) * this.scroll / maxScroll;
            g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, COL_ACCENT);
        }

        renderOrderBar(g, mouseX, mouseY);
    }

    // Header cell with sort arrow; registers its hit zone for mouseClicked.
    private void drawHeader(GuiGraphics g, int x, int y, int w,
                            String label, SortField field) {
        boolean active = this.sortField == field;
        String arrow = active ? (this.sortAsc ? " ^" : " v") : "";
        g.drawString(this.font, label + arrow, x, y,
                active ? COL_ACCENT : COL_ACCENT_DIM, false);
        this.headerSorts.add(new int[]{x - 2, y - 2, w + 4, field.ordinal()});
    }

    // Bottom bar of the squad window: selection helpers on the left, SEM
    // order keys on the right - every order rides SEM's PacketIssueOrder to
    // each selected unit, same wire the world-map menu uses.
    private void renderOrderBar(GuiGraphics g, int mouseX, int mouseY) {
        int y = this.winY + this.winH - 22;
        g.fill(this.winX + 4, y - 4, this.winX + this.winW - 4, y + 14, COL_TITLE);
        int x = this.winX + 8;
        drawBarButton(g, mouseX, mouseY, x, y, 34, "ALL", -1);
        x += 38;
        drawBarButton(g, mouseX, mouseY, x, y, 40, "NONE", -2);
        x += 48;
        g.drawString(this.font, "ORDERS:", x, y + 2, COL_ACCENT_DIM, false);
        x += 46;
        String[] labels = {"FOLLOW", "HOLD", "WEDGE", "COLUMN", "FREE", "CEASE"};
        OrderType[] orders = {OrderType.FOLLOW_COMMANDER, OrderType.HOLD_POSITION,
                OrderType.FORM_WEDGE, OrderType.FORM_COLUMN,
                OrderType.FREE_FIRE, OrderType.CEASE_FIRE};
        for (int i = 0; i < labels.length; i++) {
            int w = this.font.width(labels[i]) + 10;
            drawBarButton(g, mouseX, mouseY, x, y, w, labels[i], orders[i].ordinal());
            x += w + 4;
        }
    }

    private void drawBarButton(GuiGraphics g, int mouseX, int mouseY,
                               int x, int y, int w, String label, int code) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 12;
        g.fill(x, y, x + w, y + 12,
                hover ? 0xFF4A5230 : 0xFF232818);
        g.fill(x, y + 11, x + w, y + 12, hover ? COL_ACCENT : 0xFF3A4030);
        g.drawCenteredString(this.font, label, x + w / 2, y + 2,
                hover ? COL_ACCENT : COL_SUB);
        this.orderButtons.add(new int[]{x, y, w, code});
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
        // Critical warning: starving, collapsed, or about to break.
        if (m.hunger() <= 2 || m.energy() <= 1 || m.morale() < 25) {
            g.drawString(this.font, "!", x + 16, y - 2, 0xFFC0403A, false);
        }
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
        g.drawString(this.font, "Waypoints:", cx, y, COL_ACCENT_DIM, false);
        y += 10;
        if (this.waypoints.isEmpty()) {
            g.drawString(this.font,
                    com.poseidon.sem_classsystem.client.xaero.SemcsWaypoints.available()
                            ? "- none in this dimension -"
                            : "- Xaero's Minimap not installed -",
                    cx, y, COL_ACCENT_DIM, false);
            y += 12;
        } else {
            for (int i = 0; i < this.waypoints.size(); i++) {
                var wp = this.waypoints.get(i);
                g.drawString(this.font, this.font.plainSubstrByWidth(wp.name(), 140),
                        cx, y + 3, COL_TEXT, false);
                g.drawString(this.font, wp.x() + ", " + wp.y() + ", " + wp.z(),
                        cx + 150, y + 3, COL_SUB, false);
                int gx = cx + cw - 40;
                boolean hov = mouseX >= gx && mouseX < gx + 36
                        && mouseY >= y && mouseY < y + 12;
                g.fill(gx, y, gx + 36, y + 12, hov ? 0xFF4A5230 : 0xFF232818);
                g.drawCenteredString(this.font, "GO", gx + 18, y + 2,
                        hov ? COL_ACCENT : COL_SUB);
                this.tpTargets.add(new int[]{gx, y, 36, i});
                y += 14;
            }
            y += 2;
        }

        // unit multi-select roster
        g.drawString(this.font, "Units (click to select):", cx, y, COL_ACCENT_DIM, false);
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
                    g.fill(cx - 2, ry, cx + cw, ry + ROW_H - 1, COL_ROW_HOVER);
                }
                // checkbox
                g.fill(cx, ry + 3, cx + 8, ry + 11,
                        sel ? COL_ACCENT : 0xFF2A3018);
                g.fill(cx + 1, ry + 4, cx + 7, ry + 10,
                        sel ? 0xFF3A2F18 : COL_PANEL);
                String name = (m.rankTitle().isEmpty() ? "" : m.rankTitle() + " ")
                        + (m.name().isEmpty() ? "Unit #" + m.entityId() : m.name());
                g.drawString(this.font, this.font.plainSubstrByWidth(name, 160),
                        cx + 14, ry + 3, sel ? COL_ACCENT : COL_TEXT, false);
                g.drawString(this.font, classLabel(m), cx + 190, ry + 3,
                        COL_SUB, false);
                g.drawString(this.font, statusLabel(m), cx + cw - 60, ry + 3,
                        m.downed() ? COL_BAD : COL_SUB, false);
            }
            ry += ROW_H;
        }
        g.disableScissor();
        if (units.isEmpty()) {
            g.drawCenteredString(this.font,
                    "No owned units in this dimension.",
                    this.winX + this.winW / 2, viewTop + viewH / 2, COL_ACCENT_DIM);
        }
        if (maxScroll > 0) {
            int trackX = this.winX + this.winW - 6;
            g.fill(trackX, viewTop, trackX + 3, viewTop + viewH, COL_TASKBAR);
            int thumbH = Math.max(12, viewH * viewH / contentH);
            int thumbY = viewTop + (viewH - thumbH) * this.scroll / maxScroll;
            g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, COL_ACCENT);
        }
    }

    private void drawTpButton(GuiGraphics g, int mouseX, int mouseY,
                              int x, int y, int w, String label, int code) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 14;
        g.fill(x, y, x + w, y + 14, hover ? 0xFF4A5230 : 0xFF232818);
        g.drawCenteredString(this.font, label, x + w / 2, y + 3,
                hover ? COL_ACCENT : 0xFFFFFFFF);
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
                x, y, COL_TEXT, false);
        y += line;
        g.drawString(this.font, "Units active:  " + own.size() + " own / "
                + spotted + " spotted contacts", x, y, COL_SUB, false);
        y += line;
        g.drawString(this.font, "Claims in " + dim + ":  " + claims + " / "
                + ClassSystemConfig.maxClaimedChunks(), x, y, COL_SUB, false);
        y += line;
        g.drawString(this.font, "Contiguous claims:  "
                + (ClassSystemConfig.requireContiguousClaims() ? "enforced" : "off"),
                x, y, COL_SUB, false);
        y += line;
        g.drawString(this.font, "Map selection:  "
                + UnitMarkerStore.selectedUnitIds().size() + " unit(s)",
                x, y, COL_SUB, false);
        y += line;

        double[] ping = mc.level == null ? null
                : UnitMarkerStore.raidPing(dim, mc.level.getGameTime());
        if (ping == null) {
            g.drawString(this.font, "Raid alert:  none", x, y, COL_ACCENT_DIM, false);
        } else {
            double dist = mc.player == null ? 0
                    : Math.sqrt(mc.player.distanceToSqr(ping[0], mc.player.getY(), ping[1]));
            g.drawString(this.font, "RAID ALERT:  hostiles at " + (int) ping[0]
                    + ", " + (int) ping[1] + " (~" + (int) dist + "m)",
                    x, y, COL_BAD, false);
        }
        y += line + 4;

        // Casualty board - newest losses last.
        List<String> kia = UnitMarkerStore.casualties();
        g.drawString(this.font, "CASUALTIES:", x, y, COL_ACCENT_DIM, false);
        y += 10;
        if (kia.isEmpty()) {
            g.drawString(this.font, "  none recorded", x, y, 0xFF4A5538, false);
        } else {
            int shown = 0;
            for (int i = kia.size() - 1; i >= 0 && shown < 8; i--, shown++) {
                g.drawString(this.font, this.font.plainSubstrByWidth(
                        "x  " + kia.get(i), this.winW - 40), x, y, COL_SUB, false);
                y += 10;
            }
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
                if (this.openApp == App.SQUAD
                        && clickSquadChrome(mouseX, mouseY)) {
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

    // Squad-app chrome clicks: sortable headers, the CRIT filter toggle,
    // and the bottom bar (selection helpers + batch SEM orders).
    private boolean clickSquadChrome(double mouseX, double mouseY) {
        for (int[] h : this.headerSorts) {
            if (mouseX >= h[0] && mouseX < h[0] + h[2]
                    && mouseY >= h[1] && mouseY < h[1] + 11) {
                SortField field = SortField.values()[h[3]];
                if (this.sortField == field) {
                    this.sortAsc = !this.sortAsc;
                } else {
                    this.sortField = field;
                    this.sortAsc = true;
                }
                UiSounds.click();
                return true;
            }
        }
        if (this.critButton != null
                && mouseX >= this.critButton[0]
                && mouseX < this.critButton[0] + this.critButton[2]
                && mouseY >= this.critButton[1]
                && mouseY < this.critButton[1] + this.critButton[3]) {
            this.filterCrit = !this.filterCrit;
            this.scroll = 0;
            UiSounds.click();
            return true;
        }
        for (int[] b : this.orderButtons) {
            if (mouseX < b[0] || mouseX >= b[0] + b[2]
                    || mouseY < b[1] || mouseY >= b[1] + 12) {
                continue;
            }
            if (b[3] == -1) {        // ALL
                for (UnitMarker m : UnitMarkerStore.markers()) {
                    if (m.allegiance() == UnitMarker.Allegiance.OWN) {
                        UnitMarkerStore.addSelected(m);
                    }
                }
            } else if (b[3] == -2) { // NONE
                UnitMarkerStore.clearSelection();
            } else {
                issueOrder(OrderType.values()[b[3]]);
            }
            UiSounds.confirm();
            return true;
        }
        return false;
    }

    // Broadcast one SEM order to every selected unit - same PacketIssueOrder
    // wire the map's right-click menu uses (server validates ownership).
    private void issueOrder(OrderType order) {
        java.util.Set<Integer> ids = UnitMarkerStore.selectedUnitIds();
        if (ids.isEmpty()) {
            return;
        }
        for (int id : ids) {
            net.nekoyuni.SimpleEnemyMod.network.ModNetworking.CHANNEL.sendToServer(
                    new net.nekoyuni.SimpleEnemyMod.network.packets.PacketIssueOrder(
                            id, order, net.minecraft.world.phys.Vec3.ZERO, 0, -1));
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(
                    "Order " + order.name().replace('_', ' ') + " -> "
                            + ids.size() + " unit(s)")
                    .withStyle(net.minecraft.ChatFormatting.GREEN), true);
        }
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
        if (m.downed()) {
            return "DOWNED";     // collapsed - needs a revive before bleedout
        }
        if (m.sleeping()) {
            return "Sleeping";   // off-shift: bunked down on a claimed bed
        }
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
