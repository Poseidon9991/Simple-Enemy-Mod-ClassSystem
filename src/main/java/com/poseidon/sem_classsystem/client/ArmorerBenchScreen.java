package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.menu.ArmorerBenchMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

public class ArmorerBenchScreen extends AbstractContainerScreen<ArmorerBenchMenu> {

    private static final int WIN_W = 176;
    private static final int WIN_H = 184;

    private static final int BTN_X = 107;
    private static final int BTN_Y = 23;
    private static final int BTN_W = 24;
    private static final int BTN_H = 14;

    public ArmorerBenchScreen(ArmorerBenchMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = WIN_W;
        this.imageHeight = WIN_H;
        this.inventoryLabelY = 91;
    }

    @Override
    protected void init() {
        super.init();
        this.titleLabelX = 8;
        this.titleLabelY = 5;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int left = this.leftPos;
        int top = this.topPos;

        // Tactical slate panel
        g.fill(left, top, left + WIN_W, top + WIN_H, 0xFF14181F);
        g.fill(left, top, left + WIN_W, top + 1, 0xFF3E4856);
        g.fill(left, top + WIN_H - 1, left + WIN_W, top + WIN_H, 0xFF0D1015);
        g.fill(left, top, left + 1, top + WIN_H, 0xFF3E4856);
        g.fill(left + WIN_W - 1, top, left + WIN_W, top + WIN_H, 0xFF0D1015);

        // Header bar
        g.fill(left + 2, top + 2, left + WIN_W - 2, top + 16, 0xFF1E2530);
        g.fill(left + 2, top + 16, left + WIN_W - 2, top + 17, 0xFF2F3B4C);

        // Left: Gear Fitting panel
        g.fill(left + 8, top + 19, left + 56, top + 89, 0xFF1A202A);
        g.drawString(this.font, Component.translatable("gui.sem_classsystem.armorer.fit"), left + 14, top + 42, 0x8FA4BE, false);

        // Top-right: Repair panel
        g.fill(left + 60, top + 19, left + 168, top + 42, 0xFF1A202A);
        g.drawString(this.font, "+", left + 125, top + 26, 0x8FA4BE, false);

        // Repair button
        boolean ready = this.menu.canRepairNow();
        boolean hov = mouseX >= left + BTN_X && mouseX < left + BTN_X + BTN_W
                && mouseY >= top + BTN_Y && mouseY < top + BTN_Y + BTN_H;
        int btnBg = ready ? (hov ? 0xFF3A7B56 : 0xFF2B5E42) : 0xFF222933;
        g.fill(left + BTN_X, top + BTN_Y, left + BTN_X + BTN_W, top + BTN_Y + BTN_H, btnBg);
        g.drawCenteredString(this.font, "FIX", left + BTN_X + BTN_W / 2, top + BTN_Y + 3,
                ready ? 0xFF9BFFB8 : 0xFF6B7785);

        // Bottom-right: 12-slot Plate Rack panel
        g.fill(left + 60, top + 44, left + 168, top + 89, 0xFF1A202A);
        g.drawString(this.font, Component.translatable("gui.sem_classsystem.armorer.rack"), left + 63, top + 45, 0x8FA4BE, false);

        for (Slot slot : this.menu.slots) {
            if (!slot.isActive()) {
                continue;
            }
            int sx = left + slot.x;
            int sy = top + slot.y;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, 0xFF2B3544);
            g.fill(sx, sy, sx + 16, sy + 16, 0xFF10141B);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 0xF0C040, false);
        g.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 0x808E9B, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int bx = this.leftPos + BTN_X;
            int by = this.topPos + BTN_Y;
            if (mouseX >= bx && mouseX < bx + BTN_W && mouseY >= by && mouseY < by + BTN_H) {
                if (this.menu.canRepairNow() && this.minecraft != null && this.minecraft.gameMode != null) {
                    this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, ArmorerBenchMenu.BUTTON_REPAIR);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
