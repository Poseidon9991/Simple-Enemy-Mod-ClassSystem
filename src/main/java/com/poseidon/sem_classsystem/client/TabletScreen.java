// Client package - SEM-OS desktop shown by the Field Tablet item
package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.entity.ai.UnitChunkDutyGoal;
import com.poseidon.sem_classsystem.faction.UnitClass;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.map.UnitMarker;
import com.poseidon.sem_classsystem.network.PacketTeleportUnits;
import com.poseidon.sem_classsystem.network.SemcsNetwork;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

// Field terminal UI for the Field Tablet item: olive-drab chassis, amber
// stencil typography, responsive data tables, search/filter bar, smooth
// cubic-out transitions (respecting reduced-motion settings), and batch
// squad command/teleport controls.
public class TabletScreen extends Screen {

    private enum App {SQUAD, TELEPORT, INTEL}

    private enum SortField {NAME, CLASS, HP, AMMO, SUPPLY, STATUS, NEEDS}

    private enum FilterMode {ALL, CRIT, DUTY, FOLLOW}

    private static final int TASKBAR_H = 22;
    private static final int ICON_SIZE = 34;
    private static final int ROW_H = 16;

    // Cohesive Field-Tablet palette: dark olive chassis, khaki text, amber accents
    private static final int COL_BG_TOP = 0xFF0A0D08;
    private static final int COL_BG_BOT = 0xFF141A0E;
    private static final int COL_TASKBAR = 0xFF070905;
    private static final int COL_ACCENT = 0xFFD8B36A;
    private static final int COL_ACCENT_BRIGHT = 0xFFF2D086;
    private static final int COL_ACCENT_DIM = 0xFF8A8F6A;
    private static final int COL_PANEL = 0xFF11160C;
    private static final int COL_PANEL_ALT = 0xFF151C0F;
    private static final int COL_PANEL_EDGE = 0xFF3E4528;
    private static final int COL_TITLE = 0xFF1A2110;
    private static final int COL_HEADER_BG = 0xFF161D0D;
    private static final int COL_TEXT = 0xFFE6EAD8;
    private static final int COL_SUB = 0xFFA8B08C;
    private static final int COL_MUTED = 0xFF5A6246;
    private static final int COL_ROW_HOVER = 0xFF212E16;
    private static final int COL_ROW_SEL = 0xFF283418;
    private static final int COL_BAD = 0xFFC84B3A;
    private static final int COL_WARN = 0xFFE3A84B;
    private static final int COL_OK = 0xFF62B852;

    private App openApp = App.SQUAD; // Open directly to Squad Roster for immediate utility
    private int scroll = 0;
    private float smoothScroll = 0.0F;
    private SortField sortField = SortField.NAME;
    private boolean sortAsc = true;
    private FilterMode filterMode = FilterMode.ALL;
    private String searchQuery = "";
    private boolean searchFocused = false;

    // Smooth animation state (respects reduced-motion setting)
    private long lastFrameMs = Util.getMillis();
    private float appOpenAnim = 0.0F;

    // Inline feedback toast banner
    private String feedbackText = "";
    private int feedbackColor = COL_OK;
    private long feedbackUntilMs = 0L;

    // Window geometry, refreshed each frame
    private int winX, winY, winW, winH;
    private int rosterViewTop = 0;
    private int rosterViewH = 0;
    private int maxScrollCached = 0;

    // Click zones rebuilt during render each frame
    private final List<int[]> navTabs = new ArrayList<>();       // {x, y, w, h, appOrd}
    private final List<int[]> headerSorts = new ArrayList<>();   // {x, y, w, sortFieldOrdinal}
    private final List<int[]> filterPills = new ArrayList<>();   // {x, y, w, h, filterOrd}
    private final List<int[]> orderButtons = new ArrayList<>();  // {x, y, w, code}
    private final List<int[]> tpTargets = new ArrayList<>();     // {x, y, w, code}
    private int[] searchBoxRect = null;                          // {x, y, w, h}
    private int[] searchClearRect = null;                        // {x, y, w, h}

    private List<com.poseidon.sem_classsystem.client.xaero.SemcsWaypoints.WaypointEntry> waypoints = List.of();
    private UnitMarker hoveredUnit = null;

