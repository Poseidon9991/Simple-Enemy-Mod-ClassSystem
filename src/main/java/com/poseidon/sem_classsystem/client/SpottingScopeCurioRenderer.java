package com.poseidon.sem_classsystem.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;
import top.theillusivec4.curios.api.client.ICurioRenderer;

// Worn 3D head-mounted Spotting Scope / Laser Rangefinder Monocular.
// Uses a HumanoidModel field so both vanilla Players (via ICurioRenderer)
// and SEM / VDF units (via UnitCuriosRenderLayer.syncEmbeddedHumanoidModels)
// animate the scope 1:1 with the wearer's head rotation and helmets.
public final class SpottingScopeCurioRenderer implements ICurioRenderer {

    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            SemClassSystem.MODID, "textures/models/curios/spotting_scope_3d.png");

    private final HumanoidModel<LivingEntity> model;

    public SpottingScopeCurioRenderer() {
        this.model = new HumanoidModel<>(createLayer().bakeRoot());
    }

    public static void register() {
        CuriosRendererRegistry.register(SemcsRegistries.SPOTTING_SCOPE.get(), SpottingScopeCurioRenderer::new);
    }

    private static LayerDefinition createLayer() {
        MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
        PartDefinition root = mesh.getRoot();
        PartDefinition head = root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);

        // Whole-number cube sizes + a generated 64x64 atlas: every face maps
        // to its own texel block (fractional sizes / a 32x32 layer vs a 64x64
        // PNG is what corrupted the old render). Head-local space, -Z = front,
        // -X = wearer's right eye.
        head.addOrReplaceChild("scope", CubeListBuilder.create()
                // Head-band wrapping the brow
                .texOffs(0, 0).addBox(-4.0F, -6.0F, -4.0F, 8.0F, 1.0F, 8.0F, new CubeDeformation(0.5F))
                // Forehead optic mount + swing arm to the right eye
                .texOffs(32, 0).addBox(-1.0F, -7.0F, -6.0F, 2.0F, 2.0F, 2.0F, CubeDeformation.NONE)
                .texOffs(40, 0).addBox(-3.0F, -7.0F, -6.0F, 2.0F, 1.0F, 2.0F, CubeDeformation.NONE)
                // Eyecup, olive optic tube, brass focus ring, objective bell (cyan lens on its front face)
                .texOffs(48, 0).addBox(-3.0F, -4.0F, -5.0F, 2.0F, 2.0F, 1.0F, CubeDeformation.NONE)
                .texOffs(0, 9).addBox(-3.0F, -5.0F, -9.0F, 3.0F, 3.0F, 4.0F, CubeDeformation.NONE)
                .texOffs(14, 9).addBox(-3.0F, -5.0F, -8.0F, 3.0F, 3.0F, 1.0F, new CubeDeformation(0.4F))
                .texOffs(22, 9).addBox(-3.0F, -5.0F, -10.0F, 3.0F, 3.0F, 1.0F, new CubeDeformation(0.5F))
                // Side laser rangefinder module (red emitter on its front face)
                .texOffs(30, 9).addBox(-5.0F, -4.0F, -9.0F, 2.0F, 2.0F, 3.0F, CubeDeformation.NONE),
                PartPose.ZERO);

        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public <T extends LivingEntity, M extends EntityModel<T>> void render(
            ItemStack stack, SlotContext slotContext, PoseStack poseStack,
            RenderLayerParent<T, M> renderLayerParent, MultiBufferSource buffer,
            int light, float limbSwing, float limbSwingAmount,
            float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {
        LivingEntity entity = slotContext.entity();
        if (renderLayerParent.getModel() instanceof HumanoidModel<?> parentHumanoid) {
            this.model.head.copyFrom(parentHumanoid.head);
        } else {
            ICurioRenderer.followHeadRotations(entity, this.model.head);
        }

        VertexConsumer consumer = ItemRenderer.getArmorFoilBuffer(
                buffer, RenderType.armorCutoutNoCull(TEXTURE), false, stack.hasFoil());
        this.model.head.render(poseStack, consumer, light, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
    }
}
