// Item package - BHQ's own ballistic helmet + plate carrier
package com.poseidon.sem_classsystem.item;

import com.atsuishio.superbwarfare.resource.BedrockModelLoader;
import com.atsuishio.superbwarfare.tiers.ModArmorMaterial;
import com.github.mcmodderanchor.simplebedrockmodel.v1.client.model.BedrockArmorModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.client.renderer.GeoArmorRenderer;
import com.poseidon.sem_classsystem.SemClassSystem;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import java.util.function.Consumer;

// Real-model armor piece rendered through SBW's bedrock armor pipeline
// (the same one its own PASGT/6B47/IOTV pieces use):
//   geo:   assets/sem_classsystem/models/bedrock/armor/<name>.geo.json
//   skin:  assets/sem_classsystem/textures/bedrock/armor/<name>.png
// The model RL is registered with simplebedrockmodel's RegisterBedrockModelEvent
// (SbwArmorModels) and looked up via BedrockModelLoader.getArmorModel.
//
// These classes touch superbwarfare + simplebedrockmodel directly, so the
// items are only registered when both mods are loaded (SemcsRegistries).
//
// NB: we read the global BedrockModelResourceSet via getModel(), not
// BedrockModelLoader.getArmorModel() - the latter only serves RLs that SBW
// registered through its own registerArmorModel(), which is hardcoded to
// the superbwarfare: namespace and can't carry our IDs.
public class BhqArmorItem extends ArmorItem {

    public BhqArmorItem(Type type, String name) {
        super(ModArmorMaterial.CEMENTED_CARBIDE, type, new Properties().stacksTo(1));
        this.modelId = ResourceLocation.fromNamespaceAndPath(SemClassSystem.MODID, "armor/" + name);
        this.texture = ResourceLocation.fromNamespaceAndPath(
                SemClassSystem.MODID, "textures/bedrock/armor/" + name + ".png");
    }

    private final ResourceLocation modelId;
    private final ResourceLocation texture;

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private GeoArmorRenderer renderer;

            @Override
            public HumanoidModel<?> getHumanoidArmorModel(
                    LivingEntity livingEntity, ItemStack itemStack,
                    EquipmentSlot equipmentSlot, HumanoidModel<?> original) {
                // Registered via SbwArmorModels -> RegisterBedrockModelEvent,
                // so it lands in the shared model resource set.
                if (!(BedrockModelLoader.getModel(modelId) instanceof BedrockArmorModel model)) {
                    return original;   // model not loaded - vanilla path, stay safe
                }
                if (this.renderer == null) {
                    this.renderer = new GeoArmorRenderer(model, texture);
                }
                this.renderer.preparePose(livingEntity, itemStack, equipmentSlot, original);
                return this.renderer;
            }
        });
    }
}