    public TabletScreen() {
        super(Component.literal("FIELD-LINK"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static boolean reducedMotion() {
        Minecraft mc = Minecraft.getInstance();
        return mc.options != null && mc.options.screenEffectScale().get() <= 0.01D;
    }

    private void updateAnimations() {
        long now = Util.getMillis();
        float dt = Mth.clamp((now - this.lastFrameMs) / 1000.0F, 0.0F, 0.1F);
        this.lastFrameMs = now;
        if (reducedMotion()) {
            this.appOpenAnim = 1.0F;
            this.smoothScroll = this.scroll;
            return;
        }
        this.appOpenAnim = Math.min(1.0F, this.appOpenAnim + dt * 7.5F);
        float diff = this.scroll - this.smoothScroll;
        if (Math.abs(diff) < 0.25F) {
            this.smoothScroll = this.scroll;
        } else {
            this.smoothScroll += diff * Math.min(1.0F, dt * 18.0F);
        }
    }

    private void switchApp(App target) {
        if (this.openApp != target) {
            this.openApp = target;
            this.scroll = 0;
            this.smoothScroll = 0.0F;
            this.appOpenAnim = reducedMotion() ? 1.0F : 0.0F;
            this.searchFocused = false;
        }
    }

    private void showFeedback(String msg, int color) {
        this.feedbackText = msg;
        this.feedbackColor = color;
        this.feedbackUntilMs = Util.getMillis() + 3200L;
    }

    // ---- rendering ---------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        updateAnimations();
        this.hoveredUnit = null;
        this.navTabs.clear();

        this.renderBackground(g);
        // Chassis wallpaper - dark olive gradient with subtle survey grid
        g.fillGradient(0, 0, this.width, this.height, COL_BG_TOP, COL_BG_BOT);
        for (int x = 0; x < this.width; x += 40) {
            g.fill(x, TASKBAR_H, x + 1, this.height, 0x0AFFFFFF);
        }
        for (int y = TASKBAR_H; y < this.height; y += 40) {
            g.fill(0, y, this.width, y + 1, 0x0AFFFFFF);
        }
        for (int y = TASKBAR_H + 2; y < this.height; y += 4) {
            g.fill(0, y, this.width, y + 1, 0x06FFFFFF);
        }

        renderTaskbar(g, mouseX, mouseY);
        renderIcons(g, mouseX, mouseY);

        if (this.openApp == App.SQUAD) {
            renderSquad(g, mouseX, mouseY);
        } else if (this.openApp == App.TELEPORT) {
            renderTeleport(g, mouseX, mouseY);
        } else if (this.openApp == App.INTEL) {
            renderIntel(g, mouseX, mouseY);
        }

        if (this.hoveredUnit != null) {
            renderUnitTooltip(g, this.hoveredUnit, mouseX, mouseY);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderTaskbar(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(0, 0, this.width, TASKBAR_H, COL_TASKBAR);
        g.fill(0, TASKBAR_H - 1, this.width, TASKBAR_H, COL_PANEL_EDGE);

        String brand = "FIELD-LINK // TACNET";
        g.drawString(this.font, brand, 8, 7, COL_ACCENT, false);
        int pipX = 8 + this.font.width(brand) + 6;
        g.fill(pipX, 9, pipX + 4, 13, COL_OK);
        g.fill(pipX + 6, 9, pipX + 10, 13, COL_ACCENT);

        // Quick app switcher tabs in the taskbar
        int tabX = pipX + 20;
        String[] tabNames = {"1: SQUAD", "2: TELEPORT", "3: INTEL"};
        App[] apps = App.values();
        for (int i = 0; i < apps.length; i++) {
            int tw = this.font.width(tabNames[i]) + 12;
            boolean active = this.openApp == apps[i];
            boolean hover = mouseX >= tabX && mouseX < tabX + tw && mouseY >= 3 && mouseY < TASKBAR_H - 3;
            int bg = active ? 0xFF283218 : (hover ? 0xFF1A2210 : 0xFF10140A);
            g.fill(tabX, 3, tabX + tw, TASKBAR_H - 3, bg);
            g.fill(tabX, TASKBAR_H - 4, tabX + tw, TASKBAR_H - 3, active ? COL_ACCENT : COL_PANEL_EDGE);
            g.drawCenteredString(this.font, tabNames[i], tabX + tw / 2, 7,
                    active ? COL_ACCENT_BRIGHT : (hover ? COL_TEXT : COL_SUB));
            this.navTabs.add(new int[]{tabX, 3, tw, TASKBAR_H - 6, i});
            tabX += tw + 4;
        }

        Minecraft mc = Minecraft.getInstance();
        String right = mc.player == null ? "" : mc.player.getGameProfile().getName();
        if (mc.level != null) {
            long t = (mc.level.getDayTime() + 6000L) % 24000L;
            right = String.format("%02d:%02d  %s", t / 1000, (t % 1000) * 60 / 1000, right);
        }
        g.drawString(this.font, right, this.width - 8 - this.font.width(right), 7, COL_SUB, false);
    }

    private void renderIcons(GuiGraphics g, int mouseX, int mouseY) {
        int cx = this.width / 2;
        int cy = this.height / 2 - 28;
        drawAppIcon(g, cx - 108, cy, mouseX, mouseY, 0xFF3E4E24, "Squad", () -> {
            g.fill(cx - 108 + 9, cy + 9, cx - 108 + 13, cy + 25, 0xFFDFF5E8);
            g.fill(cx - 108 + 15, cy + 9, cx - 108 + 19, cy + 25, 0xFFB8E4C8);
            g.fill(cx - 108 + 21, cy + 9, cx - 108 + 25, cy + 25, 0xFF8FD3AC);
        });
        drawAppIcon(g, cx - 17, cy, mouseX, mouseY, 0xFF4E482A, "Teleport", () -> {
            int ix = cx - 17;
            g.fill(ix + 15, cy + 8, ix + 19, cy + 12, 0xFFD8C8FF);
            g.fill(ix + 10, cy + 13, ix + 14, cy + 17, 0xFFB89AF0);
            g.fill(ix + 20, cy + 13, ix + 24, cy + 17, 0xFFB89AF0);
            g.fill(ix + 15, cy + 18, ix + 19, cy + 22, 0xFFD8C8FF);
            g.fill(ix + 15, cy + 13, ix + 19, cy + 17, 0xFFF4ECFF);
        });
        drawAppIcon(g, cx + 74, cy, mouseX, mouseY, 0xFF5E4222, "Intel", () -> {
            g.fill(cx + 74 + 7, cy + 18, cx + 74 + 11, cy + 25, 0xFFF5E6C0);
            g.fill(cx + 74 + 14, cy + 13, cx + 74 + 18, cy + 25, 0xFFF5E6C0);
            g.fill(cx + 74 + 21, cy + 8, cx + 74 + 25, cy + 25, 0xFFF5E6C0);
        });
    }

    private void drawAppIcon(GuiGraphics g, int x, int y, int mouseX, int mouseY,
                             int color, String label, Runnable glyph) {
        boolean hover = this.openApp == null
                && mouseX >= x && mouseX < x + ICON_SIZE
                && mouseY >= y && mouseY < y + ICON_SIZE;
        g.fill(x - 1, y - 1, x + ICON_SIZE + 1, y + ICON_SIZE + 1,
                hover ? COL_ACCENT_BRIGHT : 0x88000000);
        g.fill(x, y, x + ICON_SIZE, y + ICON_SIZE, color);
        glyph.run();
        g.drawCenteredString(this.font, label,
                x + ICON_SIZE / 2, y + ICON_SIZE + 5,
                hover ? 0xFFFFFFFF : COL_SUB);
    }

    private void openWindow(GuiGraphics g, int mouseX, int mouseY, String title) {
        float ease = 1.0F - (float) Math.pow(1.0F - this.appOpenAnim, 3.0);
        this.winW = Math.min(600, Math.max(320, this.width - 28));
        this.winH = Math.min(330, Math.max(200, this.height - TASKBAR_H - 18));
        this.winX = (this.width - this.winW) / 2;
        int targetY = TASKBAR_H + (this.height - TASKBAR_H - this.winH) / 2;
        this.winY = targetY + Math.round((1.0F - ease) * 8.0F);

        // Drop shadow + rugged double bezel
        g.fill(this.winX + 3, this.winY + 3, this.winX + this.winW + 4,
                this.winY + this.winH + 4, 0x66000000);
        g.fill(this.winX - 2, this.winY - 2, this.winX + this.winW + 2,
                this.winY + this.winH + 2, 0xFF252A18);
        g.fill(this.winX - 1, this.winY - 1, this.winX + this.winW + 1,
                this.winY + this.winH + 1, COL_PANEL_EDGE);
        g.fill(this.winX, this.winY, this.winX + this.winW,
                this.winY + this.winH, COL_PANEL);

        // Corner bolts
        for (int sx : new int[]{this.winX - 1, this.winX + this.winW - 1}) {
            for (int sy : new int[]{this.winY - 1, this.winY + this.winH - 1}) {
                g.fill(sx, sy, sx + 2, sy + 2, 0xFF9AA27C);
            }
        }

        // Title strip
        g.fill(this.winX, this.winY, this.winX + this.winW, this.winY + 20, COL_TITLE);
        g.fill(this.winX, this.winY + 20, this.winX + this.winW, this.winY + 21, COL_PANEL_EDGE);
        g.drawString(this.font, title.toUpperCase(Locale.ROOT),
                this.winX + 8, this.winY + 6, COL_ACCENT, false);

        // Inline toast feedback if active
        if (Util.getMillis() < this.feedbackUntilMs && !this.feedbackText.isEmpty()) {
            int fw = this.font.width(this.feedbackText) + 10;
            int fx = this.winX + this.winW - 24 - fw;
            if (fx > this.winX + 180) {
                g.fill(fx, this.winY + 3, fx + fw, this.winY + 17, 0xFF141A0E);
                g.fill(fx, this.winY + 16, fx + fw, this.winY + 17, this.feedbackColor);
                g.drawString(this.font, this.feedbackText, fx + 5, this.winY + 6, this.feedbackColor, false);
            }
        }

        // Close button
        int bx = this.winX + this.winW - 18;
        boolean closeHov = mouseX >= bx && mouseX < bx + 14
                && mouseY >= this.winY + 3 && mouseY < this.winY + 17;
        g.fill(bx, this.winY + 3, bx + 14, this.winY + 17, closeHov ? 0xFFA8382C : 0xFF7A2E24);
        g.drawCenteredString(this.font, "x", bx + 7, this.winY + 5, 0xFFFFFFFF);
    }

    private List<UnitMarker> ownedUnits() {
        List<UnitMarker> own = new ArrayList<>();
        for (UnitMarker m : UnitMarkerStore.markers()) {
            if (m.allegiance() == UnitMarker.Allegiance.OWN) {
                own.add(m);
            }
        }
        if (this.filterMode == FilterMode.CRIT) {
            own.removeIf(m -> !isCritical(m));
        } else if (this.filterMode == FilterMode.DUTY) {
            own.removeIf(m -> m.dutyMode() == 0 && !m.sleeping());
        } else if (this.filterMode == FilterMode.FOLLOW) {
            own.removeIf(m -> m.dutyMode() != 0 || m.sleeping());
        }
        if (!this.searchQuery.isBlank()) {
            String q = this.searchQuery.trim().toLowerCase(Locale.ROOT);
            own.removeIf(m -> !matchesSearch(m, q));
        }
        Comparator<UnitMarker> cmp = switch (this.sortField) {
            case CLASS -> Comparator.comparingInt(UnitMarker::classOrd);
            case HP -> Comparator.comparingDouble(UnitMarker::healthFrac);
            case AMMO -> Comparator.comparingInt(
                    (UnitMarker m) -> m.ammoInfinite() ? Integer.MAX_VALUE : m.ammoRounds());
            case SUPPLY -> Comparator.comparingInt(this::supplyScore);
            case NEEDS -> Comparator.comparingInt(this::needScore);
            case STATUS -> Comparator.comparingInt((UnitMarker m) -> -m.dutyMode());
            case NAME -> Comparator.comparing((UnitMarker m) -> m.name().toLowerCase(Locale.ROOT));
        };
        own.sort((this.sortAsc ? cmp : cmp.reversed())
                .thenComparingInt(UnitMarker::entityId));
        return own;
    }

    private static boolean matchesSearch(UnitMarker m, String q) {
        String full = ((m.squad().isEmpty() ? "" : m.squad() + " ")
                + (m.rankTitle().isEmpty() ? "" : m.rankTitle() + " ")
                + (m.name().isEmpty() ? "unit #" + m.entityId() : m.name())
                + " " + classLabel(m) + " " + statusLabel(m)).toLowerCase(Locale.ROOT);
        return full.contains(q);
    }

    private int supplyScore(UnitMarker m) {
        return (m.hasMedical() ? 2 : 0) + (m.hasFood() ? 1 : 0);
    }

    private int needScore(UnitMarker m) {
        if (m.hunger() < 0) {
            return Integer.MAX_VALUE;
        }
        return Math.min(m.hunger(), Math.min(m.energy(), m.morale() / 5));
    }

    private static boolean isCritical(UnitMarker m) {
        if (m.healthFrac() < 0.35F) {
            return true;
        }
        return m.hunger() >= 0
                && (m.hunger() <= 2 || m.energy() <= 1 || m.morale() < 25);
    }

    // ---- SQUAD ROSTER APP --------------------------------------------------

    private void renderSquad(GuiGraphics g, int mouseX, int mouseY) {
        this.headerSorts.clear();
        this.filterPills.clear();
        this.orderButtons.clear();
        this.searchBoxRect = null;
        this.searchClearRect = null;

        List<UnitMarker> units = ownedUnits();
        int totalOwn = 0;
        int totalCrit = 0;
        for (UnitMarker m : UnitMarkerStore.markers()) {
            if (m.allegiance() == UnitMarker.Allegiance.OWN) {
                totalOwn++;
                if (isCritical(m)) {
                    totalCrit++;
                }
            }
        }
        int selected = UnitMarkerStore.selectedUnitIds().size();
        openWindow(g, mouseX, mouseY, "Squad Roster  [" + units.size() + "/" + totalOwn + " units]"
                + (selected > 0 ? "  •  " + selected + " selected" : "")
                + (totalCrit > 0 ? "  •  " + totalCrit + " CRIT" : ""));

        int cx = this.winX + 8;
        int cw = this.winW - 16;
        int cy = this.winY + 24;

        // 1. Search & Filter Toolbar
        int searchW = Math.min(165, Math.max(95, cw / 3));
        boolean sHov = mouseX >= cx && mouseX < cx + searchW && mouseY >= cy && mouseY < cy + 14;
        g.fill(cx, cy, cx + searchW, cy + 14, 0xFF0A0D07);
        g.fill(cx, cy + 13, cx + searchW, cy + 14,
                this.searchFocused ? COL_ACCENT_BRIGHT : (sHov ? COL_ACCENT : COL_PANEL_EDGE));
        String sDisp = this.searchQuery.isEmpty()
                ? (this.searchFocused ? "|" : "Search callsign / class...")
                : this.font.plainSubstrByWidth(this.searchQuery, searchW - 22) + (this.searchFocused ? "|" : "");
        g.drawString(this.font, sDisp, cx + 4, cy + 3,
                this.searchQuery.isEmpty() && !this.searchFocused ? COL_MUTED : COL_TEXT, false);
        this.searchBoxRect = new int[]{cx, cy, searchW, 14};

        if (!this.searchQuery.isEmpty()) {
            int clx = cx + searchW - 13;
            boolean clHov = mouseX >= clx && mouseX < clx + 11 && mouseY >= cy + 1 && mouseY < cy + 13;
            g.drawString(this.font, "x", clx + 3, cy + 3, clHov ? 0xFFFFFFFF : COL_SUB, false);
            this.searchClearRect = new int[]{clx, cy + 1, 11, 12};
        }

        // Filter pills on the right of search bar
        int fx = cx + searchW + 8;
        String[] fLabels = {"ALL", "CRIT", "ON DUTY", "FOLLOW"};
        FilterMode[] fModes = FilterMode.values();
        for (int i = 0; i < fModes.length; i++) {
            int pw = this.font.width(fLabels[i]) + 10;
            if (fx + pw > cx + cw) {
                break;
            }
            boolean fAct = this.filterMode == fModes[i];
            boolean fHov = mouseX >= fx && mouseX < fx + pw && mouseY >= cy && mouseY < cy + 14;
            int fBg = fAct
                    ? (fModes[i] == FilterMode.CRIT ? 0xFF7A2E24 : 0xFF34421E)
                    : (fHov ? 0xFF252E18 : 0xFF181E10);
            g.fill(fx, cy, fx + pw, cy + 14, fBg);
            g.fill(fx, cy + 13, fx + pw, cy + 14, fAct ? COL_ACCENT : COL_PANEL_EDGE);
            g.drawCenteredString(this.font, fLabels[i], fx + pw / 2, cy + 3,
                    fAct ? 0xFFFFFFFF : (fHov ? COL_TEXT : COL_SUB));
            this.filterPills.add(new int[]{fx, cy, pw, 14, i});
            fx += pw + 4;
        }
        cy += 17;

        // 2. Responsive Column Layout (prevents overflow on smaller viewports)
        // Compact mode drops STATUS/NEEDS columns - that info stays on the
        // row hover tooltip instead of overflowing the window edge.
        boolean compact = cw < 430;
        int colCheck = cx + 2;
        int colName = cx + 14;
        int wClass = compact ? 50 : 74;
        int wHp = compact ? 66 : 92;
        int wAmmo = compact ? 30 : 38;
        int wSupply = compact ? 42 : 46;
        int wStatus = compact ? 0 : 62;
        int wNeeds = compact ? 0 : 30;
        int gaps = compact ? 4 * 4 : 6 * 4;
        int wName = Math.max(56, cw - 14 - wClass - wHp - wAmmo - wSupply
                - wStatus - wNeeds - gaps - 4);
        int colClass = colName + wName + 4;
        int colHp = colClass + wClass + 4;
        int colAmmo = colHp + wHp + 4;
        int colSupply = colAmmo + wAmmo + 4;
        int colStatus = colSupply + wSupply + 4;
        int colNeeds = colStatus + wStatus + 4;

        // Table Header Bar
        g.fill(cx - 2, cy - 2, cx + cw + 2, cy + 11, COL_HEADER_BG);
        g.fill(cx - 2, cy + 10, cx + cw + 2, cy + 11, COL_PANEL_EDGE);
        drawHeader(g, mouseX, mouseY, colName, cy, wName, "CALLSIGN", SortField.NAME);
        drawHeader(g, mouseX, mouseY, colClass, cy, wClass, "CLASS", SortField.CLASS);
        drawHeader(g, mouseX, mouseY, colHp, cy, wHp, "HEALTH", SortField.HP);
        drawHeader(g, mouseX, mouseY, colAmmo, cy, wAmmo, "AMMO", SortField.AMMO);
        drawHeader(g, mouseX, mouseY, colSupply, cy, wSupply, "KIT", SortField.SUPPLY);
        if (!compact) {
            drawHeader(g, mouseX, mouseY, colStatus, cy, wStatus, "STATUS", SortField.STATUS);
            drawHeader(g, mouseX, mouseY, colNeeds, cy, wNeeds, "NEEDS", SortField.NEEDS);
        }
        cy += 12;

        // 3. Scrollable Roster Rows
        int viewTop = cy;
        int viewH = this.winY + this.winH - 26 - viewTop;
        this.rosterViewTop = viewTop;
        this.rosterViewH = viewH;
        int contentH = units.size() * ROW_H;
        this.maxScrollCached = Math.max(0, contentH - viewH);
        this.scroll = Mth.clamp(this.scroll, 0, this.maxScrollCached);
        int renderScroll = Math.round(Mth.clamp(this.smoothScroll, 0.0F, this.maxScrollCached));

        g.enableScissor(cx - 2, viewTop, cx + cw + 2, viewTop + viewH);
        int ry = viewTop - renderScroll;
        for (int idx = 0; idx < units.size(); idx++) {
            UnitMarker m = units.get(idx);
            if (ry + ROW_H > viewTop && ry < viewTop + viewH) {
                boolean sel = UnitMarkerStore.isSelected(m);
                boolean hover = mouseX >= cx && mouseX < cx + cw - 6
                        && mouseY >= ry && mouseY < ry + ROW_H
                        && mouseY >= viewTop && mouseY < viewTop + viewH;
                if (hover) {
                    this.hoveredUnit = m;
                }

                // Zebra row background + selection & hover states
                int rowBg = sel ? COL_ROW_SEL : (hover ? COL_ROW_HOVER : (idx % 2 == 1 ? COL_PANEL_ALT : COL_PANEL));
                g.fill(cx - 2, ry, cx + cw - 4, ry + ROW_H - 1, rowBg);
                g.fill(cx - 2, ry + ROW_H - 1, cx + cw - 4, ry + ROW_H, 0x22000000);
                if (sel) {
                    g.fill(cx - 2, ry, cx, ry + ROW_H - 1, COL_ACCENT_BRIGHT);
                }

                // Selection checkbox
                g.fill(colCheck, ry + 4, colCheck + 8, ry + 12, sel ? COL_ACCENT : COL_PANEL_EDGE);
                g.fill(colCheck + 1, ry + 5, colCheck + 7, ry + 11, sel ? 0xFF3E3216 : COL_PANEL);
                if (sel) {
                    g.fill(colCheck + 2, ry + 6, colCheck + 6, ry + 10, COL_ACCENT_BRIGHT);
                }

                // Callsign + Squad + Rank
                String name = (m.squad().isEmpty() ? "" : "[" + m.squad() + "] ")
                        + (m.rankTitle().isEmpty() ? "" : m.rankTitle() + " ")
                        + (m.name().isEmpty() ? "Unit #" + m.entityId() : m.name());
                g.drawString(this.font, this.font.plainSubstrByWidth(name, wName - 2),
                        colName, ry + 4, sel ? COL_ACCENT_BRIGHT : COL_TEXT, false);

                // Class badge pill
                renderClassBadge(g, m, colClass, ry + 3, wClass - 2);

                // Health bar + numbers
                renderHp(g, m, colHp, ry + 4, wHp - 4);

                // Ammo reserve
                if (m.ammoInfinite()) {
                    g.drawString(this.font, "INF", colAmmo, ry + 4, COL_ACCENT, false);
                } else {
                    int ammoCol = m.ammoRounds() > 30 ? COL_TEXT : (m.ammoRounds() > 0 ? COL_WARN : COL_BAD);
                    g.drawString(this.font, String.valueOf(m.ammoRounds()), colAmmo, ry + 4, ammoCol, false);
                }

                // Supplies (Medical & Ration pills)
                renderSupplyPills(g, m, colSupply, ry + 3);

                // Status / Duty badge + Needs micro-bars (full-width mode only;
                // compact rows expose both via the hover tooltip)
                if (!compact) {
                    renderStatusBadge(g, m, colStatus, ry + 4, wStatus - 2);
                    renderNeeds(g, m, colNeeds, ry + 4);
                }
            }
            ry += ROW_H;
        }
        g.disableScissor();

        if (units.isEmpty()) {
            String emptyMsg = !this.searchQuery.isBlank()
                    ? "No units match \"" + this.searchQuery + "\" — press ESC to clear search."
                    : (this.filterMode != FilterMode.ALL
                    ? "No units match the active filter (" + this.filterMode.name() + ")."
                    : "No owned units in this dimension — recruit at a Recruit Table.");
            g.drawCenteredString(this.font, emptyMsg,
                    this.winX + this.winW / 2, viewTop + viewH / 2 - 4, COL_ACCENT_DIM);
        }

        renderScrollbar(g, viewTop, viewH, contentH, renderScroll);
        renderOrderBar(g, mouseX, mouseY);
    }

    private void renderClassBadge(GuiGraphics g, UnitMarker m, int x, int y, int maxW) {
        String label = this.font.plainSubstrByWidth(classLabel(m), maxW - 6);
        int accent = COL_SUB;
        if (m.classOrd() >= 0 && m.classOrd() < WeaponClass.values().length) {
            accent = 0xFF000000 | SemcsRegistries.classAccent(WeaponClass.values()[m.classOrd()]);
        }
        int bw = Math.min(maxW, this.font.width(label) + 6);
        g.fill(x, y, x + bw, y + 10, 0xFF1A2012);
        g.fill(x, y, x + 2, y + 10, accent);
        g.drawString(this.font, label, x + 4, y + 1, accent, false);
    }

    private void renderSupplyPills(GuiGraphics g, UnitMarker m, int x, int y) {
        // Medical badge
        int medBg = m.hasMedical() ? 0xFF2E1A1A : 0xFF161A12;
        int medFg = m.hasMedical() ? 0xFFE86A6A : COL_MUTED;
        g.fill(x, y, x + 17, y + 10, medBg);
        g.drawCenteredString(this.font, m.hasMedical() ? "+M" : "-M", x + 8, y + 1, medFg);

        // Food badge
        int foodBg = m.hasFood() ? 0xFF2A2414 : 0xFF161A12;
        int foodFg = m.hasFood() ? 0xFFE3B86F : COL_MUTED;
        g.fill(x + 19, y, x + 36, y + 10, foodBg);
        g.drawCenteredString(this.font, m.hasFood() ? "+F" : "-F", x + 27, y + 1, foodFg);
    }

    private void renderStatusBadge(GuiGraphics g, UnitMarker m, int x, int y, int maxW) {
        String label = this.font.plainSubstrByWidth(statusLabel(m), maxW);
        int col = COL_SUB;
        if (m.sleeping()) {
            col = 0xFF9EA8E8;
        } else if (m.dutyMode() != 0) {
            col = COL_ACCENT_BRIGHT;
        } else if (m.orderOrd() >= 0 && m.orderOrd() < OrderType.values().length) {
            OrderType ord = OrderType.values()[m.orderOrd()];
            col = switch (ord) {
                case FOLLOW_COMMANDER, FREE_FIRE -> COL_OK;
                case HOLD_POSITION -> 0xFF6FD2E3;
                case CEASE_FIRE -> COL_BAD;
                default -> COL_SUB;
            };
        }
        g.drawString(this.font, label, x, y, col, false);
    }

    private void drawHeader(GuiGraphics g, int mouseX, int mouseY, int x, int y, int w,
                            String label, SortField field) {
        boolean active = this.sortField == field;
        boolean hover = mouseX >= x - 2 && mouseX < x + w && mouseY >= y - 2 && mouseY < y + 10;
        String arrow = active ? (this.sortAsc ? " ^" : " v") : "";
        g.drawString(this.font, label + arrow, x, y,
                active ? COL_ACCENT_BRIGHT : (hover ? COL_TEXT : COL_ACCENT_DIM), false);
        this.headerSorts.add(new int[]{x - 2, y - 2, w + 2, field.ordinal()});
    }

    private void renderScrollbar(GuiGraphics g, int viewTop, int viewH, int contentH, int renderScroll) {
        if (this.maxScrollCached <= 0 || viewH <= 0) {
            return;
        }
        int trackX = this.winX + this.winW - 6;
        g.fill(trackX, viewTop, trackX + 3, viewTop + viewH, COL_TASKBAR);
        int thumbH = Math.max(14, viewH * viewH / Math.max(1, contentH));
        int thumbY = viewTop + (viewH - thumbH) * renderScroll / this.maxScrollCached;
        g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, COL_ACCENT);
    }

    private void renderOrderBar(GuiGraphics g, int mouseX, int mouseY) {
        int y = this.winY + this.winH - 20;
        g.fill(this.winX + 4, y - 3, this.winX + this.winW - 4, y + 15, COL_TITLE);
        g.fill(this.winX + 4, y - 3, this.winX + this.winW - 4, y - 2, COL_PANEL_EDGE);
        int x = this.winX + 8;
        drawBarButton(g, mouseX, mouseY, x, y, 32, "ALL", -1);
        x += 35;
        drawBarButton(g, mouseX, mouseY, x, y, 38, "NONE", -2);
        x += 44;
        g.drawString(this.font, "ORDERS:", x, y + 2, COL_ACCENT_DIM, false);
        x += 44;
        String[] labels = {"FOLLOW", "HOLD", "WEDGE", "COLUMN", "FREE", "CEASE"};
        OrderType[] orders = {OrderType.FOLLOW_COMMANDER, OrderType.HOLD_POSITION,
                OrderType.FORM_WEDGE, OrderType.FORM_COLUMN,
                OrderType.FREE_FIRE, OrderType.CEASE_FIRE};
        for (int i = 0; i < labels.length; i++) {
            int w = this.font.width(labels[i]) + 10;
            if (x + w > this.winX + this.winW - 6) {
                break;
            }
            drawBarButton(g, mouseX, mouseY, x, y, w, labels[i], orders[i].ordinal());
            x += w + 4;
        }
    }

    private void drawBarButton(GuiGraphics g, int mouseX, int mouseY,
                               int x, int y, int w, String label, int code) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 12;
        g.fill(x, y, x + w, y + 12, hover ? 0xFF424C2A : 0xFF232818);
        g.fill(x, y + 11, x + w, y + 12, hover ? COL_ACCENT_BRIGHT : 0xFF3A4030);
        g.drawCenteredString(this.font, label, x + w / 2, y + 2,
                hover ? COL_ACCENT_BRIGHT : COL_TEXT);
        this.orderButtons.add(new int[]{x, y, w, code});
    }

    private void renderHp(GuiGraphics g, UnitMarker m, int x, int y, int maxColW) {
        int barW = Math.min(36, Math.max(18, maxColW - 34));
        int filled = Math.round(Mth.clamp(m.healthFrac(), 0.0F, 1.0F) * barW);
        int col = m.healthFrac() > 0.5F ? 0xFF4BC06A
                : m.healthFrac() > 0.25F ? 0xFFE3B86F : 0xFFC0403A;
        g.fill(x, y + 1, x + barW, y + 6, 0xFF090C06);
        if (filled > 0) {
            g.fill(x, y + 1, x + filled, y + 6, col);
        }
        String hp = (int) Math.ceil(m.health()) + "/" + (int) Math.ceil(m.maxHealth());
        g.drawString(this.font, hp, x + barW + 4, y, 0xFFC8D2DC, false);
    }

    private void renderNeeds(GuiGraphics g, UnitMarker m, int x, int y) {
        if (m.hunger() < 0) {
            g.drawString(this.font, "OFF", x, y, COL_MUTED, false);
            return;
        }
        needBar(g, x, y, m.hunger() / 20.0, 0xFFE3B86F);
        needBar(g, x + 5, y, m.energy() / 20.0, 0xFF6FE3C1);
        needBar(g, x + 10, y, m.morale() / 100.0, 0xFFA6E36F);
        if (m.hunger() <= 2 || m.energy() <= 1 || m.morale() < 25) {
            g.drawString(this.font, "!", x + 16, y - 1, COL_BAD, false);
        }
    }

    private static void needBar(GuiGraphics g, int x, int y, double frac, int color) {
        int h = Math.max(0, Math.min(8, (int) Math.round(frac * 8)));
        g.fill(x, y - 1, x + 3, y + 7, 0xFF090C06);
        if (h > 0) {
            g.fill(x, y + 7 - h, x + 3, y + 7, color);
        }
    }

    private void renderUnitTooltip(GuiGraphics g, UnitMarker m, int mouseX, int mouseY) {
        List<Component> lines = new ArrayList<>();
        String title = (m.rankTitle().isEmpty() ? "" : m.rankTitle() + " ")
                + (m.name().isEmpty() ? "Unit #" + m.entityId() : m.name())
                + (m.squad().isEmpty() ? "" : "  [" + m.squad() + "]");
        lines.add(Component.literal(title).withStyle(net.minecraft.ChatFormatting.GOLD));
        lines.add(Component.literal("Class: " + classLabel(m) + "  •  Status: " + statusLabel(m))
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        Minecraft mc = Minecraft.getInstance();
        int dist = mc.player != null
                ? (int) Math.round(Math.sqrt(mc.player.distanceToSqr(m.x(), m.y(), m.z())))
                : 0;
        lines.add(Component.literal(String.format(Locale.ROOT,
                "Pos: %d, %d, %d (%dm)  •  HP: %d/%d",
                (int) m.x(), (int) m.y(), (int) m.z(), dist,
                (int) Math.ceil(m.health()), (int) Math.ceil(m.maxHealth())))
                .withStyle(net.minecraft.ChatFormatting.WHITE));
        if (m.hunger() >= 0) {
            lines.add(Component.literal(String.format(Locale.ROOT,
                    "Needs: Hunger %d/20  •  Energy %d/20  •  Morale %d%%",
                    m.hunger(), m.energy(), m.morale()))
                    .withStyle(isCritical(m)
                            ? net.minecraft.ChatFormatting.RED
                            : net.minecraft.ChatFormatting.GREEN));
        }
        g.renderTooltip(this.font, lines, Optional.empty(), mouseX, mouseY);
    }

    // ---- TELEPORT APP ------------------------------------------------------

    private void renderTeleport(GuiGraphics g, int mouseX, int mouseY) {
        this.tpTargets.clear();
        this.orderButtons.clear();
        this.waypoints = com.poseidon.sem_classsystem.client.xaero.SemcsWaypoints.current();
        List<UnitMarker> units = ownedUnits();
        int selected = UnitMarkerStore.selectedUnitIds().size();
        openWindow(g, mouseX, mouseY, "Tactical Teleport  [" + selected + "/" + units.size() + " selected]");

        int cx = this.winX + 10;
        int cw = this.winW - 20;
        int y = this.winY + 26;

        // Quick destination buttons + ALL / NONE selection helpers on the same bar
        int btnW = 104;
        drawTpButton(g, mouseX, mouseY, cx, y, btnW, "TO ME", -1);
        drawTpButton(g, mouseX, mouseY, cx + btnW + 6, y, btnW + 14, "TO CROSSHAIR", -2);
        drawBarButton(g, mouseX, mouseY, cx + cw - 78, y + 1, 36, "ALL", -1);
        drawBarButton(g, mouseX, mouseY, cx + cw - 38, y + 1, 38, "NONE", -2);
        y += 18;

        // Waypoints section (capped at 4 visible rows so unit roster always has room)
        g.drawString(this.font, "XAERO WAYPOINTS:", cx, y, COL_ACCENT_DIM, false);
        y += 10;
        if (this.waypoints.isEmpty()) {
            g.drawString(this.font,
                    com.poseidon.sem_classsystem.client.xaero.SemcsWaypoints.available()
                            ? "  - No waypoints in this dimension -"
                            : "  - Xaero's Minimap not installed -",
                    cx, y, COL_MUTED, false);
            y += 12;
        } else {
            int maxWp = Math.min(this.waypoints.size(), 4);
            for (int i = 0; i < maxWp; i++) {
                var wp = this.waypoints.get(i);
                g.fill(cx, y, cx + cw - 4, y + 13, i % 2 == 1 ? COL_PANEL_ALT : 0xFF141A0E);
                g.drawString(this.font, this.font.plainSubstrByWidth(wp.name(), 160),
                        cx + 4, y + 3, COL_TEXT, false);
                g.drawString(this.font, wp.x() + ", " + wp.y() + ", " + wp.z(),
                        cx + 170, y + 3, COL_SUB, false);
                int gx = cx + cw - 44;
                boolean hov = mouseX >= gx && mouseX < gx + 38
                        && mouseY >= y + 1 && mouseY < y + 12;
                g.fill(gx, y + 1, gx + 38, y + 12, hov ? 0xFF424C2A : 0xFF232818);
                g.drawCenteredString(this.font, "GO", gx + 19, y + 2,
                        hov ? COL_ACCENT_BRIGHT : COL_TEXT);
                this.tpTargets.add(new int[]{gx, y + 1, 38, i});
                y += 14;
            }
            y += 2;
        }

        // Unit multi-select roster table
        g.drawString(this.font, "SELECT UNITS TO DEPLOY:", cx, y, COL_ACCENT_DIM, false);
        y += 10;
        int viewTop = y;
        int viewH = this.winY + this.winH - 8 - viewTop;
        this.rosterViewTop = viewTop;
        this.rosterViewH = viewH;
        int contentH = units.size() * ROW_H;
        this.maxScrollCached = Math.max(0, contentH - viewH);
        this.scroll = Mth.clamp(this.scroll, 0, this.maxScrollCached);
        int renderScroll = Math.round(Mth.clamp(this.smoothScroll, 0.0F, this.maxScrollCached));

        g.enableScissor(cx - 2, viewTop, cx + cw + 2, viewTop + viewH);
        int ry = viewTop - renderScroll;
        for (int idx = 0; idx < units.size(); idx++) {
            UnitMarker m = units.get(idx);
            if (ry + ROW_H > viewTop && ry < viewTop + viewH) {
                boolean sel = UnitMarkerStore.isSelected(m);
                boolean hover = mouseX >= cx && mouseX < cx + cw - 6
                        && mouseY >= ry && mouseY < ry + ROW_H
                        && mouseY >= viewTop && mouseY < viewTop + viewH;
                int rowBg = sel ? COL_ROW_SEL : (hover ? COL_ROW_HOVER : (idx % 2 == 1 ? COL_PANEL_ALT : COL_PANEL));
                g.fill(cx - 2, ry, cx + cw - 4, ry + ROW_H - 1, rowBg);
                if (sel) {
                    g.fill(cx - 2, ry, cx, ry + ROW_H - 1, COL_ACCENT_BRIGHT);
                }
                g.fill(cx + 2, ry + 4, cx + 10, ry + 12, sel ? COL_ACCENT : COL_PANEL_EDGE);
                g.fill(cx + 3, ry + 5, cx + 9, ry + 11, sel ? 0xFF3A2F18 : COL_PANEL);
                if (sel) {
                    g.fill(cx + 4, ry + 6, cx + 8, ry + 10, COL_ACCENT_BRIGHT);
                }
                String name = (m.rankTitle().isEmpty() ? "" : m.rankTitle() + " ")
                        + (m.name().isEmpty() ? "Unit #" + m.entityId() : m.name());
                // Right-anchored columns so the roster stays inside the
                // window at any width: CLASS 68px + HP 76px + separators.
                int wName = Math.max(48, cw - 16 - 68 - 76 - 20);
                int cxClass = cx + cw - 4 - 76 - 8 - 68;
                int cxHp = cx + cw - 4 - 76;
                g.drawString(this.font, this.font.plainSubstrByWidth(name, wName),
                        cx + 16, ry + 4, sel ? COL_ACCENT_BRIGHT : COL_TEXT, false);
                renderClassBadge(g, m, cxClass, ry + 3, 66);
                renderHp(g, m, cxHp, ry + 4, 74);
            }
            ry += ROW_H;
        }
        g.disableScissor();

        if (units.isEmpty()) {
            g.drawCenteredString(this.font,
                    "No owned units in this dimension.",
                    this.winX + this.winW / 2, viewTop + viewH / 2, COL_ACCENT_DIM);
        }
        renderScrollbar(g, viewTop, viewH, contentH, renderScroll);
    }

    private void drawTpButton(GuiGraphics g, int mouseX, int mouseY,
                              int x, int y, int w, String label, int code) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 14;
        g.fill(x, y, x + w, y + 14, hover ? 0xFF424C2A : 0xFF232818);
        g.fill(x, y + 13, x + w, y + 14, hover ? COL_ACCENT_BRIGHT : COL_ACCENT_DIM);
        g.drawCenteredString(this.font, label, x + w / 2, y + 3,
                hover ? COL_ACCENT_BRIGHT : 0xFFFFFFFF);
        this.tpTargets.add(new int[]{x, y, w, code});
    }

    // ---- INTEL APP ---------------------------------------------------------

    private void renderIntel(GuiGraphics g, int mouseX, int mouseY) {
        openWindow(g, mouseX, mouseY, "Tactical Intelligence & SITREP");
        Minecraft mc = Minecraft.getInstance();
        int x = this.winX + 12;
        int y = this.winY + 28;
        int cw = this.winW - 24;

        int ownCount = 0;
        int spotted = 0;
        int critCount = 0;
        for (UnitMarker m : UnitMarkerStore.markers()) {
            if (m.allegiance() == UnitMarker.Allegiance.OWN) {
                ownCount++;
                if (isCritical(m)) {
                    critCount++;
                }
            } else {
                spotted++;
            }
        }
        String dim = mc.level != null ? mc.level.dimension().location().toString() : "?";
        int claims = mc.player != null ? UnitMarkerStore.claimCount(mc.player.getUUID()) : 0;

        // Summary metric cards row
        int cardW = (cw - 12) / 4;
        drawIntelCard(g, x, y, cardW, "OWNED SQUAD", String.valueOf(ownCount), COL_OK);
        drawIntelCard(g, x + (cardW + 4), y, cardW, "HOSTILE CONTACTS", String.valueOf(spotted),
                spotted > 0 ? COL_BAD : COL_SUB);
        drawIntelCard(g, x + (cardW + 4) * 2, y, cardW, "CLAIMED CHUNKS",
                claims + " / " + ClassSystemConfig.maxClaimedChunks(), COL_ACCENT);
        drawIntelCard(g, x + (cardW + 4) * 3, y, cardW, "CRITICAL UNITS",
                String.valueOf(critCount), critCount > 0 ? COL_WARN : COL_SUB);
        y += 34;

        g.drawString(this.font, "Commander: " + (mc.player == null ? "?" : mc.player.getGameProfile().getName())
                + "   •   Sector: " + dim
                + "   •   Contiguous claims: " + (ClassSystemConfig.requireContiguousClaims() ? "ENFORCED" : "OFF"),
                x, y, COL_SUB, false);
        y += 14;

        // SIGINT / Raid Early Warning banner
        double[] ping = mc.level == null ? null : UnitMarkerStore.raidPing(dim, mc.level.getGameTime());
        if (ping == null) {
            g.fill(x, y, x + cw, y + 16, 0xFF151C10);
            g.fill(x, y, x + 3, y + 16, COL_OK);
            g.drawString(this.font, "SIGINT STATUS:  All manned guard posts report clear perimeter.",
                    x + 8, y + 4, COL_SUB, false);
        } else {
            double dist = mc.player == null ? 0
                    : Math.sqrt(mc.player.distanceToSqr(ping[0], mc.player.getY(), ping[1]));
            g.fill(x, y, x + cw, y + 16, 0xFF2E1412);
            g.fill(x, y, x + 3, y + 16, COL_BAD);
            g.drawString(this.font, "RAID ALERT:  Hostile incursion detected at "
                            + (int) ping[0] + ", " + (int) ping[1] + " (~" + (int) dist + "m)",
                    x + 8, y + 4, 0xFFFF7A6E, false);
        }
        y += 22;

        // Scrollable Casualty Board
        List<String> kia = UnitMarkerStore.casualties();
        g.drawString(this.font, "CASUALTY LOG (" + kia.size() + " KIA recorded):", x, y, COL_ACCENT_DIM, false);
        y += 11;

        int viewTop = y;
        int viewH = this.winY + this.winH - 10 - viewTop;
        int contentH = kia.size() * 13;
        this.maxScrollCached = Math.max(0, contentH - viewH);
        this.scroll = Mth.clamp(this.scroll, 0, this.maxScrollCached);
        int renderScroll = Math.round(Mth.clamp(this.smoothScroll, 0.0F, this.maxScrollCached));

        g.enableScissor(x, viewTop, x + cw, viewTop + viewH);
        if (kia.isEmpty()) {
            g.drawString(this.font, "  No casualties recorded on this deployment.", x, viewTop + 4, COL_MUTED, false);
        } else {
            int ry = viewTop - renderScroll;
            for (int i = kia.size() - 1; i >= 0; i--) {
                if (ry + 13 > viewTop && ry < viewTop + viewH) {
                    g.fill(x, ry, x + cw - 6, ry + 12, (kia.size() - 1 - i) % 2 == 1 ? COL_PANEL_ALT : COL_PANEL);
                    g.drawString(this.font, this.font.plainSubstrByWidth("KIA  •  " + kia.get(i), cw - 14),
                            x + 4, ry + 2, COL_SUB, false);
                }
                ry += 13;
            }
        }
        g.disableScissor();
        renderScrollbar(g, viewTop, viewH, contentH, renderScroll);
    }

    private void drawIntelCard(GuiGraphics g, int x, int y, int w, String label, String value, int accent) {
        g.fill(x, y, x + w, y + 28, COL_PANEL_ALT);
        g.fill(x, y, x + w, y + 2, accent);
        g.drawString(this.font, this.font.plainSubstrByWidth(label, w - 8), x + 5, y + 5, COL_ACCENT_DIM, false);
        g.drawString(this.font, value, x + 5, y + 16, COL_TEXT, false);
    }

    // ---- input -------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // 1. Taskbar nav tabs
            for (int[] tab : this.navTabs) {
                if (mouseX >= tab[0] && mouseX < tab[0] + tab[2]
                        && mouseY >= tab[1] && mouseY < tab[1] + tab[3]) {
                    UiSounds.click();
                    switchApp(App.values()[tab[4]]);
                    return true;
                }
            }

            // 2. Desktop icons when no window is open
            if (this.openApp == null) {
                int cx = this.width / 2;
                int cy = this.height / 2 - 28;
                if (hitIcon(mouseX, mouseY, cx - 108, cy)) {
                    UiSounds.click();
                    switchApp(App.SQUAD);
                    return true;
                }
                if (hitIcon(mouseX, mouseY, cx - 17, cy)) {
                    UiSounds.click();
                    switchApp(App.TELEPORT);
                    return true;
                }
                if (hitIcon(mouseX, mouseY, cx + 74, cy)) {
                    UiSounds.click();
                    switchApp(App.INTEL);
                    return true;
                }
            }

            if (this.openApp != null) {
                // Window close button
                int bx = this.winX + this.winW - 18;
                if (mouseX >= bx && mouseX < bx + 14
                        && mouseY >= this.winY + 3 && mouseY < this.winY + 17) {
                    UiSounds.click();
                    this.openApp = null;
                    this.searchFocused = false;
                    return true;
                }

                if (this.openApp == App.SQUAD && clickSquadChrome(mouseX, mouseY)) {
                    return true;
                }
                if (this.openApp == App.TELEPORT && clickTeleportChrome(mouseX, mouseY)) {
                    return true;
                }
                if ((this.openApp == App.SQUAD || this.openApp == App.TELEPORT)
                        && clickRosterRow(mouseX, mouseY)) {
                    UiSounds.click();
                    return true;
                }
                if (mouseX >= this.winX && mouseX < this.winX + this.winW
                        && mouseY >= this.winY && mouseY < this.winY + this.winH) {
                    this.searchFocused = false;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean hitIcon(double mx, double my, int x, int y) {
        return mx >= x && mx < x + ICON_SIZE && my >= y && my < y + ICON_SIZE;
    }

    private boolean clickTeleportChrome(double mouseX, double mouseY) {
        for (int[] b : this.orderButtons) {
            if (mouseX >= b[0] && mouseX < b[0] + b[2]
                    && mouseY >= b[1] && mouseY < b[1] + 12) {
                if (b[3] == -1) {
                    List<UnitMarker> visible = ownedUnits();
                    for (UnitMarker m : visible) {
                        UnitMarkerStore.addSelected(m);
                    }
                    showFeedback("Selected " + visible.size() + " unit(s)", COL_ACCENT_BRIGHT);
                } else if (b[3] == -2) {
                    UnitMarkerStore.clearSelection();
                    showFeedback("Selection cleared", COL_SUB);
                }
                UiSounds.click();
                return true;
            }
        }
        for (int[] t : this.tpTargets) {
            if (mouseX < t[0] || mouseX >= t[0] + t[2]
                    || mouseY < t[1] || mouseY >= t[1] + 14) {
                continue;
            }
            List<Integer> ids = new ArrayList<>(UnitMarkerStore.selectedUnitIds());
            if (ids.isEmpty()) {
                UiSounds.click();
                showFeedback("Select at least 1 unit first", COL_BAD);
                return true;
            }
            BlockPos target = null;
            int mode = PacketTeleportUnits.MODE_PLAYER;
            String destLabel = "commander";
            if (t[3] == -2) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
                    target = bhr.getBlockPos().relative(bhr.getDirection());
                }
                if (target == null) {
                    UiSounds.click();
                    showFeedback("Aim crosshair at a block first", COL_BAD);
                    return true;
                }
                mode = PacketTeleportUnits.MODE_POS;
                destLabel = target.toShortString();
            } else if (t[3] >= 0 && t[3] < this.waypoints.size()) {
                var wp = this.waypoints.get(t[3]);
                target = new BlockPos(wp.x(), wp.y() + 1, wp.z());
                mode = PacketTeleportUnits.MODE_POS;
                destLabel = wp.name();
            }
            SemcsNetwork.CHANNEL.sendToServer(new PacketTeleportUnits(ids, mode, target));
            UiSounds.confirm();
            showFeedback("Teleported " + ids.size() + " unit(s) -> " + destLabel, COL_OK);
            return true;
        }
        return false;
    }

    private boolean clickSquadChrome(double mouseX, double mouseY) {
        if (this.searchClearRect != null
                && mouseX >= this.searchClearRect[0]
                && mouseX < this.searchClearRect[0] + this.searchClearRect[2]
                && mouseY >= this.searchClearRect[1]
                && mouseY < this.searchClearRect[1] + this.searchClearRect[3]) {
            this.searchQuery = "";
            this.scroll = 0;
            UiSounds.click();
            return true;
        }
        if (this.searchBoxRect != null
                && mouseX >= this.searchBoxRect[0]
                && mouseX < this.searchBoxRect[0] + this.searchBoxRect[2]
                && mouseY >= this.searchBoxRect[1]
                && mouseY < this.searchBoxRect[1] + this.searchBoxRect[3]) {
            this.searchFocused = true;
            UiSounds.click();
            return true;
        }
        this.searchFocused = false;

        for (int[] fp : this.filterPills) {
            if (mouseX >= fp[0] && mouseX < fp[0] + fp[2]
                    && mouseY >= fp[1] && mouseY < fp[1] + fp[3]) {
                FilterMode clicked = FilterMode.values()[fp[4]];
                this.filterMode = (this.filterMode == clicked && clicked != FilterMode.ALL)
                        ? FilterMode.ALL : clicked;
                this.scroll = 0;
                UiSounds.click();
                return true;
            }
        }
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
        for (int[] b : this.orderButtons) {
            if (mouseX < b[0] || mouseX >= b[0] + b[2]
                    || mouseY < b[1] || mouseY >= b[1] + 12) {
                continue;
            }
            if (b[3] == -1) {
                List<UnitMarker> visible = ownedUnits();
                for (UnitMarker m : visible) {
                    UnitMarkerStore.addSelected(m);
                }
                showFeedback("Selected " + visible.size() + " unit(s)", COL_ACCENT_BRIGHT);
                UiSounds.click();
            } else if (b[3] == -2) {
                UnitMarkerStore.clearSelection();
                showFeedback("Selection cleared", COL_SUB);
                UiSounds.click();
            } else {
                issueOrder(OrderType.values()[b[3]]);
            }
            return true;
        }
        return false;
    }

    private void issueOrder(OrderType order) {
        java.util.Set<Integer> ids = UnitMarkerStore.selectedUnitIds();
        if (ids.isEmpty()) {
            UiSounds.click();
            showFeedback("Select at least 1 unit first", COL_BAD);
            return;
        }
        for (int id : ids) {
            net.nekoyuni.SimpleEnemyMod.network.ModNetworking.CHANNEL.sendToServer(
                    new net.nekoyuni.SimpleEnemyMod.network.packets.PacketIssueOrder(
                            id, order, net.minecraft.world.phys.Vec3.ZERO, 0, -1));
        }
        UiSounds.confirm();
        String msg = "Order " + order.name().replace('_', ' ') + " -> " + ids.size() + " unit(s)";
        showFeedback(msg, COL_OK);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(msg)
                    .withStyle(net.minecraft.ChatFormatting.GREEN), true);
        }
    }

