package com.poseidon.sem_classsystem.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

// Craftable ballistic trauma plate (Levels 1 to 5) inspired by WARBORN armor slots.
// Can be slotted into any BhqArmorItem vest (2 plate slots: Front + Back) or helmet
// (1 ballistic insert slot) by:
//   - Right-clicking the plate onto a vest/helmet in inventory (or vice-versa)
//   - Right-clicking with the plate in hand while wearing a vest/helmet
// Reduces incoming attack damage and especially TaCZ bullet/projectile damage,
// absorbing impact wear into the plate's own durability.
public class ArmorPlateItem extends Item {

    public static final String NBT_PLATES = "semcs_plates";

    private final int tier;
    private final float generalReduction;
    private final float bulletReduction;
    private final ChatFormatting tierColor;

    public ArmorPlateItem(int tier, int maxDurability, float generalReduction,
                          float bulletReduction, ChatFormatting tierColor) {
        super(new Properties().stacksTo(1).durability(maxDurability));
        this.tier = tier;
        this.generalReduction = generalReduction;
        this.bulletReduction = bulletReduction;
        this.tierColor = tierColor;
    }

    public int tier() {
        return tier;
    }

    public float generalReduction() {
        return generalReduction;
    }

    public float bulletReduction() {
        return bulletReduction;
    }

    public ChatFormatting tierColor() {
        return tierColor;
    }

    @Override
    public boolean isValidRepairItem(ItemStack toRepair, ItemStack repair) {
        return switch (this.tier) {
            case 1 -> repair.is(Items.LEATHER) || repair.is(Items.WHITE_WOOL) || repair.is(Items.STRING);
            case 2 -> repair.is(Items.IRON_INGOT);
            case 3 -> repair.is(Items.QUARTZ) || repair.is(Items.IRON_INGOT);
            case 4 -> repair.is(Items.DIAMOND);
            case 5 -> repair.is(Items.NETHERITE_SCRAP) || repair.is(Items.NETHERITE_INGOT) || repair.is(Items.DIAMOND);
            default -> super.isValidRepairItem(toRepair, repair);
        };
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return switch (this.tier) {
            case 1 -> 0x8BA870;
            case 2 -> 0xA8B8C8;
            case 3 -> 0x4BC3EB;
            case 4 -> 0xEBB437;
            case 5 -> 0xEB414B;
            default -> super.getBarColor(stack);
        };
    }

    // Right-click in hand -> quick-insert into worn Chest vest (or Helmet if vest is full)
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (maxPlateSlots(chest) > 0 && canAcceptPlate(chest, held)) {
            ItemStack single = held.copy();
            single.setCount(1);
            ItemStack swapped = insertOrReplacePlate(chest, single);
            if (swapped == null) {
                // Inserted into an empty slot
                if (!level.isClientSide) {
                    held.shrink(1);
                    player.playSound(SoundEvents.ARMOR_EQUIP_IRON, 1.0F, 1.15F);
                    player.displayClientMessage(Component.translatable(
                            "message.sem_classsystem.plate_installed",
                            single.getHoverName(), chest.getHoverName())
                            .withStyle(ChatFormatting.GREEN), true);
                }
                return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
            } else if (!swapped.isEmpty()) {
                // Swapped with an existing damaged/lower-tier plate
                if (!level.isClientSide) {
                    player.setItemInHand(hand, swapped);
                    player.playSound(SoundEvents.ARMOR_EQUIP_IRON, 1.0F, 1.05F);
                    player.displayClientMessage(Component.translatable(
                            "message.sem_classsystem.plate_swapped",
                            single.getHoverName(), chest.getHoverName())
                            .withStyle(ChatFormatting.AQUA), true);
                }
                return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
            }
        }

        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (maxPlateSlots(head) > 0 && canAcceptPlate(head, held)) {
            ItemStack single = held.copy();
            single.setCount(1);
            ItemStack swapped = insertOrReplacePlate(head, single);
            if (swapped == null) {
                if (!level.isClientSide) {
                    held.shrink(1);
                    player.playSound(SoundEvents.ARMOR_EQUIP_IRON, 1.0F, 1.2F);
                    player.displayClientMessage(Component.translatable(
                            "message.sem_classsystem.plate_installed",
                            single.getHoverName(), head.getHoverName())
                            .withStyle(ChatFormatting.GREEN), true);
                }
                return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
            } else if (!swapped.isEmpty()) {
                if (!level.isClientSide) {
                    player.setItemInHand(hand, swapped);
                    player.playSound(SoundEvents.ARMOR_EQUIP_IRON, 1.0F, 1.1F);
                    player.displayClientMessage(Component.translatable(
                            "message.sem_classsystem.plate_swapped",
                            single.getHoverName(), head.getHoverName())
                            .withStyle(ChatFormatting.AQUA), true);
                }
                return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
            }
        }

