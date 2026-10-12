package com.poseidon.sem_classsystem.item;

import com.poseidon.sem_classsystem.client.BhqArmorModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.NotNull;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.renderer.GeoArmorRenderer;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.function.Consumer;

// One GeckoLib armor item per BHQ piece. Following the ZeroContact pattern:
// the item is a GeoItem, IClientItemExtensions hands back a
// GeoArmorRenderer (which is itself a HumanoidModel), and each piece owns
// its own .geo.json so SEM's armor layers can pose it like any other
// humanoid armor on both players and units.
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
    public void initializeClient(@NotNull Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            private GeoArmorRenderer<BhqArmorItem> renderer;

            @Override
            public @NotNull HumanoidModel<?> getHumanoidArmorModel(LivingEntity entity,
                                                                 ItemStack stack,
                                                                 EquipmentSlot slot,
                                                                 HumanoidModel<?> base) {
                if (this.renderer == null) {
                    this.renderer = new GeoArmorRenderer<>(new BhqArmorModel());
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
