// Client package - 3D geometry for the BHQ gear set
package com.poseidon.sem_classsystem.client;

import com.poseidon.sem_classsystem.SemClassSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;

// One model PER SLOT, each containing ONLY its own pieces. SEM's unit
// renderers do not hide armor parts per slot the way vanilla does, so a
// shared model made a lone vest paint the cap and boots regions too. With
// per-slot models that is structurally impossible: the chest model has no
// head or leg cubes at all, so no renderer can ever show the wrong piece.
//
// UV: 128x64 atlases. The top-left 64x32 is the vanilla layout for the
// shells that pieces DO use (vest body/sleeves, pant legs, boot legs);
// every extra box owns a rectangle in the free area. Values MUST match
// gen_bhq_3d.py's box tables.
public final class BhqArmorModels {

    private BhqArmorModels() {
    }

    private static final CubeDeformation OUTER_DEFORM = new CubeDeformation(1.0F);
    private static final CubeDeformation INNER_DEFORM = new CubeDeformation(0.5F);

    public static final ModelLayerLocation HEAD = location("head");
    public static final ModelLayerLocation CHEST = location("chest");
    public static final ModelLayerLocation LEGS = location("legs");
    public static final ModelLayerLocation FEET = location("feet");

    // Baked lazily on first use - the entity model set exists by then.
    private static HumanoidModel<LivingEntity> headModel;
    private static HumanoidModel<LivingEntity> chestModel;
    private static HumanoidModel<LivingEntity> legsModel;
    private static HumanoidModel<LivingEntity> feetModel;

