package com.poseidon.sem_classsystem.entity.ai;

import com.mojang.datafixers.util.Pair;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.EnumSet;
import java.util.Locale;
import java.util.function.Supplier;

// Allows SEM units to eat food (vanilla or modded) and use bandages/medkits/potions
// from their inventory, nested bags/pouches, Curios slots, or field-ration reserves.
// Does NOT lock Flag.MOVE so units can walk into cover or retreat while eating/bandaging.
public class UnitSelfHealGoal extends Goal {

    private static final int USE_DURATION_TICKS = 32;
    private static final int COOLDOWN_TICKS = 50;

    private static final String[] HEAVY_MED_KEYWORDS = {
            "medkit", "med_kit", "first_aid", "firstaid", "ifak", "afak",
            "medical_kit", "medicalkit", "surg", "defib", "syringe", "stim",
            "adrenaline", "morphine", "blood_bag", "bloodbag", "grizzly", "salewa"
    };

    private static final String[] LIGHT_MED_KEYWORDS = {
            "bandage", "plaster", "gauze", "dressing", "salve", "ointment",
            "pill", "painkiller", "ibuprofen", "analgin", "splint", "tourniquet",
            "esmarch", "cat_tourniquet", "hemostat", "calok", "ai2", "ai_2", "cheese_med",
            "heal", "ration", "mre", "canteen", "water_bottle"
    };

    private final PathfinderMob mob;
    private int useTicks = 0;
    private int cooldown = 0;
    private ItemStack activeVisualStack = ItemStack.EMPTY;
    private Supplier<ItemStack> consumeAction = null;
    private boolean eatingAlwaysEdibleBuff = false;

