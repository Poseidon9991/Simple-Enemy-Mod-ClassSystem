package com.poseidon.sem_classsystem.menu;

import com.poseidon.sem_classsystem.event.ArmorPlateCombatEvents;
import com.poseidon.sem_classsystem.item.ArmorPlateItem;
import com.poseidon.sem_classsystem.item.BhqArmorItem;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.network.NetworkHooks;

import java.util.ArrayList;
import java.util.List;

// Container menu for inspecting, inserting, and removing ballistic armor plates
// from tactical vests (Front + Back slots) and helmets (Ballistic insert slot),
// functioning just like Warborn plate carrier inventories.
public class ArmorPlateMenu extends AbstractContainerMenu {

    public enum SourceType {
        HAND,
        WORN,
        ENTITY;

        static final SourceType[] VALUES = values();
    }

    private static final EquipmentSlot[] EQUIPMENT_SLOTS = EquipmentSlot.values();

    private final ItemStack armorStack;
    private final SourceType sourceType;
    private final int sourceIndex;
    private final int entityId;
    private final int maxPlateSlots;
    private final SimpleContainer plateContainer;

    private static class DecodedData {
        final SourceType type;
        final int index;
        final int entityId;
        final ItemStack stack;

        DecodedData(Inventory playerInv, FriendlyByteBuf buf) {
            int ord = buf.readByte();
            this.type = (ord >= 0 && ord < SourceType.VALUES.length) ? SourceType.VALUES[ord] : SourceType.HAND;
            this.index = buf.readInt();
            this.entityId = buf.readInt();
            this.stack = resolveClientStack(playerInv, this.type, this.index, this.entityId);
        }
    }

    // Client-side constructor invoked by Forge network decoder
    public ArmorPlateMenu(int containerId, Inventory playerInv, FriendlyByteBuf buf) {
        this(containerId, playerInv, new DecodedData(playerInv, buf));
    }

    private ArmorPlateMenu(int containerId, Inventory playerInv, DecodedData data) {
        this(containerId, playerInv, data.stack, data.type, data.index, data.entityId);
    }