    private boolean clickRosterRow(double mouseX, double mouseY) {
        if (mouseX < this.winX + 6 || mouseX >= this.winX + this.winW - 8
                || mouseY < this.rosterViewTop
                || mouseY >= this.rosterViewTop + this.rosterViewH) {
            return false;
        }
        int renderScroll = Math.round(Mth.clamp(this.smoothScroll, 0.0F, this.maxScrollCached));
        int row = (int) ((mouseY - this.rosterViewTop + renderScroll) / ROW_H);
        List<UnitMarker> units = ownedUnits();
        if (row < 0 || row >= units.size()) {
            return false;
        }
        UnitMarkerStore.toggleSelected(units.get(row));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (this.openApp != null) {
            this.scroll = Mth.clamp(this.scroll - (int) Math.round(delta * ROW_H * 1.5), 0, this.maxScrollCached);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.openApp == App.SQUAD && this.searchFocused) {
            if (Character.isLetterOrDigit(codePoint) || codePoint == ' ' || codePoint == '-' || codePoint == '_' || codePoint == '#') {
                if (this.searchQuery.length() < 28) {
                    this.searchQuery += codePoint;
                    this.scroll = 0;
                }
                return true;
            }
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.openApp == App.SQUAD && this.searchFocused) {
            if (keyCode == 259) { // Backspace
                if (!this.searchQuery.isEmpty()) {
                    this.searchQuery = this.searchQuery.substring(0, this.searchQuery.length() - 1);
                    this.scroll = 0;
                }
                return true;
            }
            if (keyCode == 256 || keyCode == 257) { // ESC or Enter unfocuses search
                if (keyCode == 256 && !this.searchQuery.isEmpty()) {
                    this.searchQuery = "";
                }
                this.searchFocused = false;
                return true;
            }
            return true;
        }

        if (keyCode == 256 && this.openApp != null) { // ESC backs out to desktop icons
            this.openApp = null;
            return true;
        }
        if (this.openApp != null) {
            if (keyCode == 264) { // Down arrow
                this.scroll = Mth.clamp(this.scroll + ROW_H, 0, this.maxScrollCached);
                return true;
            }
            if (keyCode == 265) { // Up arrow
                this.scroll = Mth.clamp(this.scroll - ROW_H, 0, this.maxScrollCached);
                return true;
            }
            if (keyCode == 267) { // PageDown
                this.scroll = Mth.clamp(this.scroll + this.rosterViewH, 0, this.maxScrollCached);
                return true;
            }
            if (keyCode == 266) { // PageUp
                this.scroll = Mth.clamp(this.scroll - this.rosterViewH, 0, this.maxScrollCached);
                return true;
            }
        }
        // Number keys 1/2/3 switch apps quickly
        if (keyCode == 49) {
            switchApp(App.SQUAD);
            return true;
        }
        if (keyCode == 50) {
            switchApp(App.TELEPORT);
            return true;
        }
        if (keyCode == 51) {
            switchApp(App.INTEL);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---- labels ------------------------------------------------------------

    private static String classLabel(UnitMarker m) {
        if (m.classOrd() >= 0 && m.classOrd() < WeaponClass.values().length) {
            return ClassSystemConfig.classNickname(WeaponClass.values()[m.classOrd()]);
        }
        if (m.factionOrd() >= 0 && m.factionOrd() < UnitClass.values().length) {
            return UnitClass.values()[m.factionOrd()].name();
        }
        return "-";
    }

    private static String statusLabel(UnitMarker m) {
        if (m.sleeping()) {
            return "Sleeping";
        }
        return switch (m.dutyMode()) {
            case UnitChunkDutyGoal.DUTY_GUARD -> "Guard";
            case UnitChunkDutyGoal.DUTY_PATROL -> "Patrol";
            case UnitChunkDutyGoal.DUTY_ROUTE -> "Route";
            case UnitChunkDutyGoal.DUTY_POST -> "On Post";
            default -> orderLabel(m.orderOrd());
        };
    }

    private static String orderLabel(int orderOrd) {
        OrderType[] values = OrderType.values();
        if (orderOrd < 0 || orderOrd >= values.length) {
            return "-";
        }
        String n = values[orderOrd].name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