    public UnitSelfHealGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    public static boolean isAlwaysEdibleBuffItem(ItemStack stack, LivingEntity entity) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
            return true;
        }
        FoodProperties food = stack.getFoodProperties(entity);
        if (food != null && food.canAlwaysEat() && !food.getEffects().isEmpty()) {
            return true;
        }
        return false;
    }

    public static boolean isHealOrFoodItem(ItemStack stack, LivingEntity entity) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof AbstractGunItem
                || IGun.getIGunOrNull(stack) != null
                || IAmmo.getIAmmoOrNull(stack) != null
                || stack.getItem() instanceof ArmorItem
                || UnitMeleeAttackGoal.isMeleeWeapon(stack)) {
            return false;
        }
        if (stack.isEdible() || stack.getFoodProperties(entity) != null) {
            return true;
        }
        if (stack.getItem() instanceof PotionItem) {
            for (MobEffectInstance eff : PotionUtils.getMobEffects(stack)) {
                if (eff.getEffect() == MobEffects.HEAL
                        || eff.getEffect() == MobEffects.REGENERATION
                        || eff.getEffect() == MobEffects.ABSORPTION
                        || eff.getEffect() == MobEffects.DAMAGE_RESISTANCE
                        || eff.getEffect() == MobEffects.FIRE_RESISTANCE) {
                    return true;
                }
            }
        }
        String id = itemIdString(stack);
        for (String kw : HEAVY_MED_KEYWORDS) {
            if (id.contains(kw)) return true;
        }
        for (String kw : LIGHT_MED_KEYWORDS) {
            if (id.contains(kw)) return true;
        }
        UseAnim anim = stack.getUseAnimation();
        return anim == UseAnim.EAT || anim == UseAnim.DRINK || anim == UseAnim.BRUSH;
    }

    public static boolean hasAnyConsumable(LivingEntity entity) {
        return findConsumable(entity) != null;
    }

    private static String itemIdString(ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        String reg = key != null ? key.toString().toLowerCase(Locale.ROOT) : "";
        String cls = stack.getItem().getClass().getSimpleName().toLowerCase(Locale.ROOT);
        return reg + " " + cls;
    }

    @Override
    public boolean canUse() {
        if (this.cooldown > 0) {
            this.cooldown--;
            return false;
        }
        if (!this.mob.isAlive() || this.mob.isUsingItem()) {
            return false;
        }

        FoundConsumable found = findConsumable(this.mob);
        if (found == null) {
            return false;
        }

        float hp = this.mob.getHealth();
        float maxHp = this.mob.getMaxHealth();
        boolean isBuffFood = isAlwaysEdibleBuffItem(found.preview, this.mob);
        boolean needsAbsorption = isBuffFood && !this.mob.hasEffect(MobEffects.ABSORPTION);

        // Normal food/meds require missing HP; Golden Apples / always-edible buff foods
        // can also be eaten at full HP whenever the unit lacks Absorption!
        if (hp >= maxHp - 0.5F && !needsAbsorption) {
            return false;
        }

        LivingEntity target = this.mob.getTarget();
        boolean inCombat = target != null && target.isAlive();
        if (inCombat && !needsAbsorption) {
            // In combat: eat/heal when below 85% HP and not in point-blank melee (< 2.5 blocks)
            if (hp > maxHp * 0.85F) {
                return false;
            }
            double distSqr = this.mob.distanceToSqr(target);
            if (distSqr < 2.5D * 2.5D && this.mob.getSensing().hasLineOfSight(target)) {
                return false;
            }
        }

        this.activeVisualStack = found.preview.copy();
        this.activeVisualStack.setCount(1);
        this.consumeAction = found.consumeOne;
        this.eatingAlwaysEdibleBuff = needsAbsorption;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.mob.isAlive()
                && this.useTicks < USE_DURATION_TICKS
                && !this.activeVisualStack.isEmpty()
                && (this.eatingAlwaysEdibleBuff || this.mob.getHealth() < this.mob.getMaxHealth());
    }

    private boolean swappedEmptyOffhand = false;

    @Override
    public void start() {
        this.useTicks = 0;
        ItemStack currentOff = this.mob.getItemBySlot(EquipmentSlot.OFFHAND);
        if (currentOff.isEmpty()) {
            this.swappedEmptyOffhand = true;
            this.mob.setItemSlot(EquipmentSlot.OFFHAND, this.activeVisualStack.copy());
        } else {
            this.swappedEmptyOffhand = false;
        }
        this.mob.startUsingItem(InteractionHand.OFF_HAND);
    }

    @Override
    public void tick() {
        this.useTicks++;
        if (!this.mob.isUsingItem()) {
            this.mob.startUsingItem(InteractionHand.OFF_HAND);
        }

        if (this.useTicks % 4 == 0) {
            boolean isFood = this.activeVisualStack.isEdible()
                    || this.activeVisualStack.getFoodProperties(this.mob) != null;
            boolean isDrink = this.activeVisualStack.getUseAnimation() == UseAnim.DRINK
                    || this.activeVisualStack.getItem() instanceof PotionItem;

            if (isDrink) {
                this.mob.playSound(SoundEvents.GENERIC_DRINK, 0.7F, 0.9F + this.mob.getRandom().nextFloat() * 0.2F);
            } else if (isFood) {
                this.mob.playSound(SoundEvents.GENERIC_EAT, 0.7F, 0.9F + this.mob.getRandom().nextFloat() * 0.2F);
            } else {
                this.mob.playSound(SoundEvents.WOOL_PLACE, 0.7F, 1.1F + this.mob.getRandom().nextFloat() * 0.2F);
            }

            if (this.mob.level() instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(
                        new ItemParticleOption(ParticleTypes.ITEM, this.activeVisualStack),
                        this.mob.getX(),
                        this.mob.getEyeY() - 0.25D,
                        this.mob.getZ(),
                        3, 0.12D, 0.08D, 0.12D, 0.02D);
            }
        }

        if (this.useTicks >= USE_DURATION_TICKS && this.consumeAction != null) {
            if (this.swappedEmptyOffhand) {
                this.mob.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                this.swappedEmptyOffhand = false;
            }
            ItemStack consumed = this.consumeAction.get();
            if (!consumed.isEmpty()) {
                applyHealingAndEffects(consumed);
            }
        }
    }

    @Override
    public void stop() {
        this.mob.stopUsingItem();
        if (this.swappedEmptyOffhand) {
            this.mob.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            this.swappedEmptyOffhand = false;
        }
        this.activeVisualStack = ItemStack.EMPTY;
        this.consumeAction = null;
        this.eatingAlwaysEdibleBuff = false;
        this.cooldown = COOLDOWN_TICKS;
    }

    private void applyHealingAndEffects(ItemStack stack) {
        float hpBefore = this.mob.getHealth();
        FoodProperties food = stack.getFoodProperties(this.mob);

        // 1. Try the item's own finishUsingItem hook (handles golden apples, modded potions, etc.)
        try {
            stack.copy().finishUsingItem(this.mob.level(), this.mob);
        } catch (Throwable ignored) {
            // Some modded bandages cast LivingEntity to Player inside finishUsingItem
        }

        // 2. Food items: vanilla LivingEntity.eat only adds hunger to Players, not Mobs!
        //    Convert nutrition + saturation into direct health recovery + food status effects.
        if (food != null) {
            for (Pair<MobEffectInstance, Float> pair : food.getEffects()) {
                if (pair.getFirst() != null && this.mob.getRandom().nextFloat() < pair.getSecond()) {
                    this.mob.addEffect(new MobEffectInstance(pair.getFirst()));
                }
            }
            float heal = Math.max(4.0F, food.getNutrition() * 0.85F + food.getSaturationModifier() * 2.5F);
            this.mob.heal(heal);
            this.mob.playSound(SoundEvents.PLAYER_BURP, 0.6F, 0.9F + this.mob.getRandom().nextFloat() * 0.2F);
        } else if (stack.getItem() instanceof PotionItem) {
            for (MobEffectInstance eff : PotionUtils.getMobEffects(stack)) {
                if (eff.getEffect().isInstantenous()) {
                    eff.getEffect().applyInstantenousEffect(this.mob, this.mob, this.mob, eff.getAmplifier(), 1.0D);
                } else {
                    this.mob.addEffect(new MobEffectInstance(eff));
                }
            }
            if (this.mob.getHealth() <= hpBefore + 0.1F) {
                this.mob.heal(6.0F);
            }
        } else {
            // 3. Modded bandage / medkit / syringe: if finishUsingItem didn't heal this Mob,
            //    apply direct healing + brief regeneration scaled by heavy vs light medical item.
            if (this.mob.getHealth() <= hpBefore + 0.1F) {
                String id = itemIdString(stack);
                boolean heavy = false;
                for (String kw : HEAVY_MED_KEYWORDS) {
                    if (id.contains(kw)) {
                        heavy = true;
                        break;
                    }
                }
                float healAmt = heavy ? 12.0F : 6.0F;
                this.mob.heal(healAmt);
                this.mob.addEffect(new MobEffectInstance(MobEffects.REGENERATION, heavy ? 100 : 80, heavy ? 1 : 0));
            }
            this.mob.playSound(SoundEvents.ARMOR_EQUIP_LEATHER, 0.8F, 1.1F);
        }

        if (this.mob.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(
                    ParticleTypes.HEART,
                    this.mob.getX(),
                    this.mob.getEyeY() + 0.25D,
                    this.mob.getZ(),
                    4, 0.25D, 0.2D, 0.25D, 0.01D);
        }
    }

    record FoundConsumable(ItemStack preview, Supplier<ItemStack> consumeOne) {}

    static FoundConsumable findConsumable(LivingEntity entity) {
        return findConsumable(entity, UnitSelfHealGoal::isHealOrFoodItem);
    }

    // Food-only variant for the hunger goal - bandages and buff
    // potions do not count as rations.
    static FoundConsumable findFood(LivingEntity entity) {
        return findConsumable(entity, UnitSelfHealGoal::isFoodItem);
    }

    static boolean isFoodItem(ItemStack stack, LivingEntity entity) {
        return !stack.isEmpty()
                && (stack.isEdible() || stack.getFoodProperties(entity) != null);
    }

    private static FoundConsumable findConsumable(LivingEntity entity,
            java.util.function.BiPredicate<ItemStack, LivingEntity> accepts) {
        // 1. Check unit's ITEM_HANDLER capability (slots 6..17 storage, slot 1 offhand, plus nested bags)
        var capOpt = entity.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve();
        if (capOpt.isPresent()) {
            IItemHandler handler = capOpt.get();
            FoundConsumable direct = scanHandler(handler, entity, true, accepts);
            if (direct != null) {
                return direct;
            }
        }

        // 2. Check equipment slots (e.g. backpack worn on chest/legs/head with nested ITEM_HANDLER or NBT)
        for (EquipmentSlot eqSlot : EquipmentSlot.values()) {
            ItemStack eqStack = entity.getItemBySlot(eqSlot);
            if (!eqStack.isEmpty()) {
                FoundConsumable nested = scanNestedBag(eqStack, entity, accepts);
                if (nested != null) {
                    return nested;
                }
            }
        }

        // 3. Check Curios slots via reflection (supports badges/pouches/backpacks placed in Curios slots)
        FoundConsumable fromCurios = scanCurios(entity, accepts);
        if (fromCurios != null) {
            return fromCurios;
        }

        // 4. Fallback: built-in field rations for units without a full inventory grid (RU / US)
        CompoundTag data = entity.getPersistentData();
        int rations = data.getInt("semcs_field_rations");
        if (rations > 0) {
            ItemStack steak = new ItemStack(Items.COOKED_BEEF);
            return new FoundConsumable(steak, () -> {
                int r = data.getInt("semcs_field_rations");
                if (r <= 0) return ItemStack.EMPTY;
                data.putInt("semcs_field_rations", r - 1);
                return new ItemStack(Items.COOKED_BEEF);
            });
        }
        return null;
    }

    private static FoundConsumable scanHandler(IItemHandler handler, LivingEntity entity, boolean skipMainHandAndArmor, java.util.function.BiPredicate<ItemStack, LivingEntity> accepts) {
        int slots = handler.getSlots();
        // Pass 1A: Prefer high-priority items (Golden Apples, Enchanted Golden Apples, Medkits) first
        for (int i = 0; i < slots; i++) {
            if (skipMainHandAndArmor && slots >= 18 && (i == 0 || (i >= 2 && i <= 5))) {
                continue;
            }
            ItemStack stack = handler.getStackInSlot(i);
            if (isAlwaysEdibleBuffItem(stack, entity)) {
                return createSlotConsumable(handler, i, stack);
            }
        }
        // Pass 1B: Any direct food / bandage / potion item in storage slots (6+) or offhand (1)
        for (int i = 0; i < slots; i++) {
            if (skipMainHandAndArmor && slots >= 18 && (i == 0 || (i >= 2 && i <= 5))) {
                continue;
            }
            ItemStack stack = handler.getStackInSlot(i);
            if (accepts.test(stack, entity)) {
                return createSlotConsumable(handler, i, stack);
            }
        }
        // Second pass: nested bags / pouches / badges inside any slot
        for (int i = 0; i < slots; i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty()) {
                FoundConsumable nested = scanNestedBag(stack, entity, accepts);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static FoundConsumable createSlotConsumable(IItemHandler handler, int slotIdx, ItemStack stack) {
        return new FoundConsumable(stack.copy(), () -> {
            if (handler instanceof IItemHandlerModifiable mod) {
                ItemStack cur = mod.getStackInSlot(slotIdx);
                if (cur.isEmpty()) return ItemStack.EMPTY;
                if (cur.isDamageableItem()) {
                    ItemStack used = cur.copy();
                    used.setCount(1);
                    int nextDmg = cur.getDamageValue() + 1;
                    if (nextDmg >= cur.getMaxDamage()) {
                        mod.setStackInSlot(slotIdx, ItemStack.EMPTY);
                    } else {
                        cur.setDamageValue(nextDmg);
                        mod.setStackInSlot(slotIdx, cur);
                    }
                    return used;
                }
                ItemStack one = cur.split(1);
                mod.setStackInSlot(slotIdx, cur.isEmpty() ? ItemStack.EMPTY : cur);
                return one;
            }
            return handler.extractItem(slotIdx, 1, false);
        });
    }

    private static FoundConsumable scanNestedBag(ItemStack containerStack, LivingEntity entity, java.util.function.BiPredicate<ItemStack, LivingEntity> accepts) {
        // A. Check if the bag/badge exposes ForgeCapabilities.ITEM_HANDLER
        var capOpt = containerStack.getCapability(ForgeCapabilities.ITEM_HANDLER, (Direction) null).resolve();
        if (capOpt.isPresent()) {
            FoundConsumable res = scanHandler(capOpt.get(), entity, false, accepts);
            if (res != null) {
                return res;
            }
        }
        // B. Check NBT-backed bags/pouches/bundles ("Items" list or "Inventory"."Items" list)
        CompoundTag tag = containerStack.getTag();
        if (tag == null) {
            return null;
        }
        FoundConsumable fromDirectList = scanNbtItemList(tag, "Items", entity, accepts);
        if (fromDirectList != null) {
            return fromDirectList;
        }
        if (tag.contains("Inventory", Tag.TAG_COMPOUND)) {
            CompoundTag invTag = tag.getCompound("Inventory");
            FoundConsumable fromInv = scanNbtItemList(invTag, "Items", entity, accepts);
            if (fromInv != null) {
                return fromInv;
            }
        }
        if (tag.contains("BlockEntityTag", Tag.TAG_COMPOUND)) {
            CompoundTag beTag = tag.getCompound("BlockEntityTag");
            FoundConsumable fromBe = scanNbtItemList(beTag, "Items", entity, accepts);
            if (fromBe != null) {
                return fromBe;
            }
        }
        return null;
    }

    private static FoundConsumable scanNbtItemList(CompoundTag parent, String key, LivingEntity entity, java.util.function.BiPredicate<ItemStack, LivingEntity> accepts) {
        if (!parent.contains(key, Tag.TAG_LIST)) {
            return null;
        }
        ListTag list = parent.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag itemTag = list.getCompound(i);
            ItemStack parsed = ItemStack.of(itemTag);
            if (accepts.test(parsed, entity)) {
                final int idx = i;
                return new FoundConsumable(parsed.copy(), () -> {
                    if (idx >= list.size()) return ItemStack.EMPTY;
                    CompoundTag curTag = list.getCompound(idx);
                    ItemStack cur = ItemStack.of(curTag);
                    if (cur.isEmpty()) return ItemStack.EMPTY;
                    ItemStack one = cur.split(1);
                    if (cur.isEmpty()) {
                        list.remove(idx);
                    } else {
                        CompoundTag updated = new CompoundTag();
                        cur.save(updated);
                        if (curTag.contains("Slot")) {
                            updated.put("Slot", curTag.get("Slot").copy());
                        }
                        list.set(idx, updated);
                    }
                    return one;
                });
            }
        }
        return null;
    }

    private static FoundConsumable scanCurios(LivingEntity entity, java.util.function.BiPredicate<ItemStack, LivingEntity> accepts) {
        try {
            Class<?> apiCls = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            var mGet = apiCls.getMethod("getCuriosInventory", LivingEntity.class);
            Object lazyOpt = mGet.invoke(null, entity);
            if (lazyOpt instanceof net.minecraftforge.common.util.LazyOptional<?> lo && lo.isPresent()) {
                Object curiosHandler = lo.orElse(null);
                if (curiosHandler != null) {
                    var mEquipped = curiosHandler.getClass().getMethod("getEquippedCurios");
                    Object equipped = mEquipped.invoke(curiosHandler);
                    if (equipped instanceof IItemHandler itemHandler) {
                        return scanHandler(itemHandler, entity, false, accepts);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
