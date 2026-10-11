// Client package - soft-dep bridge to tacz_sewv's equipment render layers
package com.poseidon.sem_classsystem.client;

import com.neoalive.tacz_sewv.client.BedrockArmorLayer;
import com.neoalive.tacz_sewv.client.CuriosHeadLayer;
import com.neoalive.tacz_sewv.client.HolsterLayer;
import com.neoalive.tacz_sewv.client.SmallArmsLayer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;

// tacz_sewv equips every SEM unit with armor through NpcArmor, and draws its
// custom-model armor pieces (mich helmets, vests, holsters, curios headwear)
// via layers it attaches in its own AddLayers handler. That handler lists
// entity types explicitly and predates this addon, so our BHQ_UNIT renderer
// never received them - BHQ wore the armor but nothing rendered.
//
// This class is referenced ONLY behind a ModList.isLoaded("tacz_sewv") guard:
// the JVM does not load it (or resolve the sewv classes above) until the first
// call, so the mod stays fully optional.
public final class SewvLayersCompat {

    private SewvLayersCompat() {
    }

    // Mirrors sewv's own addUnitLayers minus its GeckoArmorLayer: BHQ inherits
    // SEM's PmcUnitRenderer constructor, which already installs SEM's gecko
    // armor layer when GeckoLib is present (the same one PMC units use).
    public static <T extends LivingEntity, M extends EntityModel<T>> void addLayers(
            LivingEntityRenderer<T, M> renderer) {
        renderer.addLayer(new BedrockArmorLayer<>(renderer));
        renderer.addLayer(new SmallArmsLayer<>(renderer));
        renderer.addLayer(new HolsterLayer<>(renderer));
        renderer.addLayer(new CuriosHeadLayer<>(renderer));
    }
}
