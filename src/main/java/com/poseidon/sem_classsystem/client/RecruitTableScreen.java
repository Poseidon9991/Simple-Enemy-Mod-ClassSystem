package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.menu.RecruitTableMenu;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.config.common.MiscConfig;
import net.nekoyuni.SimpleEnemyMod.registry.ModItems;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// Tactical Recruitment GUI for PMC and BHQ Recruit Tables.
// Displays 9 selectable class cards (Random + 8 main weapon classes) with
// custom spawn egg icons, role descriptions, smooth hover/selection states,
// tooltips, keyboard shortcuts (1-9 / arrows / Enter), and Recruit button.
public class RecruitTableScreen extends AbstractContainerScreen<RecruitTableMenu> {

    private static final int WIN_W = 256;
    private static final int WIN_H = 246;

    private static final int CARD_W = 116;
    private static final int CARD_H = 30;
    private static final int GRID_X = 10;
    private static final int GRID_Y = 30;
    private static final int GAP_X = 4;
    private static final int GAP_Y = 4;

    private static final String[] ROLE_SUBTITLES = {
            "Any weapon class",
            "SMG / CQB Rush",
            "Rifle / Versatile",
            "Shotgun / Close",
            "Sniper / Long Range",
            "Heavy MG / Suppress",
            "Launcher / Ordnance",
            "Generalist / Any Gun",
            "Medic / Heals Squad"
    };

    private static final String[] ROLE_DETAILS = {
            "Rolls a balanced random operator class from the faction pool.",
            "High mobility CQB specialist. Aggressive room-clearing posture.",
            "Standard infantry rifleman. Reliable across all engagement ranges.",
            "Close-quarters breacher with high stopping power and wide stance.",
            "Long-range marksman. Uses deep one-knee and prone firing stances.",
            "Squad automatic gunner. Suppresses targets from a braced bipod crouch.",
            "Anti-armor/ordnance specialist equipped with heavy launcher kit.",
            "Versatile multi-role operator capable of fielding any weapon type.",
            "Combat medic. Autonomously heals wounded squadmates under fire."
    };

    private final float[] cardHoverAnim = new float[9];
    private long lastFrameMs = Util.getMillis();

