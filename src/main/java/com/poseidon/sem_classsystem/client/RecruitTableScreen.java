package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.menu.RecruitTableMenu;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.config.common.MiscConfig;

// Tactical Recruitment GUI for PMC and BHQ Recruit Tables.
// Displays 9 selectable class cards (Random + 8 main weapon classes) with
// custom spawn egg icons, role descriptions, cost indicator, and Recruit button.
public class RecruitTableScreen extends AbstractContainerScreen<RecruitTableMenu> {

    private static final int WIN_W = 248;
    private static final int WIN_H = 230;   // 5 card rows + header + recruit bar

    private static final int CARD_W = 112;
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
        this.renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x0 = this.leftPos;
        int y0 = this.topPos;
        boolean bhq = this.menu.isBhq();

        // Outer tactical frame
        int accentHeader = bhq ? 0xFFBA1E28 : 0xFF3E6EA8;
        g.fill(x0 - 2, y0 - 2, x0 + WIN_W + 2, y0 + WIN_H + 2, 0xFF0A0C10);
        g.fill(x0, y0, x0 + WIN_W, y0 + WIN_H, 0xFF181C22);
        g.fill(x0, y0, x0 + WIN_W, y0 + 24, 0xFF101318);
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
        g.renderItem(currencyStack, x0 + WIN_W - 68, y0 + 4);
        String costText = owned + " / " + cost;
        g.drawString(this.font, costText, x0 + WIN_W - 48, y0 + 8,
                canAfford ? 0xFF55FF55 : 0xFFFF5555, true);

        // 2x5 grid of Class Cards (9 options: Random + 8 classes)
        int selected = this.menu.selectedIndex();
        for (int idx = 0; idx < 9; idx++) {
            int col = idx % 2;
            int row = idx / 2;
            int cx = x0 + GRID_X + col * (CARD_W + GAP_X);
            int cy = y0 + GRID_Y + row * (CARD_H + GAP_Y);

            boolean isSel = (idx == selected);
            boolean isHover = mouseX >= cx && mouseX < cx + CARD_W && mouseY >= cy && mouseY < cy + CARD_H;

            WeaponClass cls = RecruitTableMenu.classForIndex(idx);
            int accent = cls != null ? (0xFF000000 | SemcsRegistries.classAccent(cls)) : 0xFFD9A62E;

            int borderCol = isSel ? accent : (isHover ? 0xFF8892A0 : 0xFF2C323C);
            int bgCol = isSel ? 0xFF252C38 : (isHover ? 0xFF202630 : 0xFF14181E);

            g.fill(cx, cy, cx + CARD_W, cy + CARD_H, borderCol);
            g.fill(cx + 1, cy + 1, cx + CARD_W - 1, cy + CARD_H - 1, bgCol);
            // Left color bar
            g.fill(cx + 1, cy + 1, cx + 4, cy + CARD_H - 1, accent);

            // Egg icon
            ItemStack iconStack = iconForOption(bhq, cls);
            g.renderItem(iconStack, cx + 7, cy + 7);

            // Class Name & Subtitle
            String name = cls == null ? "Random Class" : ClassSystemConfig.classNickname(cls);
            g.drawString(this.font, name, cx + 26, cy + 6, isSel ? 0xFFFFFFFF : 0xFFD8DEE8, true);
            g.drawString(this.font, ROLE_SUBTITLES[idx], cx + 26, cy + 17, 0xFF8C96A6, false);
        }

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
        int textW = this.font.width(btnLabel);
        g.drawString(this.font, btnLabel, btnX + (btnW - textW) / 2, btnY + 6,
                canAfford ? 0xFFFFFFFF : 0xFFCC8888, true);
    }

    private static ItemStack iconForOption(boolean bhq, WeaponClass cls) {
        if (cls == null) {
            return new ItemStack(SemcsRegistries.BHQ_UNIT_SPAWN_EGG.get());
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

        // Check 9 class cards
        for (int idx = 0; idx < 9; idx++) {
            int col = idx % 2;
            int row = idx / 2;
            int cx = x0 + GRID_X + col * (CARD_W + GAP_X);
            int cy = y0 + GRID_Y + row * (CARD_H + GAP_Y);
            if (mouseX >= cx && mouseX < cx + CARD_W && mouseY >= cy && mouseY < cy + CARD_H) {
                if (this.minecraft != null && this.minecraft.gameMode != null) {
                    // Right-click on a card recruits immediately; left-click selects it
                    int pktId = (button == 1) ? (10 + idx) : idx;
                    this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, pktId);
                    Minecraft.getInstance().getSoundManager().play(
                            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                }
                return true;
            }
        }

        // Check bottom Recruit button
        int btnX = x0 + 10;
        int btnY = y0 + WIN_H - 26;
        int btnW = WIN_W - 20;
        int btnH = 20;
        if (mouseX >= btnX && mouseX < btnX + btnW && mouseY >= btnY && mouseY < btnY + btnH) {
            if (this.minecraft != null && this.minecraft.gameMode != null) {
                int sel = Math.max(0, Math.min(8, this.menu.selectedIndex()));
                this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 10 + sel);
                Minecraft.getInstance().getSoundManager().play(
                        SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            }
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }
}