    // Server-side / master constructor
    public ArmorPlateMenu(int containerId, Inventory playerInv, ItemStack armorStack,
                          SourceType sourceType, int sourceIndex, int entityId) {
        super(SemcsRegistries.ARMOR_PLATE_MENU.get(), containerId);
        this.armorStack = armorStack;
        this.sourceType = sourceType;
        this.sourceIndex = sourceIndex;
        this.entityId = entityId;

        this.maxPlateSlots = ArmorPlateItem.maxPlateSlots(armorStack);
        int slotCount = Math.max(1, this.maxPlateSlots);
        this.plateContainer = new SimpleContainer(slotCount);

        List<ItemStack> initialPlates = ArmorPlateItem.getPlates(armorStack);
        for (int i = 0; i < this.maxPlateSlots && i < initialPlates.size(); i++) {
            this.plateContainer.setItem(i, initialPlates.get(i).copy());
        }

        // On slot changes, write directly back to armorStack NBT and sync unit handler if applicable
        this.plateContainer.addListener(c -> {
            if (this.maxPlateSlots > 0 && !this.armorStack.isEmpty()) {
                List<ItemStack> updated = new ArrayList<>(this.maxPlateSlots);
                for (int i = 0; i < this.maxPlateSlots; i++) {
                    updated.add(this.plateContainer.getItem(i));
                }
                ArmorPlateItem.setPlates(this.armorStack, updated);
                if (this.sourceType == SourceType.ENTITY && !playerInv.player.level().isClientSide) {
                    Entity ent = playerInv.player.level().getEntity(this.entityId);
                    if (ent instanceof LivingEntity living) {
                        int handlerSlot = (this.sourceIndex == EquipmentSlot.HEAD.ordinal()) ? 5 : 4;
                        ArmorPlateCombatEvents.syncUnitArmorSlot(living, handlerSlot, this.armorStack);
                    }
                }
            }
        });

        // 1. Plate Slots (Vests: Front at x=62, Back at x=98; Helmets: Insert at x=80)
        if (this.maxPlateSlots == 1) {
            this.addSlot(new PlateSlot(this.plateContainer, 0, 80, 30, this.armorStack, 0));
        } else if (this.maxPlateSlots >= 2) {
            this.addSlot(new PlateSlot(this.plateContainer, 0, 62, 30, this.armorStack, 0));
            this.addSlot(new PlateSlot(this.plateContainer, 1, 98, 30, this.armorStack, 1));
        }

        // 2. Player Inventory (3 rows of 9 slots at y=84)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }

        // 3. Player Hotbar (1 row of 9 slots at y=142)
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, 8 + col * 18, 142));
        }
    }

    private static ItemStack resolveClientStack(Inventory playerInv, SourceType type, int index, int entityId) {
        if (type == SourceType.HAND) {
            return playerInv.player.getItemInHand(index == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
        } else if (type == SourceType.WORN) {
            if (index >= 0 && index < EQUIPMENT_SLOTS.length) {
                return playerInv.player.getItemBySlot(EQUIPMENT_SLOTS[index]);
            }
        } else if (type == SourceType.ENTITY) {
            Entity ent = playerInv.player.level().getEntity(entityId);
            if (ent instanceof LivingEntity living) {
                if (index >= 0 && index < EQUIPMENT_SLOTS.length) {
                    return living.getItemBySlot(EQUIPMENT_SLOTS[index]);
                }
                return living.getItemBySlot(EquipmentSlot.CHEST);
            }
        }
        return ItemStack.EMPTY;
    }

    public static void openForHand(ServerPlayer player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof BhqArmorItem) || ArmorPlateItem.maxPlateSlots(stack) <= 0) {
            return;
        }
        int handIdx = hand == InteractionHand.MAIN_HAND ? 0 : 1;
        NetworkHooks.openScreen(player, new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return stack.getHoverName();
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return new ArmorPlateMenu(id, inv, stack, SourceType.HAND, handIdx, -1);
            }
        }, buf -> {
            buf.writeByte(SourceType.HAND.ordinal());
            buf.writeInt(handIdx);
            buf.writeInt(-1);
        });
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ARMOR_EQUIP_GENERIC, SoundSource.PLAYERS, 0.9F, 1.1F);
    }

    public static void openForWorn(ServerPlayer player, EquipmentSlot slot) {
        ItemStack stack = player.getItemBySlot(slot);
        if (!(stack.getItem() instanceof BhqArmorItem) || ArmorPlateItem.maxPlateSlots(stack) <= 0) {
            return;
        }
        NetworkHooks.openScreen(player, new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return stack.getHoverName();
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return new ArmorPlateMenu(id, inv, stack, SourceType.WORN, slot.ordinal(), -1);
            }
        }, buf -> {
            buf.writeByte(SourceType.WORN.ordinal());
            buf.writeInt(slot.ordinal());
            buf.writeInt(-1);
        });
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ARMOR_EQUIP_GENERIC, SoundSource.PLAYERS, 0.9F, 1.1F);
    }

    public static void openForEntity(ServerPlayer player, LivingEntity target, EquipmentSlot slot) {
        ItemStack stack = target.getItemBySlot(slot);
        if (!(stack.getItem() instanceof BhqArmorItem) || ArmorPlateItem.maxPlateSlots(stack) <= 0) {
            return;
        }
        NetworkHooks.openScreen(player, new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.translatable("container.sem_classsystem.unit_vest", target.getDisplayName(), stack.getHoverName());
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return new ArmorPlateMenu(id, inv, stack, SourceType.ENTITY, slot.ordinal(), target.getId());
            }
        }, buf -> {
            buf.writeByte(SourceType.ENTITY.ordinal());
            buf.writeInt(slot.ordinal());
            buf.writeInt(target.getId());
        });
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ARMOR_EQUIP_GENERIC, SoundSource.PLAYERS, 0.9F, 1.1F);
    }

    public ItemStack getArmorStack() {
        return this.armorStack;
    }

    public int getMaxPlateSlots() {
        return this.maxPlateSlots;
    }

    public boolean isVest() {
        return this.armorStack.getItem() instanceof BhqArmorItem bhq && bhq.getType() == ArmorItem.Type.CHESTPLATE;
    }

    @Override
    public boolean stillValid(Player player) {
        if (!player.isAlive() || this.armorStack.isEmpty()) {
            return false;
        }
        if (this.sourceType == SourceType.ENTITY) {
            Entity ent = player.level().getEntity(this.entityId);
            return ent != null && ent.isAlive() && ent.distanceTo(player) <= 8.0F;
        }
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.slots.get(slotIndex);
        if (slot != null && slot.hasItem()) {
            ItemStack stackInSlot = slot.getItem();
            itemstack = stackInSlot.copy();

            // Plate slot -> player inventory
            if (slotIndex < this.maxPlateSlots) {
                if (!this.moveItemStackTo(stackInSlot, this.maxPlateSlots, this.maxPlateSlots + 36, true)) {
                    return ItemStack.EMPTY;
                }
                slot.onQuickCraft(stackInSlot, itemstack);
            } else {
                // Player inventory -> plate slots (only if it's a valid ArmorPlateItem)
                if (stackInSlot.getItem() instanceof ArmorPlateItem
                        && ArmorPlateItem.canAcceptPlate(this.armorStack, stackInSlot)) {
                    if (!this.moveItemStackTo(stackInSlot, 0, this.maxPlateSlots, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (slotIndex < this.maxPlateSlots + 27) {
                    // Move from inventory to hotbar
                    if (!this.moveItemStackTo(stackInSlot, this.maxPlateSlots + 27, this.maxPlateSlots + 36, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (!this.moveItemStackTo(stackInSlot, this.maxPlateSlots, this.maxPlateSlots + 27, false)) {
                    // Move from hotbar to inventory
                    return ItemStack.EMPTY;
                }
            }

            if (stackInSlot.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }

            if (stackInSlot.getCount() == itemstack.getCount()) {
                return ItemStack.EMPTY;
            }

            slot.onTake(player, stackInSlot);
        }
        return itemstack;
    }

    public static class PlateSlot extends Slot {
        private final ItemStack armorStack;
        private final int plateIndex;

        public PlateSlot(Container container, int slotIndex, int x, int y,
                         ItemStack armorStack, int plateIndex) {
            super(container, slotIndex, x, y);
            this.armorStack = armorStack;
            this.plateIndex = plateIndex;
        }

        public int getPlateIndex() {
            return this.plateIndex;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return ArmorPlateItem.canAcceptPlate(this.armorStack, stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public void setByPlayer(ItemStack stack) {
            super.setByPlayer(stack);
        }
    }
}
