package com.poseidon.sem_classsystem.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.client.gui.screens.PmcUnitScreen;
import net.nekoyuni.SimpleEnemyMod.compat.curios.CuriosHelper;
import net.nekoyuni.SimpleEnemyMod.compat.geckolib.GeckoCompat;
import net.nekoyuni.SimpleEnemyMod.compat.geckolib.GeckoCompatClient;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Client-only support for dynamic Curios GUI panel drawing and safe Curios armor rendering.
public final class CuriosClientSupport {

    private CuriosClientSupport() {
    }

    public static ResourceLocation resolveSlotIcon(String identifier) {
        ResourceLocation specific = ResourceLocation.fromNamespaceAndPath(
                "curios", "slot/empty_" + identifier + "_slot");
        ResourceLocation texPath = ResourceLocation.fromNamespaceAndPath(
                "curios", "textures/slot/empty_" + identifier + "_slot.png");
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getResourceManager() != null
                    && mc.getResourceManager().getResource(texPath).isPresent()) {
                return specific;
            }
        } catch (Throwable ignored) {
        }
        return ResourceLocation.fromNamespaceAndPath("curios", "slot/empty_curio_slot");
    }

    // Draws a dynamic multi-column Curios panel to the left of PmcUnitScreen
    // that expands automatically to fit all active Curios slots on the unit.
    public static void renderDynamicCuriosOverlay(GuiGraphics g, int guiLeft, int guiTop) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof PmcUnitScreen screen)) {
            return;
        }
        List<Slot> curioSlots = new ArrayList<>();
        for (Slot s : screen.getMenu().slots) {
            if (s.x < 0) {
                curioSlots.add(s);
            }
        }
        if (curioSlots.isEmpty()) {
            return;
        }

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Slot s : curioSlots) {
            int sx = guiLeft + s.x;
            int sy = guiTop + s.y;
            if (sx < minX) minX = sx;
            if (sx + 16 > maxX) maxX = sx + 16;
            if (sy < minY) minY = sy;
            if (sy + 16 > maxY) maxY = sy + 16;
        }

        int pLeft = minX - 6;
        int pTop = minY - 6;
        int pRight = maxX + 6;
        int pBottom = maxY + 6;

        // Outer border + vanilla-styled panel fill + highlight/shadow bevels
        g.fill(pLeft - 1, pTop - 1, pRight + 1, pBottom + 1, 0xFF000000);
        g.fill(pLeft, pTop, pRight, pBottom, 0xFFC6C6C6);
        g.fill(pLeft, pTop, pRight - 1, pTop + 2, 0xFFFFFFFF);
        g.fill(pLeft, pTop, pLeft + 2, pBottom - 1, 0xFFFFFFFF);
        g.fill(pLeft + 1, pBottom - 2, pRight, pBottom, 0xFF555555);
        g.fill(pRight - 2, pTop + 1, pRight, pBottom, 0xFF555555);

        // Individual 18x18 recessed slot frames
        for (Slot s : curioSlots) {
            int sx = guiLeft + s.x - 1;
            int sy = guiTop + s.y - 1;
            g.fill(sx, sy, sx + 18, sy + 18, 0xFF8B8B8B);
            g.fill(sx, sy, sx + 17, sy + 1, 0xFF373737);
            g.fill(sx, sy, sx + 1, sy + 17, 0xFF373737);
            g.fill(sx + 1, sy + 17, sx + 18, sy + 18, 0xFFFFFFFF);
            g.fill(sx + 17, sy + 1, sx + 18, sy + 18, 0xFFFFFFFF);
        }
    }

    // Only passes Curios stacks to GeckoArmorLayerImpl if they are genuine
    // GeckoLib armor or ArmorItems, preventing Artifacts rings/scarves/goggles
    // from being rendered as a corrupted humanoid armor suit.
    public static <T extends LivingEntity> void renderSafeCuriosArmor(
            T entity, CuriosHelper.CuriosPieceRenderer<T> renderer) {
        CuriosApi.getCuriosInventory(entity).ifPresent(curiosInventory -> {
            for (Map.Entry<String, ICurioStacksHandler> entry : curiosInventory.getCurios().entrySet()) {
                String slotId = entry.getKey();
                ICurioStacksHandler handler = entry.getValue();
                if (!handler.isVisible()) {
                    continue;
                }
                EquipmentSlot mapped = mapSlotToEquipment(slotId);
                IDynamicStackHandler stacks = handler.getStacks();
                var renders = handler.getRenders();
                for (int i = 0; i < stacks.getSlots(); i++) {
                    if (i < renders.size() && !Boolean.TRUE.equals(renders.get(i))) {
                        continue;
                    }
                    ItemStack stack = stacks.getStackInSlot(i);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    boolean isGeckoArmor = GeckoCompat.LOADED && GeckoCompatClient.isGeckoArmor(stack);
                    if (!isGeckoArmor && !(stack.getItem() instanceof ArmorItem)) {
                        continue;
                    }
                    renderer.render(stack, mapped);
                }
            }
        });
    }

    private static EquipmentSlot mapSlotToEquipment(String slotId) {
        return switch (slotId) {
            case "head", "necklace", "curio" -> EquipmentSlot.HEAD;
            case "belt", "ring", "charm" -> EquipmentSlot.LEGS;
            case "feet" -> EquipmentSlot.FEET;
            default -> EquipmentSlot.CHEST;
        };
    }
}
