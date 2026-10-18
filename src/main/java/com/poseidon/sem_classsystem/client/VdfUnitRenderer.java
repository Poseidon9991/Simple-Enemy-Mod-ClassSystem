// Client package - renderers only exist on the client (never server code!)
package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.entity.VdfUnitEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.nekoyuni.SimpleEnemyMod.entity.client.ru_unit.RUunitRenderer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;

// Renders the VDF militia on SEM's RU rig (same bones, animations, gun
// layer, armor layers) but swaps the texture for the olive-drab militia
// skins. The villager face is added separately by VdfHeadLayer - same
// trick as BhqUnitRenderer, just on the RU line instead of PMC.
public class VdfUnitRenderer extends RUunitRenderer {

    private static final ResourceLocation[] VDF_TEXTURES = new ResourceLocation[]{
            ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "textures/entity/vdf_unit/vdf_unit_0.png"),
            ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "textures/entity/vdf_unit/vdf_unit_1.png"),
            ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "textures/entity/vdf_unit/vdf_unit_2.png"),
            ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "textures/entity/vdf_unit/vdf_unit_3.png"),
    };

    public VdfUnitRenderer(EntityRendererProvider.Context context) {
        super(context);
        // Swap the borrowed RU rig for our own: same bones (animations and
        // gun-layer anchor survive) but villager geometry - nose included.
        this.model = new VdfUnitModel(context.bakeLayer(VdfUnitModel.LAYER_LOCATION));
    }

    @Override
    public ResourceLocation getTextureLocation(RUunitEntity entity) {
        int variant = entity instanceof VdfUnitEntity
                ? Math.floorMod(entity.getVariant(), VDF_TEXTURES.length)
                : 0;
        return VDF_TEXTURES[variant];
    }
}
