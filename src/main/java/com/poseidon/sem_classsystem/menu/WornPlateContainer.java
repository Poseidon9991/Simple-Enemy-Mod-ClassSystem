package com.poseidon.sem_classsystem.menu;

import com.poseidon.sem_classsystem.item.ArmorPlateItem;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Live container view over the player's worn Helmet (slot 0: ballistic insert)
 * and Chestplate (slot 1: front plate, slot 2: back plate), allowing direct
 * management inside the standard Survival Inventory screen like WARBORN.
 */
public class WornPlateContainer implements Container {

    public static final int SLOT_HEAD_INSERT = 0;
    public static final int SLOT_CHEST_FRONT = 1;
    public static final int SLOT_CHEST_BACK = 2;

    private final Player player;

    public WornPlateContainer(Player player) {
        this.player = player;
    }

    private static EquipmentSlot equipmentForIndex(int index) {
        return index == SLOT_HEAD_INSERT ? EquipmentSlot.HEAD : EquipmentSlot.CHEST;
    }

    private static int plateIndexFor(int index) {
        return index == SLOT_CHEST_BACK ? 1 : 0;
    }

    @Override
    public int getContainerSize() {
        return 3;
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < 3; i++) {
            if (!getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int index) {
        if (index < 0 || index >= 3) {
            return ItemStack.EMPTY;
        }
        ItemStack armor = this.player.getItemBySlot(equipmentForIndex(index));
        int pIdx = plateIndexFor(index);
        if (ArmorPlateItem.maxPlateSlots(armor) <= pIdx) {
            return ItemStack.EMPTY;
        }
        List<ItemStack> plates = ArmorPlateItem.getPlates(armor);
        return pIdx < plates.size() ? plates.get(pIdx) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int index, int count) {
        if (count <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack cur = getItem(index);
        if (cur.isEmpty()) {
            return ItemStack.EMPTY;
        }
        setItem(index, ItemStack.EMPTY);
        return cur;
    }

    @Override
    public ItemStack removeItemNoUpdate(int index) {
        ItemStack cur = getItem(index);
        if (cur.isEmpty()) {
            return ItemStack.EMPTY;
        }
        setItem(index, ItemStack.EMPTY);
        return cur;
    }

    @Override
    public void setItem(int index, ItemStack stack) {
        if (index < 0 || index >= 3) {
            return;
        }
        ItemStack armor = this.player.getItemBySlot(equipmentForIndex(index));
        int pIdx = plateIndexFor(index);
        int max = ArmorPlateItem.maxPlateSlots(armor);
        if (max <= pIdx) {
            return;
        }
        List<ItemStack> plates = ArmorPlateItem.getPlates(armor);
        while (plates.size() < max) {
            plates.add(ItemStack.EMPTY);
        }
        ItemStack toStore = stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
        if (!toStore.isEmpty()) {
            toStore.setCount(1);
        }
        plates.set(pIdx, toStore);
        ArmorPlateItem.setPlates(armor, plates);
        setChanged();
    }

    @Override
    public void setChanged() {
        this.player.getInventory().setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < 3; i++) {
            setItem(i, ItemStack.EMPTY);
        }
    }

    /**
     * Player-inventory slot bound to one ballistic plate position on the worn
     * helmet or chestplate. Automatically activates whenever compatible armor
     * is equipped and hides itself in vanilla/creative views otherwise.
     */
    public static class WornArmorPlateSlot extends Slot {
        /**
         * Set by client init so server-side code never references client screen classes.
         */
        public static BooleanSupplier creativeScreenCheck = () -> false;

        private final Player player;
        private final EquipmentSlot equipSlot;
        private final int plateIndex;

        public WornArmorPlateSlot(WornPlateContainer container, int slotIndex, int x, int y,
                                  Player player, EquipmentSlot equipSlot, int plateIndex) {
            super(container, slotIndex, x, y);
            this.player = player;
            this.equipSlot = equipSlot;
            this.plateIndex = plateIndex;
        }

        public EquipmentSlot getEquipSlot() {
            return this.equipSlot;
        }

        public int getPlateIndex() {
            return this.plateIndex;
        }

        public ItemStack getWornArmor() {
            return this.player.getItemBySlot(this.equipSlot);
        }

        @Override
        public boolean isActive() {
            if (this.player.level().isClientSide && creativeScreenCheck.getAsBoolean()) {
                return false;
            }
            return ArmorPlateItem.maxPlateSlots(getWornArmor()) > this.plateIndex;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return isActive() && ArmorPlateItem.canAcceptPlate(getWornArmor(), stack);
        }

        @Override
        public boolean mayPickup(Player player) {
            return isActive() && super.mayPickup(player);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return 1;
        }
    }
}
