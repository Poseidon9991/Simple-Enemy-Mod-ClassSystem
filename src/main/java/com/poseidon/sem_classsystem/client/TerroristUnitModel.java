package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.entity.TerroristUnitEntity;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.nekoyuni.SimpleEnemyMod.entity.client.pmc_unit.PmcUnitModel;

// Insurgent unit model extending SEM's PmcUnitModel rig.
// Keeps identical bone names/pivots so MixinUnitModelCrouch, UnitProceduralPoses,
// GunLayerRenderer, UniversalArmorLayer and animations all work seamlessly, while
// adding 3D keffiyeh/shemagh cloth panels on the head and tactical chest rig
// magazine/utility pouches on the torso using unused corners of the 64x64 sheet.
public class TerroristUnitModel extends PmcUnitModel<TerroristUnitEntity> {

    public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(SemClassSystem.MODID, "terroristunit"),
            "main");

    public TerroristUnitModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        PartDefinition fakeRoot = root.addOrReplaceChild("fakeRoot",
                CubeListBuilder.create(), PartPose.offset(0.0F, 6.0F, -1.0F));
        PartDefinition unit = fakeRoot.addOrReplaceChild("unit",
                CubeListBuilder.create(), PartPose.offsetAndRotation(
                        0.0F, 0.0F, 0.0F, 0.0873F, 0.0F, 0.0F));

        // Head + 0.5F wrap layer for balaclava / shemagh texture detail
        PartDefinition head = unit.addOrReplaceChild("head", CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, new CubeDeformation(0.0F))
                        .texOffs(32, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, new CubeDeformation(0.5F)),
                PartPose.offsetAndRotation(0.0F, -6.16F, 1.0616F, -0.0105F, 0.0735F, 0.0228F));

        // 3D front keffiyeh chin/neck fold (texOffs(24,0) -> 8x3 region in unused head corner)
        head.addOrReplaceChild("shemagh_chin", CubeListBuilder.create()
                        .texOffs(24, 0).addBox(-1.5F, -0.6F, -4.55F, 3.0F, 2.0F, 1.0F, new CubeDeformation(0.1F)),
                PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, -0.10F, 0.0F, 0.0F));

        // 3D draped keffiyeh tail on the back of head/nape (two 3x4x1 panels in free 56..63, 34..45 space)
        head.addOrReplaceChild("shemagh_drape_left", CubeListBuilder.create()
                        .texOffs(56, 34).addBox(0.25F, -2.0F, 3.6F, 3.0F, 4.0F, 1.0F, new CubeDeformation(0.05F)),
                PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, 0.24F, 0.0F, -0.06F));
        head.addOrReplaceChild("shemagh_drape_right", CubeListBuilder.create()
                        .texOffs(56, 40).addBox(-3.25F, -2.0F, 3.6F, 3.0F, 4.0F, 1.0F, new CubeDeformation(0.05F)),
                PartPose.offsetAndRotation(0.0F, 0.0F, 0.0F, 0.24F, 0.0F, 0.06F));

        // Torso + outer tunic / rig shell
        PartDefinition body = unit.addOrReplaceChild("body", CubeListBuilder.create()
                        .texOffs(16, 16).mirror().addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, new CubeDeformation(0.0F))
                        .mirror(false)
                        .texOffs(16, 32).addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, new CubeDeformation(0.25F)),
                PartPose.offsetAndRotation(0.0F, -6.26F, 1.0616F, 0.0997F, 0.6536F, 0.0238F));

        // 3D MOLLE AK-47 magazine pouches across the chest rig + side utility pouches (free 56..63, 16..33 space)
        body.addOrReplaceChild("chest_mag_pouches", CubeListBuilder.create()
                        .texOffs(56, 16).addBox(-3.0F, 3.3F, -3.05F, 2.0F, 4.0F, 1.0F, new CubeDeformation(-0.12F))
                        .texOffs(56, 16).addBox(-1.0F, 3.3F, -3.12F, 2.0F, 4.0F, 1.0F, new CubeDeformation(-0.12F))
                        .texOffs(56, 16).addBox(1.0F, 3.3F, -3.05F, 2.0F, 4.0F, 1.0F, new CubeDeformation(-0.12F)),
                PartPose.ZERO);
        body.addOrReplaceChild("hip_pouches", CubeListBuilder.create()
                        .texOffs(56, 22).addBox(-4.35F, 6.2F, -1.2F, 1.0F, 3.0F, 2.0F, new CubeDeformation(-0.05F))
                        .texOffs(56, 28).addBox(3.35F, 6.2F, -1.2F, 1.0F, 3.0F, 2.0F, new CubeDeformation(-0.05F)),
                PartPose.ZERO);

        PartDefinition rightArm = unit.addOrReplaceChild("rightArm", CubeListBuilder.create()
                        .texOffs(40, 16).addBox(-3.0123F, -1.9895F, -1.7505F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.0F))
                        .texOffs(40, 32).addBox(-3.0123F, -1.9895F, -1.7505F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.25F)),
                PartPose.offsetAndRotation(-3.0F, -2.9089F, 3.4377F, -1.6919F, -0.0024F, -0.0561F));
        rightArm.addOrReplaceChild("rightHandAnchor",
                CubeListBuilder.create(), PartPose.offset(-1.0F, 9.0F, 0.0F));

        unit.addOrReplaceChild("leftArm", CubeListBuilder.create()
                        .texOffs(32, 48).addBox(-1.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.0F))
                        .texOffs(48, 48).addBox(-1.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.25F)),
                PartPose.offsetAndRotation(2.5F, -3.2588F, -3.4123F, -1.5619F, 0.6592F, 0.1584F));

        unit.addOrReplaceChild("rightLeg", CubeListBuilder.create()
                        .texOffs(0, 16).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 32).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.25F)),
                PartPose.offsetAndRotation(-1.9F, 6.0F, 4.0F, 0.1309F, 0.0F, 0.0F));

        unit.addOrReplaceChild("leftLeg", CubeListBuilder.create()
                        .texOffs(16, 48).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.0F))
                        .texOffs(0, 48).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, new CubeDeformation(0.25F)),
                PartPose.offsetAndRotation(2.15F, 5.85F, -1.0F, -0.1745F, 0.0F, 0.0F));

        return LayerDefinition.create(mesh, 64, 64);
    }
}
