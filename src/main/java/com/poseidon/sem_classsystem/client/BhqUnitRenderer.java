// Client package - renderers only exist on the client (never server code!)
package com.poseidon.sem_classsystem.client;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.client.pmc_unit.PmcUnitRenderer;
import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.entity.BhqUnitEntity;

// Renders the BHQ unit with SEM's own PMC model (same bones, animations,
// gun layer, armor layers) but swaps the texture for our BHQ skins.
// Variant 0 = black balaclava, 1 = skull mask, 2 = sunglasses.
public class BhqUnitRenderer extends PmcUnitRenderer {

    private static final ResourceLocation[] BHQ_TEXTURES = new ResourceLocation[]{
            ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "textures/entity/bhq_unit/bhq_unit_mask.png"),
            ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "textures/entity/bhq_unit/bhq_unit_skull.png"),
            ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "textures/entity/bhq_unit/bhq_unit_shades.png"),
    };

    public BhqUnitRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(PmcUnitEntity entity) {
        int variant = entity instanceof BhqUnitEntity bhq
                ? Math.floorMod(entity.getVariant(), BHQ_TEXTURES.length)
                : 0;
        return BHQ_TEXTURES[variant];
    }
}
