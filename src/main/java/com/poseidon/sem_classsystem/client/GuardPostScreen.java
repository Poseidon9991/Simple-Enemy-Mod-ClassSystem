// Client package - guard post UI, packet-driven like the tablet
package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.map.UnitMarker;
import com.poseidon.sem_classsystem.network.PacketGuardPostAction;
import com.poseidon.sem_classsystem.network.PacketOpenGuardPost;
import com.poseidon.sem_classsystem.network.SemcsNetwork;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Guard Post assignment table UI: manages PRIMARY and RESERVE billet slots
// plus a sortable/scrollable owned-unit roster with class badges, health bars,
// and inline post transfer selection.
public class GuardPostScreen extends Screen {

    private static final int ROW_H = 16;
    private static final int BTN_H = 12;

    private final BlockPos pos;
    private final PacketOpenGuardPost.Slot primary;
    private final PacketOpenGuardPost.Slot reserve;
    private final List<BlockPos> nearbyPosts;
    private int scroll = 0;
    private float smoothScroll = 0.0F;
    private long lastFrameMs = Util.getMillis();
    private int transferSlot = 0;   // 0 none, 1 primary, 2 reserve

    private int winX, winY, winW, winH;
    private int maxScrollCached = 0;
    private final List<int[]> rosterRows = new ArrayList<>();      // {x,y,w,entityId}
    private final List<int[]> transferTargets = new ArrayList<>(); // {x,y,w,targetPosIdx}

