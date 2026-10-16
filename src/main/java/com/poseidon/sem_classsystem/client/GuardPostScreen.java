// Client package - guard post UI, packet-driven like the tablet
package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.map.UnitMarker;
import com.poseidon.sem_classsystem.network.PacketGuardPostAction;
import com.poseidon.sem_classsystem.network.PacketOpenGuardPost;
import com.poseidon.sem_classsystem.network.SemcsNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Two slot rows (PRIMARY / RESERVE) plus the owned-unit roster. Assign
// fills the first free slot; Remove frees one; Transfer sends a slot's
// unit to another nearby post picked from an inline list. The screen is
// a snapshot - the server re-validates every click.
public class GuardPostScreen extends Screen {

    private static final int ROW_H = 14;
    private static final int BTN_H = 12;

    private final BlockPos pos;
    private PacketOpenGuardPost.Slot primary;
    private PacketOpenGuardPost.Slot reserve;
    private final List<BlockPos> nearbyPosts;
    private int scroll = 0;
    private int transferSlot = 0;   // 0 none, 1 primary, 2 reserve

    // cached click regions rebuilt each frame
    private int winX, winY, winW, winH;
    private final List<int[]> rosterRows = new ArrayList<>();  // {x,y,w,entityId}
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
        this.renderBackground(g);
        this.rosterRows.clear();
        this.transferTargets.clear();

        this.winW = Math.min(420, this.width - 40);
        this.winH = Math.min(300, this.height - 40);
        this.winX = (this.width - this.winW) / 2;
        this.winY = (this.height - this.winH) / 2;
        g.fill(this.winX - 1, this.winY - 1, this.winX + this.winW + 1,
                this.winY + this.winH + 1, 0xFF3D4E63);
        g.fill(this.winX, this.winY, this.winX + this.winW,
                this.winY + this.winH, 0xFF141C28);
        g.fill(this.winX, this.winY, this.winX + this.winW, this.winY + 18, 0xFF1D2A3A);
        g.drawString(this.font,
                "Guard Post  " + this.pos.getX() + ", " + this.pos.getY() + ", " + this.pos.getZ(),
                this.winX + 8, this.winY + 5, 0xFF6FE3C1, false);
        int bx = this.winX + this.winW - 16;
        g.fill(bx, this.winY + 4, bx + 12, this.winY + 14, 0xFF8A2F2F);
        g.drawCenteredString(this.font, "x", bx + 6, this.winY + 6, 0xFFFFFFFF);

        int y = this.winY + 26;
        y = renderSlot(g, mouseX, mouseY, 1, "PRIMARY", this.primary, y) + 2;
        y = renderSlot(g, mouseX, mouseY, 2, "RESERVE", this.reserve, y) + 6;

        // ---- roster ---------------------------------------------------------
        g.drawString(this.font, "Your units (click ASSIGN to bind):",
                this.winX + 10, y, 0xFF5E7086, false);
        y += 10;

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
        int maxScroll = Math.max(0, contentH - viewH);
        this.scroll = Math.min(this.scroll, maxScroll);

