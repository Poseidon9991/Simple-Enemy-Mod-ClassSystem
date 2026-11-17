package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.item.ArmorPlateItem;
import com.poseidon.sem_classsystem.menu.WornPlateContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InventoryMenu.class)
public abstract class MixinInventoryMenu {

    @Unique
    private int semcs$plateStartIndex = -1;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void semcs$addPlateSlots(Inventory playerInventory, boolean active, Player owner, CallbackInfo ci) {
        InventoryMenu self = (InventoryMenu) (Object) this;
        AbstractContainerMenuAccessor accessor = (AbstractContainerMenuAccessor) this;
        this.semcs$plateStartIndex = self.slots.size();

        WornPlateContainer plateContainer = new WornPlateContainer(owner);
        // Placed in a vertical column at x=77 directly above the vanilla Offhand slot (77, 62),
        // aligned horizontally with Helmet (y=8), Chestplate (y=26), and Leggings (y=44).
        accessor.semcs$addSlot(new WornPlateContainer.WornArmorPlateSlot(
                plateContainer, WornPlateContainer.SLOT_HEAD_INSERT, 77, 8, owner, EquipmentSlot.HEAD, 0));
        accessor.semcs$addSlot(new WornPlateContainer.WornArmorPlateSlot(
                plateContainer, WornPlateContainer.SLOT_CHEST_FRONT, 77, 26, owner, EquipmentSlot.CHEST, 0));
        accessor.semcs$addSlot(new WornPlateContainer.WornArmorPlateSlot(
                plateContainer, WornPlateContainer.SLOT_CHEST_BACK, 77, 44, owner, EquipmentSlot.CHEST, 1));
    }

    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
    private void semcs$quickMovePlate(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        if (this.semcs$plateStartIndex < 0) {
            return;
        }
        InventoryMenu self = (InventoryMenu) (Object) this;
        AbstractContainerMenuAccessor accessor = (AbstractContainerMenuAccessor) this;
        int plateEnd = this.semcs$plateStartIndex + 3;
        if (index < 0 || index >= self.slots.size()) {
            return;
        }
        Slot slot = self.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return;
        }

        // 1. Shift-click OUT of a worn armor plate slot -> player inventory/hotbar
        if (index >= this.semcs$plateStartIndex && index < plateEnd) {
            ItemStack stack = slot.getItem();
            ItemStack copy = stack.copy();
            if (!accessor.semcs$moveItemStackTo(stack, InventoryMenu.INV_SLOT_START, InventoryMenu.USE_ROW_SLOT_END, false)) {
                cir.setReturnValue(ItemStack.EMPTY);
                return;
            }
            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
            slot.onTake(player, stack);
            cir.setReturnValue(copy);
            return;
        }

        // 2. Shift-click an ArmorPlateItem IN from inventory/hotbar -> active plate slot
        if (index >= InventoryMenu.INV_SLOT_START && index < InventoryMenu.USE_ROW_SLOT_END) {
            ItemStack stack = slot.getItem();
            if (stack.getItem() instanceof ArmorPlateItem) {
                ItemStack copy = stack.copy();
                if (accessor.semcs$moveItemStackTo(stack, this.semcs$plateStartIndex, plateEnd, false)) {
                    if (stack.isEmpty()) {
                        slot.setByPlayer(ItemStack.EMPTY);
                    } else {
                        slot.setChanged();
                    }
                    if (stack.getCount() != copy.getCount()) {
                        slot.onTake(player, stack);
                    }
                    cir.setReturnValue(copy);
                }
            }
        }
    }
}
