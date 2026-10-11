// Client-only - GeckoLib model binding for BHQ armor pieces.
package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.item.BhqArmorItem;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

// One model per piece: the geo file holds only that piece's bones, so a
// vest physically cannot draw a cap no matter which renderer drives it.
public class BhqArmorModel extends GeoModel<BhqArmorItem> {

    @Override
    public ResourceLocation getModelResource(BhqArmorItem item) {
        return ResourceLocation.fromNamespaceAndPath(SemClassSystem.MODID,
                "geo/" + item.piece() + ".geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(BhqArmorItem item) {
        return ResourceLocation.fromNamespaceAndPath(SemClassSystem.MODID,
                "textures/models/armor/" + item.piece() + ".png");
    }

    @Override
    public ResourceLocation getAnimationResource(BhqArmorItem item) {
        return null;
    }
}