    public RecruitTableScreen(RecruitTableMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = WIN_W;
        this.imageHeight = WIN_H;
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Labels are drawn inside renderBg for full custom layout
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        this.renderCardTooltips(g, mouseX, mouseY);
        this.renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        long now = Util.getMillis();
        float dt = Mth.clamp((now - this.lastFrameMs) / 1000.0F, 0.0F, 0.1F);
        this.lastFrameMs = now;
        boolean reduced = this.minecraft != null && this.minecraft.options != null
                && this.minecraft.options.screenEffectScale().get() <= 0.01D;

        int x0 = this.leftPos;
        int y0 = this.topPos;
        boolean bhq = this.menu.isBhq();

        // Outer tactical frame with drop shadow
        int accentHeader = bhq ? 0xFFD8B36A : 0xFF4C82C2;
        g.fill(x0 + 3, y0 + 3, x0 + WIN_W + 4, y0 + WIN_H + 4, 0x66000000);
        g.fill(x0 - 2, y0 - 2, x0 + WIN_W + 2, y0 + WIN_H + 2, 0xFF0A0C10);
        g.fill(x0 - 1, y0 - 1, x0 + WIN_W + 1, y0 + WIN_H + 1, 0xFF2C3440);
        g.fill(x0, y0, x0 + WIN_W, y0 + WIN_H, 0xFF161A20);
        g.fill(x0, y0, x0 + WIN_W, y0 + 24, 0xFF0F1217);
        g.fill(x0, y0 + 23, x0 + WIN_W, y0 + 25, accentHeader);

        // Title
        String headerTitle = bhq ? "BHQ OPERATOR RECRUITMENT" : "PMC UNIT RECRUITMENT";
        g.drawString(this.font, headerTitle, x0 + 10, y0 + 8, 0xFFF0F2F5, true);

        // Currency indicator on top right
        Item currency = MiscConfig.getRecruitItem();
        int cost = this.menu.recruitPrice();
        int owned = this.minecraft != null && this.minecraft.player != null
                ? RecruitTableMenu.countItemInInventory(this.minecraft.player, currency)
                : 0;
        boolean canAfford = (this.minecraft != null && this.minecraft.player != null
                && this.minecraft.player.getAbilities().instabuild) || owned >= cost;

        ItemStack currencyStack = new ItemStack(currency);
        g.renderItem(currencyStack, x0 + WIN_W - 72, y0 + 4);
        String costText = owned + " / " + cost;
        g.drawString(this.font, costText, x0 + WIN_W - 52, y0 + 8,
                canAfford ? 0xFF62D862 : 0xFFFF5E5E, true);

        // 2x5 grid of Class Cards (9 options: Random + 8 classes)
        int selected = Mth.clamp(this.menu.selectedIndex(), 0, 8);
        for (int idx = 0; idx < 9; idx++) {
            int col = idx % 2;
            int row = idx / 2;
            int cx = x0 + GRID_X + col * (CARD_W + GAP_X);
            int cy = y0 + GRID_Y + row * (CARD_H + GAP_Y);

            boolean isSel = (idx == selected);
            boolean isHover = mouseX >= cx && mouseX < cx + CARD_W && mouseY >= cy && mouseY < cy + CARD_H;

            float targetHov = (isHover || isSel) ? 1.0F : 0.0F;
            if (reduced) {
                this.cardHoverAnim[idx] = targetHov;
            } else {
                this.cardHoverAnim[idx] = Mth.lerp(Math.min(1.0F, dt * 14.0F), this.cardHoverAnim[idx], targetHov);
            }

            WeaponClass cls = RecruitTableMenu.classForIndex(idx);
            int accent = cls != null ? (0xFF000000 | SemcsRegistries.classAccent(cls)) : 0xFFD9A62E;

            int borderCol = isSel ? accent : (isHover ? 0xFF94A0B2 : 0xFF282E38);
            int bgCol = isSel ? 0xFF242C38 : (isHover ? 0xFF1E2530 : 0xFF12161C);

            g.fill(cx, cy, cx + CARD_W, cy + CARD_H, borderCol);
            g.fill(cx + 1, cy + 1, cx + CARD_W - 1, cy + CARD_H - 1, bgCol);
            int barW = isSel ? 4 : (isHover ? 3 : 2);
            g.fill(cx + 1, cy + 1, cx + 1 + barW, cy + CARD_H - 1, accent);

            // Egg icon
            ItemStack iconStack = iconForOption(bhq, cls);
            g.renderItem(iconStack, cx + 6, cy + 7);

            // Class Name & Subtitle + hotkey hint
            String name = cls == null ? "Random Class" : ClassSystemConfig.classNickname(cls);
            g.drawString(this.font, this.font.plainSubstrByWidth(name, CARD_W - 36),
                    cx + 25, cy + 5, isSel ? 0xFFFFFFFF : 0xFFDCE2EC, true);
            g.drawString(this.font, this.font.plainSubstrByWidth(ROLE_SUBTITLES[idx], CARD_W - 28),
                    cx + 25, cy + 17, 0xFF8C96A6, false);
            g.drawString(this.font, String.valueOf(idx + 1), cx + CARD_W - 9, cy + 4,
                    isSel ? accent : 0xFF485260, false);
        }

        // Selected class info strip above the Recruit button
        int infoY = y0 + WIN_H - 42;
        g.fill(x0 + 10, infoY, x0 + WIN_W - 10, infoY + 13, 0xFF10141A);
        g.drawString(this.font, this.font.plainSubstrByWidth(ROLE_DETAILS[selected], WIN_W - 28),
                x0 + 14, infoY + 3, 0xFFA4B0C0, false);

        // Bottom Recruit Button
        int btnX = x0 + 10;
        int btnY = y0 + WIN_H - 26;
        int btnW = WIN_W - 20;
        int btnH = 20;
        boolean btnHover = mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH;

        int btnBorder = canAfford ? (btnHover ? 0xFF78FF78 : 0xFF3CA846) : 0xFF7A2E2E;
        int btnFill = canAfford ? (btnHover ? 0xFF2E7D36 : 0xFF215E28) : 0xFF3A1E1E;
        g.fill(btnX, btnY, btnX + btnW, btnY + btnH, btnBorder);
        g.fill(btnX + 1, btnY + 1, btnX + btnW - 1, btnY + btnH - 1, btnFill);

        WeaponClass selCls = RecruitTableMenu.classForIndex(selected);
        String selName = selCls == null ? "RANDOM" : ClassSystemConfig.classNickname(selCls).toUpperCase();
        String btnLabel = "RECRUIT " + (bhq ? "BHQ " : "PMC ") + selName + " (" + cost + " " + currency.getDescription().getString() + ")";
        String trimmedLabel = this.font.plainSubstrByWidth(btnLabel, btnW - 8);
        int textW = this.font.width(trimmedLabel);
        g.drawString(this.font, trimmedLabel, btnX + (btnW - textW) / 2, btnY + 6,
                canAfford ? 0xFFFFFFFF : 0xFFCC8888, true);
    }

