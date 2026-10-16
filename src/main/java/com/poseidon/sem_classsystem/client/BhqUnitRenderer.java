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
// Variants 0-2 = woodland camo (balaclava, skull, shades), 3-5 = GRK navy (mask, skull, beret).
public class BhqUnitRenderer extends PmcUnitRenderer {

    private static final ResourceLocation[] BHQ_TEXTURES = new ResourceLocation[]{
            // Standard BHQ variants (woodland camo)
            ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "textures/entity/bhq_unit/bhq_unit_mask.png"),
            ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "textures/entity/bhq_unit/bhq_unit_skull.png"),
            ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "textures/entity/bhq_unit/bhq_unit_shades.png"),
            // GRK variants (royal navy blue uniform)
            ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "textures/entity/bhq_unit/bhq_unit_grk_mask.png"),
            ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "textures/entity/bhq_unit/bhq_unit_grk_skull.png"),
            ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "textures/entity/bhq_unit/bhq_unit_grk_beret.png"),
    };

    public BhqUnitRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(PmcUnitEntity entity) {
        int variant = entity instanceof BhqUnitEntity
                ? Math.floorMod(entity.getVariant(), BHQ_TEXTURES.length)
                : 0;
        return BHQ_TEXTURES[variant];
    }
}
