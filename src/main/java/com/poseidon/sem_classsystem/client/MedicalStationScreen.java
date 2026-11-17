package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.menu.MedicalStationMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

public class MedicalStationScreen extends AbstractContainerScreen<MedicalStationMenu> {

    private static final int WIN_W = 176;
    private static final int WIN_H = 154;

    public MedicalStationScreen(MedicalStationMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = WIN_W;
        this.imageHeight = WIN_H;
        this.inventoryLabelY = 61;
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

        g.fill(left, top, left + WIN_W, top + WIN_H, 0xFF14181F);
        g.fill(left, top, left + WIN_W, top + 1, 0xFF3E4856);
        g.fill(left, top + WIN_H - 1, left + WIN_W, top + WIN_H, 0xFF0D1015);
        g.fill(left, top, left + 1, top + WIN_H, 0xFF3E4856);
        g.fill(left + WIN_W - 1, top, left + WIN_W, top + WIN_H, 0xFF0D1015);

        g.fill(left + 2, top + 2, left + WIN_W - 2, top + 16, 0xFF1D2730);
        g.fill(left + 2, top + 16, left + WIN_W - 2, top + 17, 0xFF3FA67B);

        for (Slot slot : this.menu.slots) {
            int sx = left + slot.x;
            int sy = top + slot.y;
            g.fill(sx - 1, sy - 1, sx + 17, sy + 17, 0xFF28323E);
            g.fill(sx, sy, sx + 16, sy + 16, 0xFF10141B);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 0x72E3A7, false);
        g.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 0x808E9B, false);
    }
}