    private void renderCardTooltips(GuiGraphics g, int mouseX, int mouseY) {
        int x0 = this.leftPos;
        int y0 = this.topPos;
        for (int idx = 0; idx < 9; idx++) {
            int col = idx % 2;
            int row = idx / 2;
            int cx = x0 + GRID_X + col * (CARD_W + GAP_X);
            int cy = y0 + GRID_Y + row * (CARD_H + GAP_Y);
            if (mouseX >= cx && mouseX < cx + CARD_W && mouseY >= cy && mouseY < cy + CARD_H) {
                WeaponClass cls = RecruitTableMenu.classForIndex(idx);
                String name = cls == null ? "Random Class" : ClassSystemConfig.classNickname(cls);
                List<Component> tip = new ArrayList<>();
                tip.add(Component.literal(name + " (" + ROLE_SUBTITLES[idx] + ")")
                        .withStyle(ChatFormatting.GOLD));
                tip.add(Component.literal(ROLE_DETAILS[idx]).withStyle(ChatFormatting.GRAY));
                tip.add(Component.literal("Left-click: Select  •  Right-click / Enter: Recruit")
                        .withStyle(ChatFormatting.DARK_GRAY));
                g.renderTooltip(this.font, tip, Optional.empty(), mouseX, mouseY);
                return;
            }
        }
    }

    private static ItemStack iconForOption(boolean bhq, WeaponClass cls) {
        if (cls == null) {
            return bhq
                    ? new ItemStack(SemcsRegistries.BHQ_UNIT_SPAWN_EGG.get())
                    : new ItemStack(ModItems.PMC_UNIT_SPAWN_EGG.get());
        }
        String prefix = bhq ? "bhq_" : "pmc_";
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                SemClassSystem.MODID, prefix + cls.configKey() + "_spawn_egg");
        Item item = ForgeRegistries.ITEMS.getValue(id);
        return item != null ? new ItemStack(item) : new ItemStack(SemcsRegistries.BHQ_UNIT_SPAWN_EGG.get());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x0 = this.leftPos;
        int y0 = this.topPos;

        for (int idx = 0; idx < 9; idx++) {
            int col = idx % 2;
            int row = idx / 2;
            int cx = x0 + GRID_X + col * (CARD_W + GAP_X);
            int cy = y0 + GRID_Y + row * (CARD_H + GAP_Y);
            if (mouseX >= cx && mouseX < cx + CARD_W && mouseY >= cy && mouseY < cy + CARD_H) {
                if (this.minecraft != null && this.minecraft.gameMode != null) {
                    int pktId = (button == 1) ? (10 + idx) : idx;
                    this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, pktId);
                    Minecraft.getInstance().getSoundManager().play(
                            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                }
                return true;
            }
        }

        int btnX = x0 + 10;
        int btnY = y0 + WIN_H - 26;
        int btnW = WIN_W - 20;
        int btnH = 20;
        if (mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH) {
            triggerRecruitSelected();
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Number keys 1..9 select card 0..8
        if (keyCode >= 49 && keyCode <= 57) {
            int idx = keyCode - 49;
            if (this.minecraft != null && this.minecraft.gameMode != null) {
                this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, idx);
                Minecraft.getInstance().getSoundManager().play(
                        SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            }
            return true;
        }
        // Enter or Space recruits the selected option
        if (keyCode == 257 || keyCode == 32) {
            triggerRecruitSelected();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void triggerRecruitSelected() {
        if (this.minecraft != null && this.minecraft.gameMode != null) {
            int sel = Mth.clamp(this.menu.selectedIndex(), 0, 8);
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 10 + sel);
            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }
}
