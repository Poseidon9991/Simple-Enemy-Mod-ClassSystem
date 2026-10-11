// Item package - the armor material behind the BHQ gear set
package com.poseidon.sem_classsystem.item;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.LazyLoadedValue;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;

// Elite tier between iron and diamond: protection of diamond-lite with
// better-than-iron durability. getName() = "bhq" drives the vanilla player
// armor texture path; SEM's unit layers use BhqArmorItem.getArmorTexture,
// which points at the same files.
public enum BhqArmorMaterial implements ArmorMaterial {
    INSTANCE;

    private final LazyLoadedValue<Ingredient> repair =
            new LazyLoadedValue<>(() -> Ingredient.of(Items.IRON_INGOT));

    @Override
    public int getDurabilityForType(ArmorItem.Type type) {
        return switch (type) {
            case HELMET -> 220;
            case CHESTPLATE -> 320;
            case LEGGINGS -> 300;
            case BOOTS -> 260;
        };
    }

    @Override
    public int getDefenseForType(ArmorItem.Type type) {
        return switch (type) {
            case HELMET -> 3;
            case CHESTPLATE -> 7;
            case LEGGINGS -> 6;
            case BOOTS -> 3;
        };
    }

    @Override
    public int getEnchantmentValue() {
        return 12;
    }

    @Nullable
    @Override
    public SoundEvent getEquipSound() {
        return SoundEvents.ARMOR_EQUIP_LEATHER;
    }

    @Override
    public Ingredient getRepairIngredient() {
        return this.repair.get();
    }

    @Override
    public String getName() {
        return "bhq";
    }

    @Override
    public float getToughness() {
        return 1.0F;
    }

    @Override
    public float getKnockbackResistance() {
        return 0.0F;
    }
}