        g.enableScissor(this.winX + 8, viewTop, this.winX + this.winW - 8,
                viewTop + viewH);
        int ry = viewTop - this.scroll;
        for (UnitMarker m : units) {
            if (ry + ROW_H > viewTop && ry < viewTop + viewH) {
                boolean hover = mouseX >= this.winX + 10
                        && mouseX < this.winX + this.winW - 10
                        && mouseY >= ry && mouseY < ry + ROW_H
                        && mouseY >= viewTop && mouseY < viewTop + viewH;
                if (hover) {
                    g.fill(this.winX + 8, ry, this.winX + this.winW - 8,
                            ry + ROW_H - 1, 0xFF22344A);
                }
                String name = (m.rankTitle().isEmpty() ? "" : m.rankTitle() + " ")
                        + (m.name().isEmpty() ? "Unit #" + m.entityId() : m.name());
                g.drawString(this.font, this.font.plainSubstrByWidth(name, 160),
                        this.winX + 12, ry + 3, 0xFFE8F0F8, false);
                String hp = (int) m.health() + "/" + (int) m.maxHealth();
                g.drawString(this.font, hp, this.winX + 176, ry + 3, 0xFFB9C6D4, false);
                // ASSIGN button
                int ax = this.winX + this.winW - 66;
                boolean hovBtn = mouseX >= ax && mouseX < ax + 54
                        && mouseY >= ry + 1 && mouseY < ry + 1 + BTN_H;
                g.fill(ax, ry + 1, ax + 54, ry + 1 + BTN_H,
                        hovBtn ? 0xFF2E7D46 : 0xFF1D4A2E);
                g.drawCenteredString(this.font, "ASSIGN", ax + 27, ry + 3, 0xFFFFFFFF);
                this.rosterRows.add(new int[]{ax, ry + 1, 54, m.entityId()});
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
        super.render(g, mouseX, mouseY, partialTick);
    }

    private int renderSlot(GuiGraphics g, int mouseX, int mouseY, int slot,
                           String label, PacketOpenGuardPost.Slot s, int y) {
        int x = this.winX + 10;
        g.fill(x - 2, y - 2, this.winX + this.winW - 10, y + ROW_H + 2, 0xFF1D2A3A);
        g.drawString(this.font, label, x + 2, y + 3, 0xFFE3B86F, false);
        String name = s == null ? "- empty -"
                : (s.name().isEmpty() ? "bound unit" : s.name());
        g.drawString(this.font, name, x + 62, y + 3,
                s == null ? 0xFF5E7086 : 0xFFE8F0F8, false);

        if (s != null) {
            int rx = this.winX + this.winW - 128;
            boolean hovRemove = mouseX >= rx && mouseX < rx + 52
                    && mouseY >= y && mouseY < y + BTN_H;
            g.fill(rx, y, rx + 52, y + BTN_H,
                    hovRemove ? 0xFF8A2F2F : 0xFF5A2020);
            g.drawCenteredString(this.font, "REMOVE", rx + 26, y + 2, 0xFFFFFFFF);
            // region registered lazily in mouseClicked via stored coords
            this.rosterRows.add(new int[]{rx, y, 52, -slot});   // negative = REMOVE

            int tx = this.winX + this.winW - 72;
            boolean hovTx = mouseX >= tx && mouseX < tx + 62
                    && mouseY >= y && mouseY < y + BTN_H;
            boolean canTx = !this.nearbyPosts.isEmpty();
            g.fill(tx, y, tx + 62, y + BTN_H,
                    !canTx ? 0xFF2A3442
                           : this.transferSlot == slot ? 0xFF8A6A24
                           : hovTx ? 0xFF4A6A8A : 0xFF2A3A4A);
            g.drawCenteredString(this.font, "TRANSFER", tx + 31, y + 2, 0xFFFFFFFF);
            this.rosterRows.add(new int[]{tx, y, 62, -10 - slot}); // -11/-12 = TRANSFER
        }
        y += ROW_H + 4;

        // Inline transfer target list under the expanded slot.
        if (this.transferSlot == slot && s != null) {
            int i = 0;
            for (BlockPos target : this.nearbyPosts) {
                int tx = x + 62;
                boolean hov = mouseX >= tx && mouseX < tx + 180
                        && mouseY >= y && mouseY < y + BTN_H;
                g.fill(tx, y, tx + 180, y + BTN_H, hov ? 0xFF2E5D7D : 0xFF1D2A3A);
                g.drawString(this.font, "-> post " + target.toShortString(),
                        tx + 4, y + 2, 0xFF6FE3C1, false);
                this.transferTargets.add(new int[]{tx, y, 180, i});
                y += BTN_H + 2;
                i++;
            }
            y += 2;
        }
        return y;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int bx = this.winX + this.winW - 16;
            if (mouseX >= bx && mouseX < bx + 12
                    && mouseY >= this.winY + 4 && mouseY < this.winY + 14) {
                UiSounds.click();
                this.onClose();
                return true;
            }
            for (int[] row : this.rosterRows) {
                if (mouseX >= row[0] && mouseX < row[0] + row[2]
                        && mouseY >= row[1] && mouseY < row[1] + BTN_H) {
                    int code = row[3];
                    if (code > 0) {                       // ASSIGN
                        SemcsNetwork.CHANNEL.sendToServer(new PacketGuardPostAction(
                                this.pos, PacketGuardPostAction.ACTION_ASSIGN,
                                code, 0, null));
                        UiSounds.confirm();
                        this.onClose();   // reopen shows the fresh snapshot
                    } else if (code == -1 || code == -2) { // REMOVE primary/reserve
                        SemcsNetwork.CHANNEL.sendToServer(new PacketGuardPostAction(
                                this.pos, PacketGuardPostAction.ACTION_REMOVE,
                                0, -code, null));
                        UiSounds.click();
                        this.onClose();   // state changed; reopen shows fresh snapshot
                    } else if (code == -11 || code == -12) { // TRANSFER expando
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
        this.scroll = Math.max(0, this.scroll - (int) (delta * ROW_H));
        return true;
    }
}
