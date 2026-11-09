package com.poseidon.sem_classsystem.item;

import com.poseidon.sem_classsystem.client.BhqArmorModel;
import com.poseidon.sem_classsystem.client.SemcsArmorItemRenderer;
import com.poseidon.sem_classsystem.menu.ArmorPlateMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.renderer.GeoArmorRenderer;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;
import java.util.function.Consumer;

// One GeckoLib armor item per BHQ/faction piece. Following the ZeroContact pattern:
// the item is a GeoItem, IClientItemExtensions hands back a
// GeoArmorRenderer (which is itself a HumanoidModel), and each piece owns
// its own .geo.json so SEM's armor layers can pose it like any other
// humanoid armor on both players and units.
//
// Chestplates (vests) and Helmets also feature WARBORN-style ballistic plate
// slots (2 slots on vests, 1 insert slot on helmets) accepting Level 1-5
// ArmorPlateItem inserts via right-click in inventory or in hand.
public class BhqArmorItem extends ArmorItem implements GeoItem {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final String piece;

    public BhqArmorItem(String piece, ArmorItem.Type type) {
        super(materialFor(piece), type, new Properties().stacksTo(1));
        this.piece = piece;
    }

    private static ArmorMaterial materialFor(String piece) {
        return piece.startsWith("bhq_") ? BhqArmorMaterial.INSTANCE : SemcsArmorMaterial.forPiece(piece);
    }

    public String piece() {
        return piece;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        int maxSlots = ArmorPlateItem.maxPlateSlots(held);
        if (maxSlots > 0 && player.isShiftKeyDown()) {
            if (!level.isClientSide && player instanceof ServerPlayer sp) {
                ArmorPlateMenu.openForHand(sp, hand);
            }
            return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
        }
        return super.use(level, player, hand);
    }

