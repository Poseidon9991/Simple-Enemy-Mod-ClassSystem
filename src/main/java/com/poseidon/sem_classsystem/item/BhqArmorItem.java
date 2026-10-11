// Item package - one armor piece of the BHQ gear set
package com.poseidon.sem_classsystem.item;

import com.poseidon.sem_classsystem.SemClassSystem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

// The texture override is what makes the set render on SEM units: both of
// SEM's armor layers (UniversalArmorLayer and the GeckoLib-backed one) ask
// Forge's IForgeItem.getArmorTexture first, so pointing it at our painted
// layer files covers units AND players with one string. Legs read layer_2,
// everything else layer_1 - the vanilla convention.
public class BhqArmorItem extends ArmorItem {

    public BhqArmorItem(Type type, Properties properties) {
        super(BhqArmorMaterial.INSTANCE, type, properties);
    }

    @Nullable
    @Override
    public String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot,
                                  @Nullable String type) {
        if (type != null) {
            return null;   // no dyeable overlay pass - the set is not leather
        }
        int layer = slot == EquipmentSlot.LEGS ? 2 : 1;
        return SemClassSystem.MODID + ":textures/models/armor/bhq_layer_" + layer + ".png";
    }
}
