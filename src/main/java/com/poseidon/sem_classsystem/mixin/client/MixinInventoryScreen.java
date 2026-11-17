package com.poseidon.sem_classsystem.mixin.client;

import com.poseidon.sem_classsystem.item.ArmorPlateItem;
import com.poseidon.sem_classsystem.menu.WornPlateContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(InventoryScreen.class)
public abstract class MixinInventoryScreen {

    @Inject(method = "renderBg", at = @At("TAIL"))
    private void semcs$renderPlateSlotsBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY, CallbackInfo ci) {
        InventoryScreen self = (InventoryScreen) (Object) this;
        int left = self.getGuiLeft();
        int top = self.getGuiTop();
        Font font = Minecraft.getInstance().font;

        for (Slot slot : self.getMenu().slots) {
            if (slot instanceof WornPlateContainer.WornArmorPlateSlot plateSlot && plateSlot.isActive()) {
                int sx = left + slot.x;
                int sy = top + slot.y;

                // Vanilla-matched 18x18 slot bevel
                graphics.fill(sx - 1, sy - 1, sx + 17, sy, 0xFF373737);     // top dark
                graphics.fill(sx - 1, sy - 1, sx, sy + 17, 0xFF373737);     // left dark
                graphics.fill(sx, sy + 16, sx + 17, sy + 17, 0xFFFFFFFF);   // bottom light
                graphics.fill(sx + 16, sy, sx + 17, sy + 17, 0xFFFFFFFF);   // right light
                graphics.fill(sx, sy, sx + 16, sy + 16, 0xFF8B8B8B);        // inner slot bg

                if (!slot.hasItem()) {
                    // Subtle plate silhouette + slot tag (H / F / B)
                    graphics.fill(sx + 3, sy + 2, sx + 13, sy + 14, 0xFF737373);
                    graphics.fill(sx + 4, sy + 3, sx + 12, sy + 13, 0xFF7E7E7E);
                    String tag = semcs$slotTag(plateSlot);
                    int tw = font.width(tag);
                    graphics.drawString(font, tag, sx + 8 - tw / 2, sy + 4, 0x555555, false);
                }
            }
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void semcs$renderPlateSlotTooltip(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        InventoryScreen self = (InventoryScreen) (Object) this;
        Slot hovered = self.getSlotUnderMouse();
        if (hovered instanceof WornPlateContainer.WornArmorPlateSlot plateSlot
                && plateSlot.isActive()
                && !plateSlot.hasItem()) {
            ItemStack armor = plateSlot.getWornArmor();
            int maxTier = Math.max(1, ArmorPlateItem.maxAllowedTier(armor));
            String titleKey = semcs$slotTitleKey(plateSlot);
            List<Component> tooltip = List.of(
                    Component.translatable(titleKey).withStyle(ChatFormatting.GOLD),
                    Component.translatable("gui.sem_classsystem.plate.accepts_up_to", maxTier).withStyle(ChatFormatting.GRAY),
                    Component.translatable("gui.sem_classsystem.plate.inv_hint").withStyle(ChatFormatting.DARK_GRAY)
            );
            graphics.renderComponentTooltip(Minecraft.getInstance().font, tooltip, mouseX, mouseY);
        }
    }

    @Unique
    private static String semcs$slotTag(WornPlateContainer.WornArmorPlateSlot slot) {
        if (slot.getEquipSlot() == EquipmentSlot.HEAD) {
            return "H";
        }
        return slot.getPlateIndex() == 0 ? "F" : "B";
    }

    @Unique
    private static String semcs$slotTitleKey(WornPlateContainer.WornArmorPlateSlot slot) {
        if (slot.getEquipSlot() == EquipmentSlot.HEAD) {
            return "gui.sem_classsystem.plate.slot_insert";
        }
        return slot.getPlateIndex() == 0
                ? "gui.sem_classsystem.plate.slot_front"
                : "gui.sem_classsystem.plate.slot_back";
    }
}
