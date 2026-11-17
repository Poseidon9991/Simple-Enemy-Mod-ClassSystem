package com.poseidon.sem_classsystem.block;

import com.poseidon.sem_classsystem.event.UnitMarkerTracker;
import com.poseidon.sem_classsystem.menu.MedicalStationMenu;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// Field Medical Station storage (18 slots):
// Stores wool, golden apples, medical items, and IFAK Pouches.
// Automatically recharges damaged IFAK Pouches stored inside using wool/golden apples,
// and supplies post-fight recovery + medic charge restocking for owned units.
public class MedicalStationBlockEntity extends BlockEntity implements Container, MenuProvider {

    public static final int SLOTS = 18;
    private static final int RECHARGE_INTERVAL = 40;

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    private final LazyOptional<IItemHandler> handler = LazyOptional.of(() -> new InvWrapper(this));

    public MedicalStationBlockEntity(BlockPos pos, BlockState state) {
        super(SemcsRegistries.MEDICAL_STATION_BE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, MedicalStationBlockEntity station) {
        if (level.isClientSide || level.getGameTime() % RECHARGE_INTERVAL != 0) {
            return;
        }
        station.tickIfakRecharge();
    }

    private void tickIfakRecharge() {
        int damagedIfakIdx = -1;
        for (int i = 0; i < SLOTS; i++) {
            ItemStack s = this.items.get(i);
            if (!s.isEmpty() && s.is(SemcsRegistries.IFAK_POUCH.get()) && s.isDamaged()) {
                damagedIfakIdx = i;
                break;
            }
        }
        if (damagedIfakIdx < 0) {
            return;
        }
        if (consumeRechargeMaterial()) {
            ItemStack pouch = this.items.get(damagedIfakIdx);
            pouch.setDamageValue(Math.max(0, pouch.getDamageValue() - 4));
            setChanged();
        }
    }

    public boolean rechargeHeldIfak(ItemStack heldIfak) {
        if (!heldIfak.is(SemcsRegistries.IFAK_POUCH.get()) || !heldIfak.isDamaged()) {
            return false;
        }
        if (!consumeRechargeMaterial()) {
            return false;
        }
        heldIfak.setDamageValue(Math.max(0, heldIfak.getDamageValue() - 4));
        return true;
    }

    public boolean hasMedicalSupply() {
        for (int i = 0; i < SLOTS; i++) {
            ItemStack s = this.items.get(i);
            if (!s.isEmpty() && isStationSupply(s)) {
                return true;
            }
        }
        return false;
    }

    public boolean consumeMedicalSupply() {
        for (int i = 0; i < SLOTS; i++) {
            ItemStack s = this.items.get(i);
            if (!s.isEmpty() && isStationSupply(s)) {
                if (s.is(SemcsRegistries.IFAK_POUCH.get())) {
                    if (s.getDamageValue() < s.getMaxDamage() - 1) {
                        s.setDamageValue(s.getDamageValue() + 1);
                        setChanged();
                        return true;
                    }
                    continue;
                }
                s.shrink(1);
                if (s.isEmpty()) {
                    this.items.set(i, ItemStack.EMPTY);
                }
                setChanged();
                return true;
            }
        }
        return false;
    }

    private boolean consumeRechargeMaterial() {
        for (int i = 0; i < SLOTS; i++) {
            ItemStack s = this.items.get(i);
            if (!s.isEmpty() && isRechargeMaterial(s)) {
                s.shrink(1);
                if (s.isEmpty()) {
                    this.items.set(i, ItemStack.EMPTY);
                }
                setChanged();
                return true;
            }
        }
        return false;
    }

    public static boolean isRechargeMaterial(ItemStack stack) {
        return stack.is(ItemTags.WOOL) || stack.is(Items.GOLDEN_APPLE);
    }

    public static boolean isStationSupply(ItemStack stack) {
        return isRechargeMaterial(stack)
                || UnitMarkerTracker.isMedicalItem(stack)
                || (stack.is(SemcsRegistries.IFAK_POUCH.get())
                        && stack.getDamageValue() < stack.getMaxDamage() - 1);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.sem_classsystem.medical_station");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory playerInv, Player player) {
        return new MedicalStationMenu(id, playerInv, this);
    }

    @Override
    public int getContainerSize() {
        return SLOTS;
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
        return (index >= 0 && index < SLOTS) ? this.items.get(index) : ItemStack.EMPTY;
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
        if (index >= 0 && index < SLOTS) {
            this.items.set(index, stack);
            if (stack.getCount() > getMaxStackSize()) {
                stack.setCount(getMaxStackSize());
            }
            setChanged();
        }
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
        this.items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, this.items);
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return this.handler.cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        this.handler.invalidate();
    }
}
