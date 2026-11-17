package com.poseidon.sem_classsystem.menu;

import com.poseidon.sem_classsystem.block.MedicalStationBlockEntity;
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

// 18-slot Field Medical Station container + player inventory.
public class MedicalStationMenu extends AbstractContainerMenu {

    private static final int STATION_SLOTS = MedicalStationBlockEntity.SLOTS; // 18
    private static final int PLAYER_END = STATION_SLOTS + 36;                 // 54

    private final Container container;

    public MedicalStationMenu(int id, Inventory playerInv, FriendlyByteBuf buf) {
        this(id, playerInv, resolveClientStation(playerInv, buf.readBlockPos()));
    }

    private static MedicalStationBlockEntity resolveClientStation(Inventory inv, BlockPos pos) {
        BlockEntity be = inv.player.level().getBlockEntity(pos);
        return be instanceof MedicalStationBlockEntity station ? station : null;
    }

    public MedicalStationMenu(int id, Inventory playerInv, MedicalStationBlockEntity station) {
        super(SemcsRegistries.MEDICAL_STATION_MENU.get(), id);
        this.container = station != null ? station : new SimpleContainer(STATION_SLOTS);

        // 2 rows of 9 medical storage slots
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(this.container, col + row * 9, 8 + col * 18, 22 + row * 18));
            }
        }

        // Player inventory
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 72 + row * 18));
            }
        }
        // Hotbar
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, 8 + col * 18, 130));
        }
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

        if (index < STATION_SLOTS) {
            if (!this.moveItemStackTo(stack, STATION_SLOTS, PLAYER_END, true)) {
                return ItemStack.EMPTY;
            }
        } else if (!this.moveItemStackTo(stack, 0, STATION_SLOTS, false)) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return out;
    }
}
