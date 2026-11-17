package com.poseidon.sem_classsystem.menu;

import com.poseidon.sem_classsystem.block.ArmorerBenchBlockEntity;
import com.poseidon.sem_classsystem.item.ArmorPlateItem;
import com.poseidon.sem_classsystem.item.BhqArmorItem;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;

// Container menu for the Armorer Bench:
//   Slot 0      : Gear Fitting Slot (BhqArmorItem vest/helmet)
//   Slot 1      : Damaged Armor Plate
//   Slot 2      : Repair Material
//   Slots 3..14 : 12-slot Plate Supply Rack (2 rows x 6)
//   Slots 15..16: Live Plate Fitting Slots (synced with Slot 0 armor NBT)
//   Slots 17..52: Player Inventory + Hotbar
public class ArmorerBenchMenu extends AbstractContainerMenu {

    public static final int BUTTON_REPAIR = 0;

    private static final int BENCH_SLOTS = ArmorerBenchBlockEntity.TOTAL_SLOTS; // 15
    private static final int FITTING_SLOTS = 2;
    private static final int PLAYER_INV_START = BENCH_SLOTS + FITTING_SLOTS;    // 17
    private static final int PLAYER_INV_END = PLAYER_INV_START + 36;            // 53

    private final ArmorerBenchBlockEntity bench;
    private final Container container;
    private final SimpleContainer fittingContainer = new SimpleContainer(2);
    private boolean syncingFitting = false;
    private ItemStack lastSyncedGear = ItemStack.EMPTY;

    // Client-side decoder constructor
    public ArmorerBenchMenu(int id, Inventory playerInv, FriendlyByteBuf buf) {
        this(id, playerInv, resolveClientBench(playerInv, buf.readBlockPos()));
    }

    private static ArmorerBenchBlockEntity resolveClientBench(Inventory inv, BlockPos pos) {
        BlockEntity be = inv.player.level().getBlockEntity(pos);
        return be instanceof ArmorerBenchBlockEntity bench ? bench : null;
    }

