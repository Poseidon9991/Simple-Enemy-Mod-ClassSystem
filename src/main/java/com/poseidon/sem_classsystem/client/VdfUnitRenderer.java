// Client package - renderers only exist on the client (never server code!)
package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.entity.VdfUnitEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

// Plain MobRenderer now - no RUunitRenderer parent. The model is our own
// HierarchicalModel rig; layers are the VdfHeldItemLayer (gun/sword at the
// rightHandAnchor bone) and VdfArmorLayer (BhqArmorItem gecko pieces + any
// vanilla armor), with UnitCuriosRenderLayer attached by ClientEventHandlers
// when Curios loads.
public class VdfUnitRenderer extends MobRenderer<VdfUnitEntity, VdfUnitModel> {

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
        super(context, new VdfUnitModel(context.bakeLayer(VdfUnitModel.LAYER_LOCATION)), 0.5F);
        this.addLayer(new VdfHeldItemLayer(this, context.getItemInHandRenderer()));
        this.addLayer(new VdfArmorLayer(this, context));
    }

    @Override
    public ResourceLocation getTextureLocation(VdfUnitEntity entity) {
        return VDF_TEXTURES[Math.floorMod(entity.getVariant(), VDF_TEXTURES.length)];
    }
}
