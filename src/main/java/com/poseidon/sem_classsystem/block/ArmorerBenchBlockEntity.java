package com.poseidon.sem_classsystem.block;

import com.poseidon.sem_classsystem.item.ArmorPlateItem;
import com.poseidon.sem_classsystem.item.BhqArmorItem;
import com.poseidon.sem_classsystem.menu.ArmorerBenchMenu;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// Armorer Bench storage:
//   Slot 0       : Gear Fitting Slot (BhqArmorItem vest or helmet)
//   Slot 1       : Damaged Armor Plate to repair
//   Slot 2       : Repair Material
//   Slots 3..14  : 12-slot Plate Supply Rack (exposed via ITEM_HANDLER for hoppers & unit resupply)
public class ArmorerBenchBlockEntity extends BlockEntity implements Container, MenuProvider {

    public static final int SLOT_GEAR = 0;
    public static final int SLOT_REPAIR_PLATE = 1;
    public static final int SLOT_REPAIR_MAT = 2;
    public static final int RACK_START = 3;
    public static final int RACK_COUNT = 12;
    public static final int TOTAL_SLOTS = RACK_START + RACK_COUNT;

    private NonNullList<ItemStack> items = NonNullList.withSize(TOTAL_SLOTS, ItemStack.EMPTY);

