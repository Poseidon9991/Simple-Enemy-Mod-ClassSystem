package com.poseidon.sem_classsystem.item;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.function.Supplier;

public enum SemcsArmorMaterial implements ArmorMaterial {
    CAMPER("camper_", 16, new int[]{2, 3, 5, 3}, 18, 0.5F, 0.0F, SoundEvents.ARMOR_EQUIP_LEATHER, () -> Ingredient.of(Items.LEATHER)),
    SF911("sf911_", 18, new int[]{2, 4, 6, 3}, 15, 0.75F, 0.0F, SoundEvents.ARMOR_EQUIP_CHAIN, () -> Ingredient.of(Items.IRON_INGOT)),
    RAF("raf_", 14, new int[]{2, 3, 5, 4}, 12, 0.25F, 0.0F, SoundEvents.ARMOR_EQUIP_LEATHER, () -> Ingredient.of(Items.STRING)),
    GRK("grk_", 24, new int[]{2, 4, 7, 5}, 11, 1.0F, 0.1F, SoundEvents.ARMOR_EQUIP_IRON, () -> Ingredient.of(Items.IRON_INGOT)),
    SUPPRESSOR("suppressor_", 40, new int[]{3, 6, 9, 4}, 9, 2.0F, 0.2F, SoundEvents.ARMOR_EQUIP_DIAMOND, () -> Ingredient.of(Items.NETHERITE_INGOT)),
    DEMOLISHER("demolisher_", 36, new int[]{3, 5, 8, 4}, 7, 2.5F, 0.25F, SoundEvents.ARMOR_EQUIP_NETHERITE, () -> Ingredient.of(Items.DIAMOND)),
    RU("ru_", 26, new int[]{3, 5, 7, 3}, 10, 1.0F, 0.0F, SoundEvents.ARMOR_EQUIP_IRON, () -> Ingredient.of(Items.IRON_INGOT)),
    US("us_", 28, new int[]{3, 6, 8, 3}, 10, 1.5F, 0.05F, SoundEvents.ARMOR_EQUIP_DIAMOND, () -> Ingredient.of(Items.IRON_INGOT)),
    PMC("pmc_", 30, new int[]{3, 6, 8, 4}, 12, 1.5F, 0.1F, SoundEvents.ARMOR_EQUIP_NETHERITE, () -> Ingredient.of(Items.NETHERITE_SCRAP)),
    VDF("vdf_", 20, new int[]{2, 4, 5, 3}, 14, 0.5F, 0.0F, SoundEvents.ARMOR_EQUIP_LEATHER, () -> Ingredient.of(Items.LEATHER));

    private static final int[] BASE_DURABILITY = {13, 15, 16, 11};

    private final String prefix;
    private final int durabilityMultiplier;
    private final int[] defenseBySlot;
    private final int enchantability;
    private final float toughness;
    private final float knockbackResistance;
    private final SoundEvent equipSound;
    private final Supplier<Ingredient> repair;

    SemcsArmorMaterial(String prefix, int durabilityMultiplier, int[] defenseBySlot, int enchantability,
                       float toughness, float knockbackResistance, SoundEvent equipSound,
                       Supplier<Ingredient> repair) {
        this.prefix = prefix;
        this.durabilityMultiplier = durabilityMultiplier;
        this.defenseBySlot = defenseBySlot;
        this.enchantability = enchantability;
        this.toughness = toughness;
        this.knockbackResistance = knockbackResistance;
        this.equipSound = equipSound;
        this.repair = repair;
    }

    public static SemcsArmorMaterial forPiece(String piece) {
        for (SemcsArmorMaterial material : values()) {
            if (piece.startsWith(material.prefix)) {
                return material;
            }
        }
        throw new IllegalArgumentException("No armor material for piece " + piece);
    }

    @Override
    public int getDurabilityForType(ArmorItem.Type type) {
        return BASE_DURABILITY[type.getSlot().getIndex()] * durabilityMultiplier;
    }

    @Override
    public int getDefenseForType(ArmorItem.Type type) {
        return defenseBySlot[type.getSlot().getIndex()];
    }

    @Override
    public int getEnchantmentValue() {
        return enchantability;
    }

    @Override
    public SoundEvent getEquipSound() {
        return equipSound;
    }

    @Override
    public Ingredient getRepairIngredient() {
        return repair.get();
    }

    @Override
    public String getName() {
        return prefix + "armor";
    }

    @Override
    public float getToughness() {
        return toughness;
    }

    @Override
    public float getKnockbackResistance() {
        return knockbackResistance;
    }
}