    @Override
    public @Nullable ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundTag nbt) {
        if (this.type == Type.CHESTPLATE || this.type == Type.HELMET) {
            return ArmorPlateItem.createCapabilityProvider(stack);
        }
        return super.initCapabilities(stack, nbt);
    }

    // Inventory interaction 1: Holding an ArmorPlateItem on the cursor and right-clicking
    // onto this vest/helmet inserts/swaps the plate; right-clicking with an EMPTY cursor
    // while sneaking (or right-clicking in inventory) extracts the last installed plate.
    @Override
    public boolean overrideOtherStackedOnMe(ItemStack armorStack, ItemStack cursorStack,
                                            Slot slot, ClickAction action,
                                            Player player, SlotAccess cursorAccess) {
        if (action != ClickAction.SECONDARY || !slot.allowModification(player)) {
            return false;
        }
        int maxSlots = ArmorPlateItem.maxPlateSlots(armorStack);
        if (maxSlots <= 0) {
            return false;
        }
        if (cursorStack.isEmpty()) {
            ItemStack extracted = ArmorPlateItem.extractLastPlate(armorStack);
            if (!extracted.isEmpty()) {
                cursorAccess.set(extracted);
                player.playSound(SoundEvents.ARMOR_EQUIP_GENERIC, 0.9F, 0.95F);
                return true;
            }
            return false;
        }
        if (ArmorPlateItem.canAcceptPlate(armorStack, cursorStack)) {
            ItemStack single = cursorStack.copy();
            single.setCount(1);
            ItemStack swapped = ArmorPlateItem.insertOrReplacePlate(armorStack, single);
            if (swapped == null) {
                cursorStack.shrink(1);
                player.playSound(SoundEvents.ARMOR_EQUIP_IRON, 0.9F, 1.15F);
                return true;
            } else if (!swapped.isEmpty() && cursorStack.getCount() == 1) {
                cursorAccess.set(swapped);
                player.playSound(SoundEvents.ARMOR_EQUIP_IRON, 0.9F, 1.05F);
                return true;
            }
        }
        return false;
    }

    // Inventory interaction 2: Holding this vest/helmet on the cursor and right-clicking
    // onto an ArmorPlateItem in a slot inserts the plate directly into the armor.
    @Override
    public boolean overrideStackedOnOther(ItemStack armorStack, Slot slot,
                                          ClickAction action, Player player) {
        if (action != ClickAction.SECONDARY || !slot.allowModification(player)) {
            return false;
        }
        if (ArmorPlateItem.maxPlateSlots(armorStack) <= 0) {
            return false;
        }
        ItemStack slotStack = slot.getItem();
        if (!slotStack.isEmpty() && ArmorPlateItem.canAcceptPlate(armorStack, slotStack)) {
            ItemStack single = slotStack.copy();
            single.setCount(1);
            ItemStack swapped = ArmorPlateItem.insertOrReplacePlate(armorStack, single);
            if (swapped == null) {
                slotStack.shrink(1);
                player.playSound(SoundEvents.ARMOR_EQUIP_IRON, 0.9F, 1.15F);
                return true;
            } else if (!swapped.isEmpty() && slotStack.getCount() == 1) {
                slot.set(swapped);
                player.playSound(SoundEvents.ARMOR_EQUIP_IRON, 0.9F, 1.05F);
                return true;
            }
        }
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        int maxSlots = ArmorPlateItem.maxPlateSlots(stack);
        if (maxSlots > 0) {
            List<ItemStack> plates = ArmorPlateItem.getPlates(stack);
            int filled = 0;
            for (ItemStack p : plates) {
                if (!p.isEmpty()) filled++;
            }
            tooltip.add(Component.translatable("item.sem_classsystem.armor.plate_header",
                            filled, maxSlots, ArmorPlateItem.maxAllowedTier(stack))
                    .withStyle(ChatFormatting.GOLD));
            for (int i = 0; i < maxSlots; i++) {
                ItemStack p = plates.get(i);
                if (!p.isEmpty() && p.getItem() instanceof ArmorPlateItem ap) {
                    int rem = p.getMaxDamage() - p.getDamageValue();
                    int max = p.getMaxDamage();
                    int bul = Math.round(ap.bulletReduction() * 100.0F);
                    int gen = Math.round(ap.generalReduction() * 100.0F);
                    tooltip.add(Component.literal("  [" + (i + 1) + "] ")
                            .withStyle(ChatFormatting.DARK_GRAY)
                            .append(Component.translatable("item.sem_classsystem.armor.plate_slot_filled",
                                            ap.tier(), rem, max, bul, gen)
                                    .withStyle(ap.tierColor())));
                } else {
                    tooltip.add(Component.literal("  [" + (i + 1) + "] ")
                            .withStyle(ChatFormatting.DARK_GRAY)
                            .append(Component.translatable("item.sem_classsystem.armor.plate_slot_empty")
                                    .withStyle(ChatFormatting.GRAY)));
                }
            }
            tooltip.add(Component.translatable("item.sem_classsystem.armor.plate_hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        super.appendHoverText(stack, level, tooltip, flag);
    }

    @Override
    public void initializeClient(@NotNull Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private GeoArmorRenderer<BhqArmorItem> renderer;
            private BlockEntityWithoutLevelRenderer itemRenderer;

            @Override
            public @NotNull BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (this.itemRenderer == null) {
                    this.itemRenderer = new SemcsArmorItemRenderer();
                }
                return this.itemRenderer;
            }

            @Override
            public @NotNull HumanoidModel<?> getHumanoidArmorModel(LivingEntity entity,
                                                                 ItemStack stack,
                                                                 EquipmentSlot slot,
                                                                 HumanoidModel<?> base) {
                if (this.renderer == null) {
                    this.renderer = new GeoArmorRenderer<>(new BhqArmorModel()) {
                        @Override
                        protected void applyBoneVisibilityBySlot(EquipmentSlot currentSlot) {
                            super.applyBoneVisibilityBySlot(currentSlot);
                            // Vanilla HumanoidArmorLayer shows body + both legs for LEGS
                            // (waist belt, buckle, hip pouches, groin/tasset plates live
                            // on armorBody in our .geo.json files), whereas GeckoLib's
                            // default LEGS switch only unhides rightLeg + leftLeg.
                            if (currentSlot == EquipmentSlot.LEGS) {
                                setBoneVisible(this.body, this.baseModel.body.visible);
                            }
                        }
                    };
                }
                // Required on the vanilla render path (ZeroContact does the
                // same): populates entity/stack/slot or renderToBuffer NPEs.
                this.renderer.prepForRender(entity, stack, slot, base);
                return this.renderer;
            }
        });
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }
}