    private final LazyOptional<IItemHandler> rackHandler = LazyOptional.of(() -> new IItemHandlerModifiable() {
        @Override
        public void setStackInSlot(int slot, @NotNull ItemStack stack) {
            if (slot >= 0 && slot < RACK_COUNT) {
                ArmorerBenchBlockEntity.this.setItem(RACK_START + slot, stack);
            }
        }

        @Override
        public int getSlots() {
            return RACK_COUNT;
        }

        @Override
        public @NotNull ItemStack getStackInSlot(int slot) {
            return (slot >= 0 && slot < RACK_COUNT)
                    ? ArmorerBenchBlockEntity.this.getItem(RACK_START + slot)
                    : ItemStack.EMPTY;
        }

        @Override
        public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            if (stack.isEmpty() || slot < 0 || slot >= RACK_COUNT || !isItemValid(slot, stack)) {
                return stack;
            }
            ItemStack cur = getStackInSlot(slot);
            if (!cur.isEmpty()) {
                return stack;
            }
            if (!simulate) {
                ItemStack copy = stack.copy();
                copy.setCount(1);
                setStackInSlot(slot, copy);
            }
            ItemStack rem = stack.copy();
            rem.shrink(1);
            return rem;
        }

        @Override
        public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (amount <= 0 || slot < 0 || slot >= RACK_COUNT) {
                return ItemStack.EMPTY;
            }
            ItemStack cur = getStackInSlot(slot);
            if (cur.isEmpty()) {
                return ItemStack.EMPTY;
            }
            ItemStack out = cur.copy();
            if (!simulate) {
                setStackInSlot(slot, ItemStack.EMPTY);
            }
            return out;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return stack.getItem() instanceof ArmorPlateItem;
        }
    });

    public ArmorerBenchBlockEntity(BlockPos pos, BlockState state) {
        super(SemcsRegistries.ARMORER_BENCH_BE.get(), pos, state);
    }

    public boolean canRepairPlate() {
        ItemStack plate = this.items.get(SLOT_REPAIR_PLATE);
        ItemStack mat = this.items.get(SLOT_REPAIR_MAT);
        if (plate.isEmpty() || !(plate.getItem() instanceof ArmorPlateItem ap) || !plate.isDamaged()) {
            return false;
        }
        return !mat.isEmpty() && ap.isValidRepairItem(plate, mat);
    }

    public boolean repairLoadedPlate() {
        if (!canRepairPlate()) {
            return false;
        }
        ItemStack plate = this.items.get(SLOT_REPAIR_PLATE);
        ItemStack mat = this.items.get(SLOT_REPAIR_MAT);
        int restore = Math.max(50, plate.getMaxDamage() / 2);
        plate.setDamageValue(Math.max(0, plate.getDamageValue() - restore));
        mat.shrink(1);
        if (mat.isEmpty()) {
            this.items.set(SLOT_REPAIR_MAT, ItemStack.EMPTY);
        }
        setChanged();
        if (this.level != null && !this.level.isClientSide) {
            this.level.playSound(null, this.worldPosition,
                    SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.7F, 1.15F);
        }
        return true;
    }

    public boolean hasCompatiblePlate(ItemStack armorStack) {
        if (ArmorPlateItem.maxPlateSlots(armorStack) <= 0) {
            return false;
        }
        for (int i = RACK_START; i < TOTAL_SLOTS; i++) {
            ItemStack s = this.items.get(i);
            if (!s.isEmpty() && ArmorPlateItem.canAcceptPlate(armorStack, s)
                    && (s.getMaxDamage() - s.getDamageValue()) > Math.max(10, s.getMaxDamage() / 5)) {
                return true;
            }
        }
        return false;
    }

    // Extracts the highest-tier (and healthiest) plate compatible with armorStack
    // from the supply rack, optionally storing back an old undamaged/partial plate.
    public ItemStack pullBestCompatiblePlate(ItemStack armorStack) {
        int bestIdx = -1;
        int bestScore = -1;
        for (int i = RACK_START; i < TOTAL_SLOTS; i++) {
            ItemStack s = this.items.get(i);
            if (!s.isEmpty() && ArmorPlateItem.canAcceptPlate(armorStack, s)
                    && s.getItem() instanceof ArmorPlateItem ap) {
                int rem = s.getMaxDamage() - s.getDamageValue();
                if (rem <= Math.max(10, s.getMaxDamage() / 5)) {
                    continue;
                }
                int score = ap.tier() * 10000 + rem;
                if (score > bestScore) {
                    bestScore = score;
                    bestIdx = i;
                }
            }
        }
        if (bestIdx < 0) {
            return ItemStack.EMPTY;
        }
        ItemStack out = this.items.get(bestIdx).copy();
        this.items.set(bestIdx, ItemStack.EMPTY);
        setChanged();
        return out;
    }

    public boolean depositOldPlate(ItemStack oldPlate) {
        if (oldPlate.isEmpty() || !(oldPlate.getItem() instanceof ArmorPlateItem)) {
            return false;
        }
        for (int i = RACK_START; i < TOTAL_SLOTS; i++) {
            if (this.items.get(i).isEmpty()) {
                ItemStack copy = oldPlate.copy();
                copy.setCount(1);
                this.items.set(i, copy);
                setChanged();
                return true;
            }
        }
        return false;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.sem_classsystem.armorer_bench");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory playerInv, Player player) {
        return new ArmorerBenchMenu(id, playerInv, this);
    }

    @Override
    public int getContainerSize() {
        return TOTAL_SLOTS;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack s : this.items) {
            if (!s.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack getItem(int index) {
        return (index >= 0 && index < TOTAL_SLOTS) ? this.items.get(index) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int index, int count) {
        ItemStack res = ContainerHelper.removeItem(this.items, index, count);
        if (!res.isEmpty()) {
            setChanged();
        }
        return res;
    }

    @Override
    public ItemStack removeItemNoUpdate(int index) {
        return ContainerHelper.takeItem(this.items, index);
    }

    @Override
    public void setItem(int index, ItemStack stack) {
        if (index >= 0 && index < TOTAL_SLOTS) {
            this.items.set(index, stack);
            if (stack.getCount() > getMaxStackSize()) {
                stack.setCount(getMaxStackSize());
            }
            setChanged();
        }
    }

    @Override
    public boolean canPlaceItem(int index, ItemStack stack) {
        if (index == SLOT_GEAR) {
            return stack.getItem() instanceof BhqArmorItem && ArmorPlateItem.maxPlateSlots(stack) > 0;
        }
        if (index == SLOT_REPAIR_PLATE || index >= RACK_START) {
            return stack.getItem() instanceof ArmorPlateItem;
        }
        return true;
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        this.items.clear();
        setChanged();
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag) {
        super.saveAdditional(tag);
        ContainerHelper.saveAllItems(tag, this.items);
    }

    @Override
    public void load(@NotNull CompoundTag tag) {
        super.load(tag);
        this.items = NonNullList.withSize(TOTAL_SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, this.items);
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return this.rackHandler.cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        this.rackHandler.invalidate();
    }
}