        return InteractionResultHolder.pass(held);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        int left = stack.getMaxDamage() - stack.getDamageValue();
        int bulletPct = Math.round(this.bulletReduction * 100.0F);
        int genPct = Math.round(this.generalReduction * 100.0F);

        tooltip.add(Component.translatable("item.sem_classsystem.armor_plate.tier",
                        this.tier, bulletPct, genPct)
                .withStyle(this.tierColor));
        tooltip.add(Component.translatable("item.sem_classsystem.armor_plate.durability",
                        left, stack.getMaxDamage())
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.sem_classsystem.armor_plate.usage")
                .withStyle(ChatFormatting.DARK_GRAY));
        super.appendHoverText(stack, level, tooltip, flag);
    }

    // ---- Static Plate Slot Helpers for BhqArmorItem & Combat Events ----------

    // Vests hold 2 plates (Front Strike + Back Trauma); Helmets hold 1 ballistic insert.
    public static int maxPlateSlots(ItemStack armorStack) {
        if (armorStack.isEmpty() || !(armorStack.getItem() instanceof BhqArmorItem bhq)) {
            return 0;
        }
        if (bhq.getType() == ArmorItem.Type.CHESTPLATE) {
            return 2;
        }
        if (bhq.getType() == ArmorItem.Type.HELMET) {
            return 1;
        }
        return 0;
    }

    // Soft caps/berets/boonies only accept up to Level 3 soft/medium inserts;
    // hard ballistic helmets and all vests accept Levels 1 through 5.
    public static int maxAllowedTier(ItemStack armorStack) {
        if (!(armorStack.getItem() instanceof BhqArmorItem bhq)) {
            return 0;
        }
        if (bhq.getType() == ArmorItem.Type.HELMET) {
            String p = bhq.piece();
            if ("bhq_cap".equals(p) || "sf911_beret".equals(p) || "camper_helmet".equals(p)) {
                return 3;
            }
        }
        return 5;
    }

    public static boolean canAcceptPlate(ItemStack armorStack, ItemStack plateStack) {
        if (maxPlateSlots(armorStack) <= 0 || !(plateStack.getItem() instanceof ArmorPlateItem plate)) {
            return false;
        }
        return plate.tier() <= maxAllowedTier(armorStack);
    }

    // Zero-allocation check for whether an armor stack currently holds at least one plate.
    public static boolean hasPlates(ItemStack armorStack) {
        if (maxPlateSlots(armorStack) <= 0 || !armorStack.hasTag()) {
            return false;
        }
        CompoundTag tag = armorStack.getTag();
        return tag != null
                && tag.contains(NBT_PLATES, Tag.TAG_LIST)
                && !tag.getList(NBT_PLATES, Tag.TAG_COMPOUND).isEmpty();
    }

    public static List<ItemStack> getPlates(ItemStack armorStack) {
        int max = maxPlateSlots(armorStack);
        List<ItemStack> out = new ArrayList<>(max);
        for (int i = 0; i < max; i++) {
            out.add(ItemStack.EMPTY);
        }
        if (max <= 0 || !armorStack.hasTag()) {
            return out;
        }
        CompoundTag tag = armorStack.getTag();
        if (tag == null || !tag.contains(NBT_PLATES, Tag.TAG_LIST)) {
            return out;
        }
        ListTag list = tag.getList(NBT_PLATES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int slot = entry.contains("Slot", Tag.TAG_INT) ? entry.getInt("Slot") : i;
            if (slot >= 0 && slot < max) {
                ItemStack loaded = ItemStack.of(entry);
                if (!loaded.isEmpty() && loaded.getItem() instanceof ArmorPlateItem) {
                    out.set(slot, loaded);
                }
            }
        }
        return out;
    }

    public static void setPlates(ItemStack armorStack, List<ItemStack> plates) {
        int max = maxPlateSlots(armorStack);
        if (max <= 0) {
            return;
        }
        ListTag list = new ListTag();
        for (int i = 0; i < Math.min(max, plates.size()); i++) {
            ItemStack s = plates.get(i);
            if (s != null && !s.isEmpty() && s.getItem() instanceof ArmorPlateItem) {
                CompoundTag entry = new CompoundTag();
                s.save(entry);
                entry.putInt("Slot", i);
                list.add(entry);
            }
        }
        if (list.isEmpty()) {
            if (armorStack.hasTag() && armorStack.getTag() != null) {
                armorStack.getTag().remove(NBT_PLATES);
            }
        } else {
            armorStack.getOrCreateTag().put(NBT_PLATES, list);
        }
    }

    // Inserts a plate into the first empty slot (returns null), or swaps with
    // the weakest/most-damaged plate if all slots are full (returns the old plate).
    // Returns ItemStack.EMPTY if the plate cannot be installed.
    @Nullable
    public static ItemStack insertOrReplacePlate(ItemStack armorStack, ItemStack newPlate) {
        if (!canAcceptPlate(armorStack, newPlate)) {
            return ItemStack.EMPTY;
        }
        List<ItemStack> plates = getPlates(armorStack);
        for (int i = 0; i < plates.size(); i++) {
            if (plates.get(i).isEmpty()) {
                ItemStack copy = newPlate.copy();
                copy.setCount(1);
                plates.set(i, copy);
                setPlates(armorStack, plates);
                return null;
            }
        }
        // All slots occupied: replace the lowest-tier or most-damaged plate
        int replaceIdx = 0;
        int worstScore = Integer.MAX_VALUE;
        for (int i = 0; i < plates.size(); i++) {
            ItemStack cur = plates.get(i);
            int t = cur.getItem() instanceof ArmorPlateItem ap ? ap.tier() : 0;
            int rem = cur.getMaxDamage() - cur.getDamageValue();
            int score = t * 10000 + rem;
            if (score < worstScore) {
                worstScore = score;
                replaceIdx = i;
            }
        }
        ItemStack old = plates.get(replaceIdx).copy();
        ItemStack copy = newPlate.copy();
        copy.setCount(1);
        plates.set(replaceIdx, copy);
        setPlates(armorStack, plates);
        return old;
    }

    // Extracts and returns the last installed plate from armorStack, or ItemStack.EMPTY if none.
    public static ItemStack extractLastPlate(ItemStack armorStack) {
        List<ItemStack> plates = getPlates(armorStack);
        for (int i = plates.size() - 1; i >= 0; i--) {
            ItemStack cur = plates.get(i);
            if (!cur.isEmpty()) {
                plates.set(i, ItemStack.EMPTY);
                setPlates(armorStack, plates);
                return cur;
            }
        }
        return ItemStack.EMPTY;
    }

    // Capability provider so BhqArmorItem exposes its plate slots via ForgeCapabilities.ITEM_HANDLER
    public static ICapabilityProvider createCapabilityProvider(ItemStack armorStack) {
        return new ICapabilityProvider() {
            private final LazyOptional<IItemHandlerModifiable> opt = LazyOptional.of(() -> new IItemHandlerModifiable() {
                @Override
                public void setStackInSlot(int slot, @NotNull ItemStack stack) {
                    List<ItemStack> plates = getPlates(armorStack);
                    if (slot >= 0 && slot < plates.size()) {
                        plates.set(slot, stack.copy());
                        setPlates(armorStack, plates);
                    }
                }

                @Override
                public int getSlots() {
                    return maxPlateSlots(armorStack);
                }

                @Override
                public @NotNull ItemStack getStackInSlot(int slot) {
                    List<ItemStack> plates = getPlates(armorStack);
                    return (slot >= 0 && slot < plates.size()) ? plates.get(slot) : ItemStack.EMPTY;
                }

                @Override
                public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
                    if (stack.isEmpty() || !canAcceptPlate(armorStack, stack)) {
                        return stack;
                    }
                    List<ItemStack> plates = getPlates(armorStack);
                    if (slot < 0 || slot >= plates.size() || !plates.get(slot).isEmpty()) {
                        return stack;
                    }
                    if (!simulate) {
                        ItemStack single = stack.copy();
                        single.setCount(1);
                        plates.set(slot, single);
                        setPlates(armorStack, plates);
                    }
                    ItemStack rem = stack.copy();
                    rem.shrink(1);
                    return rem;
                }

                @Override
                public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
                    if (amount <= 0) {
                        return ItemStack.EMPTY;
                    }
                    List<ItemStack> plates = getPlates(armorStack);
                    if (slot < 0 || slot >= plates.size() || plates.get(slot).isEmpty()) {
                        return ItemStack.EMPTY;
                    }
                    ItemStack existing = plates.get(slot).copy();
                    if (!simulate) {
                        plates.set(slot, ItemStack.EMPTY);
                        setPlates(armorStack, plates);
                    }
                    return existing;
                }

                @Override
                public int getSlotLimit(int slot) {
                    return 1;
                }

                @Override
                public boolean isItemValid(int slot, @NotNull ItemStack stack) {
                    return canAcceptPlate(armorStack, stack);
                }
            });

            @Override
            public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
                if (cap == ForgeCapabilities.ITEM_HANDLER && maxPlateSlots(armorStack) > 0) {
                    return opt.cast();
                }
                return LazyOptional.empty();
            }
        };
    }
}
