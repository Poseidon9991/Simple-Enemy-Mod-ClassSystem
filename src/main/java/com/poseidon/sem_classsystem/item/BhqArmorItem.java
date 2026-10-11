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

    // Swap vanilla's flat armor shell for the 3D BHQ model. Both ArmorLayer
    // (players) and SEM's UniversalArmorLayer (units) fetch the model through
    // Forge's getHumanoidArmorModel hook, so one override covers everyone.
    // The anonymous class only loads on the client - this method is never
    // called on a dedicated server.
    @Override
    public void initializeClient(java.util.function.Consumer<net.minecraftforge.client.extensions.common.IClientItemExtensions> consumer) {
        consumer.accept(new net.minecraftforge.client.extensions.common.IClientItemExtensions() {
            @Override
            public net.minecraft.client.model.HumanoidModel<?> getHumanoidArmorModel(
                    net.minecraft.world.entity.LivingEntity entity, ItemStack stack,
                    EquipmentSlot slot, net.minecraft.client.model.HumanoidModel<?> original) {
                return com.poseidon.sem_classsystem.client.BhqArmorModels.forSlot(slot);
            }
        });
    }
}