    public static HumanoidModel<LivingEntity> forSlot(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> model(HEAD, "head");
            case CHEST -> model(CHEST, "chest");
            case LEGS -> model(LEGS, "legs");
            case FEET -> model(FEET, "feet");
            default -> null;
        };
    }

    private static HumanoidModel<LivingEntity> model(ModelLayerLocation loc, String field) {
        var set = Minecraft.getInstance().getEntityModels();
        return switch (field) {
            case "head" -> headModel != null ? headModel
                    : (headModel = new HumanoidModel<>(set.bakeLayer(loc)));
            case "chest" -> chestModel != null ? chestModel
                    : (chestModel = new HumanoidModel<>(set.bakeLayer(loc)));
            case "legs" -> legsModel != null ? legsModel
                    : (legsModel = new HumanoidModel<>(set.bakeLayer(loc)));
            default -> feetModel != null ? feetModel
                    : (feetModel = new HumanoidModel<>(set.bakeLayer(loc)));
        };
    }

    private static ModelLayerLocation location(String piece) {
        return new ModelLayerLocation(
                new ResourceLocation(SemClassSystem.MODID, "bhq_armor_" + piece), "main");
    }

    private static PartPose none() {
        return PartPose.ZERO;
    }

    private static CubeListBuilder box(int u, int v, int x, int y, int z, int w, int h, int d) {
        return CubeListBuilder.create().texOffs(u, v).addBox(x, y, z, w, h, d);
    }

    private static CubeListBuilder mirrored(int u, int v, int x, int y, int z, int w, int h, int d) {
        return CubeListBuilder.create().texOffs(u, v).mirror().addBox(x, y, z, w, h, d);
    }

    // Skeleton with all seven humanoid parts but NO cubes - each piece then
    // adds only the cubes it owns. Vanilla part pivots so posing works.
    private static PartDefinition bareSkeleton(MeshDefinition mesh) {
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("head", CubeListBuilder.create(), none());
        root.addOrReplaceChild("hat", CubeListBuilder.create(), none());
        root.addOrReplaceChild("body", CubeListBuilder.create(), none());
        root.addOrReplaceChild("right_arm", CubeListBuilder.create(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create(), PartPose.offset(5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(1.9F, 12.0F, 0.0F));
        return root;
    }

    // ---- CAP: brim, crown, strap, velcro, ear defenders --------------------
    public static LayerDefinition createHead() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = bareSkeleton(mesh);
        PartDefinition head = root.getChild("head");
        head.addOrReplaceChild("brim", box(64, 0, -4, -5, -9, 8, 1, 4), none());
        head.addOrReplaceChild("crown", box(64, 6, -3, -10, -3, 6, 1, 6), none());
        head.addOrReplaceChild("strap", box(96, 0, -3, -6, 5, 6, 2, 1), none());
        head.addOrReplaceChild("velcro", box(96, 4, -2, -11, -2, 4, 1, 3), none());
        head.addOrReplaceChild("ear_r", box(64, 44, 4, -4, -1, 2, 3, 2), none());
        head.addOrReplaceChild("ear_l", mirrored(64, 44, 4, -4, -1, 2, 3, 2), none());
        return LayerDefinition.create(mesh, 128, 64);
    }

    // ---- VEST: body shell + sleeves under the pads + gear ------------------
    public static LayerDefinition createChest() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = bareSkeleton(mesh);

        PartDefinition body = root.getChild("body");
        body.addOrReplaceChild("shell", CubeListBuilder.create()
                .texOffs(16, 16).addBox(-4, 0, -4, 8, 12, 4, OUTER_DEFORM), none());
        body.addOrReplaceChild("plate", box(64, 14, -3, 1, -4, 6, 6, 1), none());
        body.addOrReplaceChild("pouch_l", box(96, 8, -4, 7, -4, 2, 3, 1), none());
        body.addOrReplaceChild("pouch_c", box(96, 8, -1, 7, -4, 2, 3, 1), none());
        body.addOrReplaceChild("pouch_r", box(96, 8, 2, 7, -4, 2, 3, 1), none());
        body.addOrReplaceChild("belt", box(88, 24, -5, 10, -4, 10, 2, 8), none());
        body.addOrReplaceChild("pack", box(80, 14, -3, 1, 3, 6, 7, 3), none());

        PartDefinition armR = root.getChild("right_arm");
        armR.addOrReplaceChild("shell", CubeListBuilder.create()
                .texOffs(40, 16).addBox(-3, -2, -2, 4, 12, 4, OUTER_DEFORM), none());
        armR.addOrReplaceChild("pad", box(64, 24, -4, -4, -3, 6, 2, 6), none());
        PartDefinition armL = root.getChild("left_arm");
        armL.addOrReplaceChild("shell", CubeListBuilder.create()
                .texOffs(40, 16).addBox(-1, -2, -2, 4, 12, 4, OUTER_DEFORM), none());
        armL.addOrReplaceChild("pad", mirrored(64, 24, -2, -4, -3, 6, 2, 6), none());

        return LayerDefinition.create(mesh, 128, 64);
    }

    // ---- PANTS: waist shell + camo legs + pockets, pads, straps ------------
    public static LayerDefinition createLegs() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = bareSkeleton(mesh);

        PartDefinition body = root.getChild("body");
        body.addOrReplaceChild("shell", CubeListBuilder.create()
                .texOffs(16, 16).addBox(-4, 0, -4, 8, 12, 4, INNER_DEFORM), none());
        body.addOrReplaceChild("belt", box(64, 10, -5, 10, -3, 10, 2, 6), none());
        body.addOrReplaceChild("loop_l", box(88, 6, -3, 10, 2, 2, 1, 2), none());
        body.addOrReplaceChild("loop_r", box(88, 6, 1, 10, 2, 2, 1, 2), none());

        PartDefinition legR = root.getChild("right_leg");
        legR.addOrReplaceChild("shell", CubeListBuilder.create()
                .texOffs(0, 16).addBox(-2, 0, -2, 4, 12, 4, INNER_DEFORM), none());
        legR.addOrReplaceChild("pocket", box(64, 0, -4, 2, -2, 2, 4, 4), none());
        legR.addOrReplaceChild("kneepad", box(80, 0, -2, 5, -3, 4, 3, 1), none());
        legR.addOrReplaceChild("kneestrap", box(88, 0, -3, 9, -2, 6, 1, 4), none());
        PartDefinition legL = root.getChild("left_leg");
        legL.addOrReplaceChild("shell", CubeListBuilder.create()
                .texOffs(0, 16).addBox(-2, 0, -2, 4, 12, 4, INNER_DEFORM), none());
        legL.addOrReplaceChild("pocket", mirrored(64, 0, 2, 2, -2, 2, 4, 4), none());
        legL.addOrReplaceChild("kneepad", mirrored(80, 0, -2, 5, -3, 4, 3, 1), none());
        legL.addOrReplaceChild("kneestrap", mirrored(88, 0, -3, 9, -2, 6, 1, 4), none());

        return LayerDefinition.create(mesh, 128, 64);
    }

    // ---- BOOTS: painted leg shells + sole, laces, heel, pull loop ----------
    public static LayerDefinition createFeet() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = bareSkeleton(mesh);

        PartDefinition legR = root.getChild("right_leg");
        legR.addOrReplaceChild("shell", CubeListBuilder.create()
                .texOffs(0, 16).addBox(-2, 0, -2, 4, 12, 4, OUTER_DEFORM), none());
        legR.addOrReplaceChild("sole", box(64, 34, -3, 12, -4, 5, 1, 8), none());
        legR.addOrReplaceChild("toe", box(96, 34, -2, 10, -5, 4, 3, 1), none());
        legR.addOrReplaceChild("lace", box(96, 44, -1, 6, -4, 2, 6, 2), none());
        legR.addOrReplaceChild("heel", box(103, 44, -2, 9, 2, 4, 3, 2), none());
        legR.addOrReplaceChild("tab", box(96, 52, -1, 8, 2, 2, 2, 2), none());
        PartDefinition legL = root.getChild("left_leg");
        legL.addOrReplaceChild("shell", CubeListBuilder.create()
                .texOffs(0, 16).addBox(-2, 0, -2, 4, 12, 4, OUTER_DEFORM), none());
        legL.addOrReplaceChild("sole", mirrored(64, 34, -2, 12, -4, 5, 1, 8), none());
        legL.addOrReplaceChild("toe", mirrored(96, 34, -2, 10, -5, 4, 3, 1), none());
        legL.addOrReplaceChild("lace", mirrored(96, 44, -1, 6, -4, 2, 6, 2), none());
        legL.addOrReplaceChild("heel", mirrored(103, 44, -2, 9, 2, 4, 3, 2), none());
        legL.addOrReplaceChild("tab", mirrored(96, 52, -1, 8, 2, 2, 2, 2), none());

        return LayerDefinition.create(mesh, 128, 64);
    }
}
