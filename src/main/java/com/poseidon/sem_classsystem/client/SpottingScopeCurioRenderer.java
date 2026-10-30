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

        // Tactical elastic head-band / helmet strap wrapping around the brow
        // (sits slightly outside the 8x8 head and helmet rim so it's visible with or without a helmet)
        head.addOrReplaceChild("strap", CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-4.6F, -6.2F, -4.55F, 9.2F, 1.1F, 9.1F, new CubeDeformation(0.05F))
                        // Forehead NVG / optic mount bracket
                        .texOffs(24, 0).addBox(-1.1F, -6.9F, -5.25F, 2.2F, 1.6F, 0.85F, CubeDeformation.NONE)
                        // Swing-arm bar leading to the right eye (-X)
                        .texOffs(16, 0).addBox(-3.3F, -6.3F, -5.35F, 2.4F, 0.7F, 0.7F, CubeDeformation.NONE)
                        .texOffs(16, 0).addBox(-3.1F, -5.7F, -5.35F, 0.8F, 1.2F, 0.7F, CubeDeformation.NONE),
                PartPose.ZERO);

        // Right-eye (-X) Tactical Spotting Scope & Laser Rangefinder Pod
        head.addOrReplaceChild("scope_assembly", CubeListBuilder.create()
                        // Rear rubber eyecup right in front of the right eye
                        .texOffs(0, 0).addBox(-3.55F, -4.95F, -5.15F, 2.1F, 2.1F, 1.1F, CubeDeformation.NONE)
                        // Olive armored optic body tube
                        .texOffs(0, 8).addBox(-3.75F, -5.15F, -7.55F, 2.5F, 2.5F, 2.5F, CubeDeformation.NONE)
                        // Brass focus / magnification ring
                        .texOffs(16, 0).addBox(-3.9F, -5.3F, -8.35F, 2.8F, 2.8F, 0.85F, CubeDeformation.NONE)
                        // Front objective bell
                        .texOffs(0, 8).addBox(-3.85F, -5.25F, -9.45F, 2.7F, 2.7F, 1.15F, CubeDeformation.NONE)
                        // Glowing cyan/aqua objective lens face
                        .texOffs(16, 8).addBox(-3.65F, -5.05F, -9.55F, 2.3F, 2.3F, 0.15F, CubeDeformation.NONE)
                        // Side-mounted laser rangefinder module + red emitter
                        .texOffs(24, 0).addBox(-4.65F, -4.55F, -8.15F, 0.9F, 1.4F, 2.6F, CubeDeformation.NONE)
                        .texOffs(24, 8).addBox(-4.5F, -4.3F, -8.25F, 0.6F, 0.9F, 0.15F, CubeDeformation.NONE),
                PartPose.ZERO);

        return LayerDefinition.create(mesh, 32, 32);
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
