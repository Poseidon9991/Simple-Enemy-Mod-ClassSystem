// Client package - renderers only exist on the client (never server code!)
package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.SemClassSystem;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.nekoyuni.SimpleEnemyMod.entity.client.ru_unit.RUunitModel;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;

// The militia's own body - NOT a borrowed RU rig. Bone tree, part names,
// pivots and animations all come from SEM's UnitModelDefinitions base
// (ported 1:1 so walk/aim/death clips, the gun-layer rightHandAnchor and
// the armor-bone sync keep working), then the head gets what makes a
// villager a villager: a real nose cube parented under "head" so it
// follows every look/aim animation.
//
// Texture layout stays the standard 64x64 humanoid map; the nose uses
// (24,0) - the same UV the vanilla villager texture reserves for it.
public class VdfUnitModel extends RUunitModel<RUunitEntity> {

    public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "vdfunit"), "main");

    public VdfUnitModel(ModelPart root) {
        super(root);
    }

    // Clone of SEM's createBaseUnitBodyLayer + the nose. Keep every pivot
    // EXACTLY as SEM authored it - the animation clips were authored
    // against these numbers, not against "a humanoid".
    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        PartDefinition fakeRoot = root.addOrReplaceChild("fakeRoot",
                CubeListBuilder.create(), PartPose.offset(0.0F, 6.0F, -1.0F));
        PartDefinition unit = fakeRoot.addOrReplaceChild("unit",
                CubeListBuilder.create(), PartPose.offsetAndRotation(
                        0.0F, 0.0F, 0.0F, 0.0873F, 0.0F, 0.0F));

        // Head: soldier-sized shell (armor layers expect the 8x8 fit) but
        // the face carries the villager signature - a 2x4x2 nose hanging
        // off the chin line and a 3D unibrow ridge above the eyes.
        PartDefinition head = unit.addOrReplaceChild("head", CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, new CubeDeformation(0.0F))
                        .texOffs(32, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, new CubeDeformation(0.5F)),
                PartPose.offsetAndRotation(0.0F, -6.16F, 1.0616F, -0.0105F, 0.0735F, 0.0228F));
        head.addOrReplaceChild("nose", CubeListBuilder.create()
                        .texOffs(24, 0).addBox(-1.0F, -3.2F, -6.0F, 2.0F, 4.0F, 2.0F, new CubeDeformation(0.0F))
                        .texOffs(24, 6).addBox(-3.0F, -4.5F, -4.5F, 6.0F, 1.0F, 1.0F, new CubeDeformation(0.0F)),
                PartPose.ZERO);

        unit.addOrReplaceChild("body", CubeListBuilder.create()
                        .texOffs(16, 16).mirror().addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, new CubeDeformation(0.0F))
                        .mirror(false)
                        .texOffs(16, 33).addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, new CubeDeformation(0.25F)),
                PartPose.offsetAndRotation(0.0F, -6.26F, 1.0616F, 0.0997F, 0.6536F, 0.0238F));

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
