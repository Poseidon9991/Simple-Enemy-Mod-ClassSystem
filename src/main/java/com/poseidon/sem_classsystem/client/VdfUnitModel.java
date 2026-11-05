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
import net.nekoyuni.SimpleEnemyMod.entity.client.animation.ModAnimationsDefinitions;
import net.nekoyuni.SimpleEnemyMod.entity.client.animation.procedural.AdvancedWeaponPoseLayer;
import net.nekoyuni.SimpleEnemyMod.entity.client.animation.procedural.HeadTrackingLayer;

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
//
// The rest of the pass mirrors the PMC pipeline one-for-one so the
// militia reads like SEM's own soldiers: SEM's procedural layers in
// UnitAnimationConfig order (head tracking -> combat stance -> weapon
// pose), then the shared UnitProceduralPoses tail MixinUnitModelCrouch
// runs on the SEM models (locomotion polish, recoil/reload/bolt,
// use-item, prone, class crouch stances). CombatStateLayer itself can't
// be reused - it hard-casts AbstractUnit - so its blended replacement
// runs through UnitProceduralPoses.applyCombatStance instead.
public class VdfUnitModel extends HierarchicalModel<VdfUnitEntity> {

    public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    SemClassSystem.MODID, "vdfunit"), "main");

    private final ModelPart root;
    private final ModelPart unit;
    private final ModelPart head;
    private final ModelPart body;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
    private final ModelPart rightLeg;
    private final ModelPart leftLeg;
    // SEM's own procedural layers - the same instances PMC/BHQ/RU/US run
    // through UnitAnimationConfig (both accept a plain LivingEntity), so
    // the militia gets identical posing.
    private final AdvancedWeaponPoseLayer weaponPoseLayer;
    private final HeadTrackingLayer headTrackingLayer;

    public VdfUnitModel(ModelPart root) {
        this.root = root;
        this.unit = root.getChild("fakeRoot").getChild("unit");
        this.head = unit.getChild("head");
        this.body = unit.getChild("body");
        this.rightArm = unit.getChild("rightArm");
        this.leftArm = unit.getChild("leftArm");
        this.rightLeg = unit.getChild("rightLeg");
        this.leftLeg = unit.getChild("leftLeg");
        this.weaponPoseLayer = new AdvancedWeaponPoseLayer(head, rightArm, leftArm);
        this.headTrackingLayer = new HeadTrackingLayer(head, 30.0F, -25.0F, 45.0F);
    }

    @Override
    public ModelPart root() {
        return this.root;
    }

    @Override
    public void setupAnim(VdfUnitEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        this.root().getAllParts().forEach(ModelPart::resetPose);

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

        // Per-entity procedural state (WeakHashMap-keyed, same store the SEM
        // models feed through their mixins): combat/walk/crouch blends,
        // recoil impulse, reload + bolt progress - all frame-rate smooth.
        UnitProceduralAnimState animState = UnitProceduralAnimState.get(entity);
        animState.update(entity, ageInTicks);

        net.minecraft.world.item.ItemStack mainHand = entity.getMainHandItem();
        IGun iGun = IGun.getIGunOrNull(mainHand);
        boolean holdsGun = iGun != null;

        // Procedural layers in SEM's order. Head tracking owns the head rot
        // (mixin-smoothed); the combat-stance blend drops out-of-combat arms
        // to the low-ready patrol hold; the weapon pose layer re-poses arms
        // per gun class (rifle two-hand / minigun hip-fire / pistol) and
        // makes them follow the head's aim.
        this.headTrackingLayer.apply(this.root, entity, ageInTicks);
        if (holdsGun) {
            net.minecraft.resources.ResourceLocation gunId = iGun.getGunId(mainHand);
            if (gunId == null || !gunId.getPath().contains("minigun")) {
                UnitProceduralPoses.applyCombatStance(this.rightArm, this.leftArm,
                        animState.smoothCombatBlend());
            }
            this.weaponPoseLayer.apply(this.root, entity, ageInTicks);
        }

        // Tail pass - the same polish MixinUnitModelCrouch layers on the SEM
        // models. Skipped while the hurt/death clips own the rig, same gate.
        if (!entity.isAlive()
                || entity.hurtAnimationState.isStarted()
                || entity.deathAnimationState.isStarted()) {
            return;
        }
        UnitProceduralPoses.applyLocomotionPolish(this.unit, this.head, this.body,
                this.rightArm, this.leftArm, this.rightLeg, this.leftLeg,
                limbSwing, limbSwingAmount, ageInTicks,
                animState.smoothWalkBlend(), animState.smoothCombatBlend());
        if (holdsGun) {
            UnitProceduralPoses.applyGunProcedural(entity, animState,
                    this.body, this.head, this.rightArm, this.leftArm);
        }
        UnitProceduralPoses.applyUseItem(animState, ageInTicks,
                this.head, this.rightArm, this.leftArm);

        float prone = animState.smoothProneBlend();
        if (prone > 0.001F) {
            UnitProceduralPoses.applyPronePose(prone, this.head,
                    this.rightArm, this.leftArm, this.rightLeg, this.leftLeg);
        }
        if (prone <= 0.5F) {
            // Crouch (firing stance or night-watch sneak) -> smooth kneel,
            // same catalogue the PMC classes use. The militia's ASSIGNED
            // class drives the pick through getEffective (gun subclasses
            // like a pistol still override, same as SEM units).
            float crouch = animState.smoothCrouchBlend() * (1.0F - prone);
            if (crouch > 0.001F) {
                com.poseidon.sem_classsystem.faction.WeaponClass cls =
                        UnitProceduralPoses.effectiveClass(entity);
                if (cls == null) {
                    cls = com.poseidon.sem_classsystem.faction.WeaponClass.RIFLE;
                }
                UnitProceduralPoses.applyCrouchStance(crouch, cls, this.body, this.head,
                        this.rightArm, this.leftArm, this.rightLeg, this.leftLeg);
            }
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