    public ArmorerBenchMenu(int id, Inventory playerInv, ArmorerBenchBlockEntity bench) {
        super(SemcsRegistries.ARMORER_BENCH_MENU.get(), id);
        this.bench = bench;
        this.container = bench != null ? bench : new SimpleContainer(BENCH_SLOTS);

        // 0: Gear fitting slot (left column)
        this.addSlot(new Slot(this.container, ArmorerBenchBlockEntity.SLOT_GEAR, 25, 22) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof BhqArmorItem && ArmorPlateItem.maxPlateSlots(stack) > 0;
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }

            @Override
            public void setChanged() {
                super.setChanged();
                refreshFittingFromGear();
            }
        });

        // 1: Damaged plate slot (repair section)
        this.addSlot(new Slot(this.container, ArmorerBenchBlockEntity.SLOT_REPAIR_PLATE, 86, 22) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof ArmorPlateItem;
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });

        // 2: Repair material slot (repair section)
        this.addSlot(new Slot(this.container, ArmorerBenchBlockEntity.SLOT_REPAIR_MAT, 134, 22));

        // 3..14: 12-slot Plate Supply Rack (2 rows x 6 cols on right/bottom panel)
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 6; col++) {
                int slotIdx = ArmorerBenchBlockEntity.RACK_START + row * 6 + col;
                this.addSlot(new Slot(this.container, slotIdx, 62 + col * 18, 52 + row * 18) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return stack.getItem() instanceof ArmorPlateItem;
                    }

                    @Override
                    public int getMaxStackSize() {
                        return 1;
                    }
                });
            }
        }

        // 15..16: Live plate fitting slots (Front / Back or Insert under Gear slot)
        this.addSlot(new FittingPlateSlot(this.fittingContainer, 0, 15, 52, 0));
        this.addSlot(new FittingPlateSlot(this.fittingContainer, 1, 35, 52, 1));

        this.fittingContainer.addListener(c -> writeFittingBackToGear());
        refreshFittingFromGear();

        // 17..43: Player inventory
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 102 + row * 18));
            }
        }
        // 44..52: Hotbar
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, 8 + col * 18, 160));
        }
    }

    public ItemStack getGearStack() {
        return this.container.getItem(ArmorerBenchBlockEntity.SLOT_GEAR);
    }

    public int getGearMaxPlateSlots() {
        return ArmorPlateItem.maxPlateSlots(getGearStack());
    }

    public boolean canRepairNow() {
        ItemStack plate = this.container.getItem(ArmorerBenchBlockEntity.SLOT_REPAIR_PLATE);
        ItemStack mat = this.container.getItem(ArmorerBenchBlockEntity.SLOT_REPAIR_MAT);
        if (plate.isEmpty() || !(plate.getItem() instanceof ArmorPlateItem ap) || !plate.isDamaged()) {
            return false;
        }
        return !mat.isEmpty() && ap.isValidRepairItem(plate, mat);
    }

    private void refreshFittingFromGear() {
        if (this.syncingFitting) {
            return;
        }
        this.syncingFitting = true;
        try {
            ItemStack gear = getGearStack();
            this.lastSyncedGear = gear;
            int max = ArmorPlateItem.maxPlateSlots(gear);
            List<ItemStack> plates = ArmorPlateItem.getPlates(gear);
            for (int i = 0; i < 2; i++) {
                ItemStack p = (i < max && i < plates.size()) ? plates.get(i).copy() : ItemStack.EMPTY;
                this.fittingContainer.setItem(i, p);
            }
        } finally {
            this.syncingFitting = false;
        }
    }

    private void writeFittingBackToGear() {
        if (this.syncingFitting) {
            return;
        }
        ItemStack gear = getGearStack();
        int max = ArmorPlateItem.maxPlateSlots(gear);
        if (max <= 0 || gear.isEmpty()) {
            return;
        }
        this.syncingFitting = true;
        try {
            List<ItemStack> updated = new ArrayList<>(max);
            for (int i = 0; i < max; i++) {
                updated.add(this.fittingContainer.getItem(i));
            }
            ArmorPlateItem.setPlates(gear, updated);
            this.container.setChanged();
        } finally {
            this.syncingFitting = false;
        }
    }

    @Override
    public void broadcastChanges() {
        ItemStack gear = getGearStack();
        if (gear != this.lastSyncedGear) {
            refreshFittingFromGear();
        }
        super.broadcastChanges();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id == BUTTON_REPAIR && this.bench != null && !player.level().isClientSide) {
            if (this.bench.repairLoadedPlate()) {
                broadcastChanges();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        return this.container.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack out = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        out = stack.copy();

        if (index < PLAYER_INV_START) {
            // Bench / fitting slot -> player inventory
            if (!this.moveItemStackTo(stack, PLAYER_INV_START, PLAYER_INV_END, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // Player inventory -> bench
            if (stack.getItem() instanceof BhqArmorItem && ArmorPlateItem.maxPlateSlots(stack) > 0) {
                if (!this.moveItemStackTo(stack, 0, 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (stack.getItem() instanceof ArmorPlateItem) {
                boolean moved = false;
                if (stack.isDamaged() && !this.slots.get(1).hasItem()) {
                    moved = this.moveItemStackTo(stack, 1, 2, false);
                }
                if (!moved && getGearMaxPlateSlots() > 0) {
                    moved = this.moveItemStackTo(stack, BENCH_SLOTS, PLAYER_INV_START, false);
                }
                if (!moved) {
                    moved = this.moveItemStackTo(stack, ArmorerBenchBlockEntity.RACK_START, BENCH_SLOTS, false);
                }
                if (!moved) {
                    return ItemStack.EMPTY;
                }
            } else {
                // Non-plate item -> repair material slot if valid for loaded plate, else swap inv/hotbar
                ItemStack repairPlate = this.container.getItem(ArmorerBenchBlockEntity.SLOT_REPAIR_PLATE);
                if (repairPlate.getItem() instanceof ArmorPlateItem ap && ap.isValidRepairItem(repairPlate, stack)) {
                    if (!this.moveItemStackTo(stack, 2, 3, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (index < PLAYER_INV_START + 27) {
                    if (!this.moveItemStackTo(stack, PLAYER_INV_START + 27, PLAYER_INV_END, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (!this.moveItemStackTo(stack, PLAYER_INV_START, PLAYER_INV_START + 27, false)) {
                    return ItemStack.EMPTY;
                }
            }
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == out.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return out;
    }

    private class FittingPlateSlot extends Slot {
        private final int plateIndex;

        FittingPlateSlot(Container container, int index, int x, int y, int plateIndex) {
            super(container, index, x, y);
            this.plateIndex = plateIndex;
        }

        @Override
        public boolean isActive() {
            return getGearMaxPlateSlots() > this.plateIndex;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return isActive() && ArmorPlateItem.canAcceptPlate(getGearStack(), stack);
        }

        @Override
        public boolean mayPickup(Player player) {
            return isActive() && super.mayPickup(player);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}
