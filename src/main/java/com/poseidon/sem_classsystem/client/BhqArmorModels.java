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

// Vanilla armor is a texture wrapped on the plain humanoid shell - flat no
// matter how well it is painted. This builds the same two shells vanilla uses
// (outer = helmet/chest/boots, inner = leggings) and bolts real boxes onto
// them: cap brim + crown + strap, vest plate + mag pouches + radio pack +
// shoulder pads + belt, thigh pockets + knee pads, boot soles + toe bumpers.
//
// UV: both atlases are 128x64. The top-left 64x32 stays the vanilla layout
// (base shell); every extra box owns a rectangle in the free area. The
// (u, v, w, h, d) values below MUST match gen_bhq_3d.py's box tables.
//
// Extras hang off the vanilla parts as children, so they inherit pose,
// visibility and scale: SEM's layer rigs/hides parts per slot and the
// pieces follow without any extra code.
public final class BhqArmorModels {

    private BhqArmorModels() {
    }

    public static final ModelLayerLocation OUTER = new ModelLayerLocation(
            new ResourceLocation(SemClassSystem.MODID, "bhq_armor_outer"), "main");
    public static final ModelLayerLocation INNER = new ModelLayerLocation(
            new ResourceLocation(SemClassSystem.MODID, "bhq_armor_inner"), "main");

    // Baked lazily on first use - the entity model set exists by then.
    private static HumanoidModel<LivingEntity> outerModel;
    private static HumanoidModel<LivingEntity> innerModel;

    // Legs wear the inner (tighter) shell like vanilla leggings; everything
    // else the outer one.
    public static HumanoidModel<LivingEntity> forSlot(EquipmentSlot slot) {
        if (slot == EquipmentSlot.LEGS) {
            if (innerModel == null) {
                innerModel = new HumanoidModel<>(Minecraft.getInstance().getEntityModels().bakeLayer(INNER));
            }
            return innerModel;
        }
        if (outerModel == null) {
            outerModel = new HumanoidModel<>(Minecraft.getInstance().getEntityModels().bakeLayer(OUTER));
        }
        return outerModel;
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

    // ---- outer shell: cap, vest, boots (deformation 1.0, like vanilla) ------
    public static LayerDefinition createOuter() {
        MeshDefinition mesh = HumanoidModel.createMesh(new CubeDeformation(1.0F), 0.0F);
        PartDefinition root = mesh.getRoot();

        // CAP - brim at the cap's lower edge, raised crown, rear adjuster
        // strap, velcro patch on the crown
        PartDefinition head = root.getChild("head");
        head.addOrReplaceChild("brim", box(64, 0, -4, -5, -9, 8, 1, 4), none());
        head.addOrReplaceChild("crown", box(64, 6, -3, -10, -3, 6, 1, 6), none());
        head.addOrReplaceChild("strap", box(96, 0, -3, -6, 5, 6, 2, 1), none());
        head.addOrReplaceChild("velcro", box(96, 4, -2, -11, -2, 4, 1, 3), none());

        // VEST - front plate, three mag pouches, cummerbund belt, radio pack
        PartDefinition body = root.getChild("body");
        body.addOrReplaceChild("plate", box(64, 14, -3, 1, -4, 6, 6, 1), none());
        body.addOrReplaceChild("pouch_l", box(96, 8, -4, 7, -4, 2, 3, 1), none());
        body.addOrReplaceChild("pouch_c", box(96, 8, -1, 7, -4, 2, 3, 1), none());
        body.addOrReplaceChild("pouch_r", box(96, 8, 2, 7, -4, 2, 3, 1), none());
        body.addOrReplaceChild("belt", box(88, 24, -5, 10, -4, 10, 2, 8), none());
        body.addOrReplaceChild("pack", box(80, 14, -3, 1, 3, 6, 7, 3), none());

        // SHOULDER PADS - sit on top of the sleeves
        root.getChild("right_arm").addOrReplaceChild("pad", box(64, 24, -4, -4, -3, 6, 2, 6), none());
        root.getChild("left_arm").addOrReplaceChild("pad", mirrored(64, 24, -2, -4, -3, 6, 2, 6), none());

        // BOOTS - wider lugged sole that reaches past the shell, toe bumper.
        // Widths are 5 (not 6) so the two soles never overlap and z-fight.
        root.getChild("right_leg").addOrReplaceChild("sole", box(64, 34, -3, 12, -4, 5, 1, 8), none());
        root.getChild("left_leg").addOrReplaceChild("sole", mirrored(64, 34, -2, 12, -4, 5, 1, 8), none());
        root.getChild("right_leg").addOrReplaceChild("toe", box(96, 34, -2, 10, -5, 4, 3, 1), none());
        root.getChild("left_leg").addOrReplaceChild("toe", mirrored(96, 34, -2, 10, -5, 4, 3, 1), none());

        return LayerDefinition.create(mesh, 128, 64);
    }

    // ---- inner shell: combat pants (deformation 0.5, like vanilla) ----------
    public static LayerDefinition createInner() {
        MeshDefinition mesh = HumanoidModel.createMesh(new CubeDeformation(0.5F), 0.0F);
        PartDefinition root = mesh.getRoot();

        root.getChild("body").addOrReplaceChild("belt", box(64, 10, -5, 10, -3, 10, 2, 6), none());

        // cargo pocket on the OUTER thigh, knee pad on the front of each knee
        root.getChild("right_leg").addOrReplaceChild("pocket", box(64, 0, -4, 2, -2, 2, 4, 4), none());
        root.getChild("left_leg").addOrReplaceChild("pocket", mirrored(64, 0, 2, 2, -2, 2, 4, 4), none());
        root.getChild("right_leg").addOrReplaceChild("kneepad", box(80, 0, -2, 5, -3, 4, 3, 1), none());
        root.getChild("left_leg").addOrReplaceChild("kneepad", mirrored(80, 0, -2, 5, -3, 4, 3, 1), none());

        return LayerDefinition.create(mesh, 128, 64);
    }
}
