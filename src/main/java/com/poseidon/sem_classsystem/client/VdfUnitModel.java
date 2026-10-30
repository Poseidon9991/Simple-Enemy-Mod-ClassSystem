// Client package - renderers only exist on the client (never server code!)
package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.entity.VdfUnitEntity;
import com.tacz.guns.api.item.IGun;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;
import net.nekoyuni.SimpleEnemyMod.entity.client.animation.ModAnimationsDefinitions;

// The militia's own body - NOT a borrowed RU rig. Bone tree, part names,
// pivots and animations all come from SEM's UnitModelDefinitions base
// (ported 1:1 so the shared UNIT_* clips, the gun-layer rightHandAnchor and
// the armor-bone sync keep working), then the head gets what makes a
// villager a villager: a real nose cube parented under "head" so it
// follows every look/aim animation.
//
// Plain HierarchicalModel now - not RUunitModel. setupAnim drives SEM's
// shared clips off OUR animation states (started by VdfUnitEntity.tick()
// from the synced movement tracker), which is the root fix for the
// sliding-legs bug: SEM's own locomotion layer gated UNIT_WALK behind a
// deltaMovement condition the militia's navigation didn't reliably trip.
public class VdfUnitModel extends HierarchicalModel<VdfUnitEntity> {

    public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "vdfunit"), "main");

    private final ModelPart root;
    private final ModelPart fakeRoot;
    private final ModelPart unit;
    private final ModelPart head;
    private final ModelPart rightArm;
    private final ModelPart leftArm;

    public VdfUnitModel(ModelPart root) {
        this.root = root;
        this.fakeRoot = root.getChild("fakeRoot");
        this.unit = fakeRoot.getChild("unit");
        this.head = unit.getChild("head");
        this.rightArm = unit.getChild("rightArm");
        this.leftArm = unit.getChild("leftArm");
    }

    @Override
    public ModelPart root() {
        return this.root;
    }

    @Override
    public void setupAnim(VdfUnitEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        this.root().getAllParts().forEach(ModelPart::resetPose);

        // Head tracks the look direction like every other humanoid rig
        this.head.yRot = netHeadYaw * Mth.DEG_TO_RAD;
        this.head.xRot = headPitch * Mth.DEG_TO_RAD;

        // Night-watch crouch: isShiftKeyDown doubles as the watch-squat flag -
        // dip the whole rig slightly rather than re-posing every limb
        if (entity.isShiftKeyDown()) {
            this.fakeRoot.y += 2.0F;
        }

        // Locomotion: our own states, started/stopped by the entity's tick()
        // off the synced movement tracker. Walk wins while moving, idle fills
        // the gaps - no layered manager, no deltaMovement condition.
        if (entity.walkAnimationState.isStarted()) {
            this.animate(entity.walkAnimationState, ModAnimationsDefinitions.UNIT_WALK, ageInTicks);
        } else {
            this.animate(entity.idleAnimationState, ModAnimationsDefinitions.UNIT_IDLE, ageInTicks);
        }
        // Action clips layer on top of locomotion (they own the bones they touch)
        this.animate(entity.hurtAnimationState, ModAnimationsDefinitions.UNIT_HURT, ageInTicks);
        this.animate(entity.deathAnimationState, ModAnimationsDefinitions.UNIT_DEATH, ageInTicks);

        // Melee militia carry no rifle - the shared UNIT_* clips (and the
        // rig's own baked arm pivots) are authored as a two-handed gun hold,
        // so without this the blade sits in a phantom rifle grip even during
        // hurt/death clips. After the animations write the bones, re-pose
        // just the arms: relaxed hang at the sides plus a vanilla-style arm
        // swing while walking. Runs before swingWeaponDown so the attack
        // swing still animates on top.
        if (IGun.getIGunOrNull(entity.getMainHandItem()) == null) {
            this.rightArm.xRot = Mth.cos(limbSwing * 0.6662F + (float) Math.PI)
                    * 2.0F * limbSwingAmount * 0.5F;
            this.rightArm.yRot = 0.0F;
            this.rightArm.zRot = 0.0F;
            this.leftArm.xRot = Mth.cos(limbSwing * 0.6662F)
                    * 2.0F * limbSwingAmount * 0.5F;
            this.leftArm.yRot = 0.0F;
            this.leftArm.zRot = 0.0F;
        }

        // Melee swing on the vanilla attack timer
        float attackAnim = entity.getAttackAnim(ageInTicks);
        if (attackAnim > 0.0F) {
            net.minecraft.client.model.AnimationUtils.swingWeaponDown(
                    this.rightArm, this.leftArm, entity, attackAnim, ageInTicks);
        }
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
