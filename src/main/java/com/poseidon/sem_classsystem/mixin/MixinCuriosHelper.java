package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.client.CuriosClientSupport;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.items.SlotItemHandler;
import net.nekoyuni.SimpleEnemyMod.compat.curios.CuriosHelper;
import net.nekoyuni.SimpleEnemyMod.compat.curios.CuriosSlotRegister;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.util.Map;

// Replaces SEM's hardcoded 4-slot Curios setup with dynamic player-like Curios
// slots that adapt to all registered slot types and slot counts on the unit,
// and filters out non-armor Curios (Artifacts, charms, rings) from SEM's
// armor-suit layer so they never render with a missing purple/black texture.
@Mixin(CuriosHelper.class)
public abstract class MixinCuriosHelper {

    @Unique
    private static final int SEMCS$SLOTS_PER_COLUMN = 8;

    @Inject(method = "addCuriosSlots", at = @At("HEAD"), cancellable = true, remap = false)
    private static void semcs$addDynamicCuriosSlots(CuriosSlotRegister registerSlot,
                                                    LivingEntity entity,
                                                    CallbackInfo ci) {
        CuriosApi.getCuriosInventory(entity).ifPresent(curiosInventory -> {
            int index = 0;
            for (Map.Entry<String, ICurioStacksHandler> entry : curiosInventory.getCurios().entrySet()) {
                final String identifier = entry.getKey();
                ICurioStacksHandler stacksHandler = entry.getValue();
                if (!stacksHandler.isVisible()) {
                    continue;
                }
                IDynamicStackHandler stacks = stacksHandler.getStacks();
                for (int i = 0; i < stacks.getSlots(); i++) {
                    final int slotIdx = i;
                    int col = index / SEMCS$SLOTS_PER_COLUMN;
                    int row = index % SEMCS$SLOTS_PER_COLUMN;
                    int x = -23 - (col * 18);
                    int y = 19 + (row * 18);

                    SlotItemHandler slot = new SlotItemHandler(stacks, slotIdx, x, y) {
                        @Override
                        public boolean mayPlace(@NotNull ItemStack stack) {
                            if (stack.isEmpty()) {
                                return false;
                            }
                            SlotContext ctx = new SlotContext(identifier, entity, slotIdx, false, true);
                            return CuriosApi.isStackValid(ctx, stack) && super.mayPlace(stack);
                        }
                    };

                    if (FMLEnvironment.dist == Dist.CLIENT) {
                        ResourceLocation icon = CuriosClientSupport.resolveSlotIcon(identifier);
                        slot.setBackground(InventoryMenu.BLOCK_ATLAS, icon);
                    }
                    registerSlot.register(slot);
                    index++;
                }
            }
        });
        ci.cancel();
    }

    @Inject(method = "renderCuriosOverlay", at = @At("HEAD"), cancellable = true, remap = false)
    private static void semcs$renderDynamicOverlay(GuiGraphics guiGraphics, int x, int y, CallbackInfo ci) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            CuriosClientSupport.renderDynamicCuriosOverlay(guiGraphics, x, y);
        }
        ci.cancel();
    }

    @Inject(method = "renderCuriosSlots", at = @At("HEAD"), cancellable = true, remap = false)
    private static <T extends LivingEntity> void semcs$renderOnlyValidCurioArmor(
            T entity,
            CuriosHelper.CuriosPieceRenderer<T> renderer,
            CallbackInfo ci) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            CuriosClientSupport.renderSafeCuriosArmor(entity, renderer);
        }
        ci.cancel();
    }
}
