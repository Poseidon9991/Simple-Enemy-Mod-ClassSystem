package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.entity.TerroristUnitEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.nekoyuni.SimpleEnemyMod.compat.geckolib.GeckoCompat;
import net.nekoyuni.SimpleEnemyMod.compat.geckolib.GeckoCompatClient;
import net.nekoyuni.SimpleEnemyMod.entity.client.GunLayerRenderer;
import net.nekoyuni.SimpleEnemyMod.entity.client.util.UniversalArmorLayer;

// Renders the Terrorist Insurgent entity using our TerroristUnitModel (which
// extends PmcUnitModel and shares SEM's animation pipeline + armor bone provider).
// Swaps cleanly across 6 distinct Middle Eastern guerrilla skins.
public class TerroristUnitRenderer extends MobRenderer<TerroristUnitEntity, TerroristUnitModel> {

    private static final ResourceLocation[] TERRORIST_TEXTURES = new ResourceLocation[]{
            ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "textures/entity/terrorist_unit/terrorist_0.png"),
            ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "textures/entity/terrorist_unit/terrorist_1.png"),
            ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "textures/entity/terrorist_unit/terrorist_2.png"),
            ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "textures/entity/terrorist_unit/terrorist_3.png"),
            ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "textures/entity/terrorist_unit/terrorist_4.png"),
            ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "textures/entity/terrorist_unit/terrorist_5.png"),
    };

    @SuppressWarnings({"unchecked", "rawtypes"})
    public TerroristUnitRenderer(EntityRendererProvider.Context context) {
        super(context, new TerroristUnitModel(context.bakeLayer(TerroristUnitModel.LAYER_LOCATION)), 0.5F);
        HumanoidModel innerArmor = new HumanoidModel(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
        HumanoidModel outerArmor = new HumanoidModel(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));
        this.addLayer(new UniversalArmorLayer<>(this, innerArmor, outerArmor));
        if (GeckoCompat.LOADED) {
            HumanoidModel geckoDummy = new HumanoidModel(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
            this.addLayer(GeckoCompatClient.createArmorLayer((net.minecraft.client.renderer.entity.RenderLayerParent) this, geckoDummy));
        }
        this.addLayer(new GunLayerRenderer<>(this, context.getItemInHandRenderer()));
    }

    @Override
    public ResourceLocation getTextureLocation(TerroristUnitEntity entity) {
        int variant = Math.floorMod(entity.getVariant(), TERRORIST_TEXTURES.length);
        return TERRORIST_TEXTURES[variant];
    }
}