    public GuardPostScreen(PacketOpenGuardPost msg) {
        super(Component.literal("Guard Post"));
        this.pos = msg.pos();
        this.primary = msg.primary();
        this.reserve = msg.reserve();
        this.nearbyPosts = msg.nearbyPosts();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        float dt = Mth.clamp((now - this.lastFrameMs) / 1000.0F, 0.0F, 0.1F);
        this.lastFrameMs = now;
        this.smoothScroll += (this.scroll - this.smoothScroll) * Math.min(1.0F, dt * 18.0F);

        this.renderBackground(g);
        this.rosterRows.clear();
        this.transferTargets.clear();

        this.winW = Math.min(440, Math.max(300, this.width - 36));
        this.winH = Math.min(310, Math.max(210, this.height - 36));
        this.winX = (this.width - this.winW) / 2;
        this.winY = (this.height - this.winH) / 2;

        // Outer frame & header
        g.fill(this.winX + 3, this.winY + 3, this.winX + this.winW + 4,
                this.winY + this.winH + 4, 0x66000000);
        g.fill(this.winX - 2, this.winY - 2, this.winX + this.winW + 2,
                this.winY + this.winH + 2, 0xFF0E141D);
        g.fill(this.winX - 1, this.winY - 1, this.winX + this.winW + 1,
                this.winY + this.winH + 1, 0xFF3D4E63);
        g.fill(this.winX, this.winY, this.winX + this.winW,
                this.winY + this.winH, 0xFF141C28);
        g.fill(this.winX, this.winY, this.winX + this.winW, this.winY + 20, 0xFF1D2A3A);
        g.fill(this.winX, this.winY + 19, this.winX + this.winW, this.winY + 20, 0xFF6FE3C1);
        g.drawString(this.font,
                "GUARD POST  //  " + this.pos.getX() + ", " + this.pos.getY() + ", " + this.pos.getZ(),
                this.winX + 8, this.winY + 6, 0xFF6FE3C1, false);

        int bx = this.winX + this.winW - 18;
        boolean closeHov = mouseX >= bx && mouseX < bx + 14
                && mouseY >= this.winY + 3 && mouseY < this.winY + 17;
        g.fill(bx, this.winY + 3, bx + 14, this.winY + 17, closeHov ? 0xFFB03A3A : 0xFF8A2F2F);
        g.drawCenteredString(this.font, "x", bx + 7, this.winY + 5, 0xFFFFFFFF);

        int y = this.winY + 26;
        y = renderSlot(g, mouseX, mouseY, 1, "PRIMARY SENTRY", this.primary, y) + 3;
        y = renderSlot(g, mouseX, mouseY, 2, "RESERVE RELIEF", this.reserve, y) + 6;

        // Roster Table Header
        int cx = this.winX + 8;
        int cw = this.winW - 16;
        g.fill(cx, y, cx + cw, y + 12, 0xFF1A2534);
        g.drawString(this.font, "CALLSIGN", cx + 6, y + 2, 0xFF8AA0B8, false);
        g.drawString(this.font, "CLASS", cx + 142, y + 2, 0xFF8AA0B8, false);
        g.drawString(this.font, "HEALTH", cx + 218, y + 2, 0xFF8AA0B8, false);
        g.drawString(this.font, "ACTION", cx + cw - 56, y + 2, 0xFF8AA0B8, false);
        y += 13;

        List<UnitMarker> units = new ArrayList<>();
        for (UnitMarker m : UnitMarkerStore.markers()) {
            if (m.allegiance() == UnitMarker.Allegiance.OWN) {
                units.add(m);
            }
        }
        units.sort(Comparator.comparing(UnitMarker::name)
                .thenComparingInt(UnitMarker::entityId));

        int viewTop = y;
        int viewH = this.winY + this.winH - 8 - viewTop;
        int contentH = units.size() * ROW_H;
        this.maxScrollCached = Math.max(0, contentH - viewH);
        this.scroll = Mth.clamp(this.scroll, 0, this.maxScrollCached);
        int renderScroll = Math.round(Mth.clamp(this.smoothScroll, 0.0F, this.maxScrollCached));

        g.enableScissor(cx, viewTop, cx + cw, viewTop + viewH);
        int ry = viewTop - renderScroll;
        for (int idx = 0; idx < units.size(); idx++) {
            UnitMarker m = units.get(idx);
            if (ry + ROW_H > viewTop && ry < viewTop + viewH) {
                boolean hover = mouseX >= cx && mouseX < cx + cw - 6
                        && mouseY >= ry && mouseY < ry + ROW_H
                        && mouseY >= viewTop && mouseY < viewTop + viewH;
                int rowBg = hover ? 0xFF22344A : (idx % 2 == 1 ? 0xFF172130 : 0xFF141C28);
                g.fill(cx, ry, cx + cw - 4, ry + ROW_H - 1, rowBg);

                String name = (m.rankTitle().isEmpty() ? "" : m.rankTitle() + " ")
                        + (m.name().isEmpty() ? "Unit #" + m.entityId() : m.name());
                g.drawString(this.font, this.font.plainSubstrByWidth(name, 130),
                        cx + 6, ry + 4, 0xFFE8F0F8, false);

                // Class badge
                if (m.classOrd() >= 0 && m.classOrd() < WeaponClass.values().length) {
                    WeaponClass cls = WeaponClass.values()[m.classOrd()];
                    int accent = 0xFF000000 | SemcsRegistries.classAccent(cls);
                    String cName = this.font.plainSubstrByWidth(ClassSystemConfig.classNickname(cls), 64);
                    g.drawString(this.font, cName, cx + 142, ry + 4, accent, false);
                } else {
                    g.drawString(this.font, "-", cx + 142, ry + 4, 0xFF5E7086, false);
                }

                // HP bar + text
                int barW = 34;
                int filled = Math.round(Mth.clamp(m.healthFrac(), 0.0F, 1.0F) * barW);
                int hpCol = m.healthFrac() > 0.5F ? 0xFF4BC06A : (m.healthFrac() > 0.25F ? 0xFFE3B86F : 0xFFC0403A);
                g.fill(cx + 218, ry + 5, cx + 218 + barW, ry + 10, 0xFF0A0E14);
                if (filled > 0) {
                    g.fill(cx + 218, ry + 5, cx + 218 + filled, ry + 10, hpCol);
                }
                String hp = (int) Math.ceil(m.health()) + "/" + (int) Math.ceil(m.maxHealth());
                g.drawString(this.font, hp, cx + 218 + barW + 4, ry + 4, 0xFFB9C6D4, false);

                // ASSIGN button
                int ax = cx + cw - 60;
                boolean hovBtn = mouseX >= ax && mouseX < ax + 54
                        && mouseY >= ry + 2 && mouseY < ry + 2 + BTN_H;
                g.fill(ax, ry + 2, ax + 54, ry + 2 + BTN_H,
                        hovBtn ? 0xFF2E7D46 : 0xFF1D4A2E);
                g.drawCenteredString(this.font, "ASSIGN", ax + 27, ry + 4, 0xFFFFFFFF);
                this.rosterRows.add(new int[]{ax, ry + 2, 54, m.entityId()});
            }
            ry += ROW_H;
        }
        g.disableScissor();

        if (units.isEmpty()) {
            g.drawCenteredString(this.font,
                    "No owned units in this dimension.",
                    this.winX + this.winW / 2, viewTop + viewH / 2, 0xFF5E7086);
        }
        if (this.maxScrollCached > 0) {
            int trackX = this.winX + this.winW - 6;
            g.fill(trackX, viewTop, trackX + 3, viewTop + viewH, 0xFF0A0E14);
            int thumbH = Math.max(12, viewH * viewH / Math.max(1, contentH));
            int thumbY = viewTop + (viewH - thumbH) * renderScroll / this.maxScrollCached;
            g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFF6FE3C1);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private int renderSlot(GuiGraphics g, int mouseX, int mouseY, int slot,
                           String label, PacketOpenGuardPost.Slot s, int y) {
        int x = this.winX + 8;
        int w = this.winW - 16;
        g.fill(x, y - 1, x + w, y + ROW_H + 1, 0xFF1D2A3A);
        g.fill(x, y - 1, x + 3, y + ROW_H + 1, s != null ? 0xFF6FE3C1 : 0xFF3D4E63);
        g.drawString(this.font, label, x + 7, y + 4, 0xFFE3B86F, false);
        String name = s == null ? "- unassigned -"
                : (s.name().isEmpty() ? "Bound unit" : s.name());
        g.drawString(this.font, this.font.plainSubstrByWidth(name, w - 220), x + 98, y + 4,
                s == null ? 0xFF5E7086 : 0xFFE8F0F8, false);

        if (s != null) {
            int rx = x + w - 122;
            boolean hovRemove = mouseX >= rx && mouseX < rx + 52
                    && mouseY >= y + 2 && mouseY < y + 2 + BTN_H;
            g.fill(rx, y + 2, rx + 52, y + 2 + BTN_H,
                    hovRemove ? 0xFFA23838 : 0xFF5A2020);
            g.drawCenteredString(this.font, "REMOVE", rx + 26, y + 4, 0xFFFFFFFF);
            this.rosterRows.add(new int[]{rx, y + 2, 52, -slot});

            int tx = x + w - 66;
            boolean hovTx = mouseX >= tx && mouseX < tx + 62
                    && mouseY >= y + 2 && mouseY < y + 2 + BTN_H;
            boolean canTx = !this.nearbyPosts.isEmpty();
            g.fill(tx, y + 2, tx + 62, y + 2 + BTN_H,
                    !canTx ? 0xFF242E3A
                           : this.transferSlot == slot ? 0xFF8A6A24
                           : hovTx ? 0xFF4A6A8A : 0xFF2A3A4A);
            g.drawCenteredString(this.font, "TRANSFER", tx + 31, y + 4,
                    canTx ? 0xFFFFFFFF : 0xFF667482);
            this.rosterRows.add(new int[]{tx, y + 2, 62, -10 - slot});
        }
        y += ROW_H + 3;

        if (this.transferSlot == slot && s != null) {
            if (this.nearbyPosts.isEmpty()) {
                g.drawString(this.font, "No other Guard Posts within transfer range.",
                        x + 98, y + 2, 0xFFE38B6F, false);
                y += BTN_H + 4;
            } else {
                int i = 0;
                for (BlockPos target : this.nearbyPosts) {
                    int tx = x + 98;
                    boolean hov = mouseX >= tx && mouseX < tx + 190
                            && mouseY >= y && mouseY < y + BTN_H;
                    g.fill(tx, y, tx + 190, y + BTN_H, hov ? 0xFF2E5D7D : 0xFF1D2A3A);
                    g.drawString(this.font, "-> Post at " + target.toShortString(),
                            tx + 5, y + 2, 0xFF6FE3C1, false);
                    this.transferTargets.add(new int[]{tx, y, 190, i});
                    y += BTN_H + 2;
                    i++;
                }
                y += 2;
            }
        }
        return y;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int bx = this.winX + this.winW - 18;
            if (mouseX >= bx && mouseX < bx + 14
                    && mouseY >= this.winY + 3 && mouseY < this.winY + 17) {
                UiSounds.click();
                this.onClose();
                return true;
            }
            for (int[] row : this.rosterRows) {
                if (mouseX >= row[0] && mouseX < row[0] + row[2]
                        && mouseY >= row[1] && mouseY < row[1] + BTN_H) {
                    int code = row[3];
                    if (code > 0) {
                        SemcsNetwork.CHANNEL.sendToServer(new PacketGuardPostAction(
                                this.pos, PacketGuardPostAction.ACTION_ASSIGN,
                                code, 0, null));
                        UiSounds.confirm();
                        this.onClose();
                    } else if (code == -1 || code == -2) {
                        SemcsNetwork.CHANNEL.sendToServer(new PacketGuardPostAction(
                                this.pos, PacketGuardPostAction.ACTION_REMOVE,
                                0, -code, null));
                        UiSounds.click();
                        this.onClose();
                    } else if (code == -11 || code == -12) {
                        this.transferSlot = this.transferSlot == -code - 10
                                ? 0 : -code - 10;
                        UiSounds.click();
                    }
                    return true;
                }
            }
            for (int[] t : this.transferTargets) {
                if (mouseX >= t[0] && mouseX < t[0] + t[2]
                        && mouseY >= t[1] && mouseY < t[1] + BTN_H) {
                    SemcsNetwork.CHANNEL.sendToServer(new PacketGuardPostAction(
                            this.pos, PacketGuardPostAction.ACTION_TRANSFER,
                            0, this.transferSlot, this.nearbyPosts.get(t[3])));
                    UiSounds.confirm();
                    this.onClose();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        this.scroll = Mth.clamp(this.scroll - (int) Math.round(delta * ROW_H * 1.5), 0, this.maxScrollCached);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 264) {
            this.scroll = Mth.clamp(this.scroll + ROW_H, 0, this.maxScrollCached);
            return true;
        }
        if (keyCode == 265) {
            this.scroll = Mth.clamp(this.scroll - ROW_H, 0, this.maxScrollCached);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
