package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.item.ArmorPlateItem;
import com.poseidon.sem_classsystem.menu.ArmorPlateMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

// Tactical plate carrier / ballistic vest GUI inspired by Warborn armor slots.
// Displays dedicated Front & Back plate slots with real-time damage reduction
// stats, slot guides, and seamless drag-and-drop or shift-click plate management.
public class ArmorPlateScreen extends AbstractContainerScreen<ArmorPlateMenu> {

    private static final int WIN_W = 176;
    private static final int WIN_H = 166;

    private static final List<Component> INSERT_EMPTY_TOOLTIP = List.of(
            Component.literal("Ballistic Insert Slot").withStyle(ChatFormatting.GOLD),
            Component.literal("Accepts Level 1 to 5 Armor Plates").withStyle(ChatFormatting.GRAY),
            Component.literal("Insert or remove plates directly by clicking").withStyle(ChatFormatting.DARK_GRAY)
    );
    private static final List<Component> FRONT_EMPTY_TOOLTIP = List.of(
            Component.literal("Front Ballistic Plate Slot").withStyle(ChatFormatting.GOLD),
            Component.literal("Accepts Level 1 to 5 Armor Plates").withStyle(ChatFormatting.GRAY),
            Component.literal("Insert or remove plates directly by clicking").withStyle(ChatFormatting.DARK_GRAY)
    );
    private static final List<Component> BACK_EMPTY_TOOLTIP = List.of(
            Component.literal("Back Ballistic Plate Slot").withStyle(ChatFormatting.GOLD),
            Component.literal("Accepts Level 1 to 5 Armor Plates").withStyle(ChatFormatting.GRAY),
            Component.literal("Insert or remove plates directly by clicking").withStyle(ChatFormatting.DARK_GRAY)
    );

    private int cachedFilledCount = -1;
    private int cachedBulletPct = -1;
    private int cachedGeneralPct = -1;
    private String cachedSummary = "";
    private int cachedSummaryWidth = 0;

    public ArmorPlateScreen(ArmorPlateMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = WIN_W;
        this.imageHeight = WIN_H;
        this.inventoryLabelY = 72;
    }

    @Override
    protected void init() {
        super.init();
        this.titleLabelX = 8;
        this.titleLabelY = 6;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int left = this.leftPos;
        int top = this.topPos;

        // 1. Tactical dark container background
        graphics.fill(left, top, left + WIN_W, top + WIN_H, 0xFF14181F);
        // Outer beveled border
        graphics.fill(left, top, left + WIN_W, top + 1, 0xFF3E4856);
        graphics.fill(left, top + WIN_H - 1, left + WIN_W, top + WIN_H, 0xFF0D1015);
        graphics.fill(left, top, left + 1, top + WIN_H, 0xFF3E4856);
        graphics.fill(left + WIN_W - 1, top, left + WIN_W, top + WIN_H, 0xFF0D1015);

        // Header bar
        graphics.fill(left + 2, top + 2, left + WIN_W - 2, top + 18, 0xFF1E2530);
        graphics.fill(left + 2, top + 18, left + WIN_W - 2, top + 19, 0xFF2F3B4C);

        // Plate slot panel background
        graphics.fill(left + 8, top + 22, left + WIN_W - 8, top + 68, 0xFF1A202A);
        graphics.fill(left + 8, top + 22, left + WIN_W - 8, top + 23, 0xFF2B3544);
        graphics.fill(left + 8, top + 67, left + WIN_W - 8, top + 68, 0xFF0D1015);

        // 2. Render all slots with tactical inset styling
        for (Slot slot : this.menu.slots) {
            int sx = left + slot.x;
            int sy = top + slot.y;

            if (slot instanceof ArmorPlateMenu.PlateSlot plateSlot) {
                // Highlighted plate carrier slot frame
                graphics.fill(sx - 2, sy - 2, sx + 18, sy + 18, 0xFF2A3648);
                graphics.fill(sx - 1, sy - 1, sx + 17, sy + 17, 0xFF0F141B);

                // Slot index label
                String label = this.menu.getMaxPlateSlots() == 1 ? "INSERT"
                        : (plateSlot.getPlateIndex() == 0 ? "FRONT" : "BACK");
                int labelW = this.font.width(label);
                graphics.drawString(this.font, label, sx + 8 - labelW / 2, sy - 9, 0x8FA4BE, false);

                // If empty, draw subtle plate silhouette placeholder
                if (!slot.hasItem()) {
                    graphics.fill(sx + 3, sy + 3, sx + 13, sy + 13, 0xFF19222E);
                    graphics.fill(sx + 5, sy + 5, sx + 11, sy + 11, 0xFF243040);
                }
            } else {
                // Standard player inventory slot box
                graphics.fill(sx - 1, sy - 1, sx + 17, sy + 17, 0xFF28303C);
                graphics.fill(sx, sy, sx + 16, sy + 16, 0xFF12161D);
            }
        }

        // 3. Armor Protection Summary Bar (reads directly from live PlateSlots without NBT deserialization)
        int bulletPct = 0;
        int generalPct = 0;
        int filledCount = 0;
        int maxSlots = this.menu.getMaxPlateSlots();
        for (int i = 0; i < maxSlots && i < this.menu.slots.size(); i++) {
            ItemStack p = this.menu.slots.get(i).getItem();
            if (!p.isEmpty() && p.getItem() instanceof ArmorPlateItem ap) {
                bulletPct += Math.round(ap.bulletReduction() * 100.0F);
                generalPct += Math.round(ap.generalReduction() * 100.0F);
                filledCount++;
            }
        }
        if (filledCount != this.cachedFilledCount
                || bulletPct != this.cachedBulletPct
                || generalPct != this.cachedGeneralPct) {
            this.cachedFilledCount = filledCount;
            this.cachedBulletPct = bulletPct;
            this.cachedGeneralPct = generalPct;
            this.cachedSummary = filledCount + "/" + maxSlots + " Plates | +" + bulletPct + "% Bullet | +" + generalPct + "% Impact";
            this.cachedSummaryWidth = this.font.width(this.cachedSummary);
        }
        int sumColor = filledCount > 0 ? 0x55FF55 : 0xAAAAAA;
        graphics.drawString(this.font, this.cachedSummary, left + (WIN_W - this.cachedSummaryWidth) / 2, top + 54, sumColor, false);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // Top title
        graphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 0xF0C040, false);
        // Player inventory label
        graphics.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, 0x808E9B, false);
    }

    @Override
    protected void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderTooltip(graphics, mouseX, mouseY);

        // Custom tooltip when hovering over empty plate slots
        if (this.hoveredSlot instanceof ArmorPlateMenu.PlateSlot plateSlot && !plateSlot.hasItem()) {
            List<Component> tip = this.menu.getMaxPlateSlots() == 1 ? INSERT_EMPTY_TOOLTIP
                    : (plateSlot.getPlateIndex() == 0 ? FRONT_EMPTY_TOOLTIP : BACK_EMPTY_TOOLTIP);
            graphics.renderComponentTooltip(this.font, tip, mouseX, mouseY);
        }
    }
}
