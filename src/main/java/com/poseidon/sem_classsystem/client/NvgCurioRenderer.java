package com.poseidon.sem_classsystem.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.item.NvgMode;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;
import top.theillusivec4.curios.api.client.ICurioRenderer;

// Distinct 3D Curios head-slot renderers for all 3 NVG tiers:
//  - nvg_night:    Dual-tube PNV-31 style with glowing green phosphor lenses
//  - nvg_infrared: Asymmetric FLIR thermal imager pod + heatsink fins + amber lens
//  - nvg_smart:    4-tube splayed GPNVG-18 panoramic rig + glowing cyan AR lenses
// Uses a HumanoidModel field so UnitCuriosRenderLayer also animates it on units.
public final class NvgCurioRenderer implements ICurioRenderer {

    private static final ResourceLocation TEX_NIGHT = ResourceLocation.fromNamespaceAndPath(
            SemClassSystem.MODID, "textures/models/curios/nvg_night_3d.png");
    private static final ResourceLocation TEX_INFRARED = ResourceLocation.fromNamespaceAndPath(
            SemClassSystem.MODID, "textures/models/curios/nvg_infrared_3d.png");
    private static final ResourceLocation TEX_SMART = ResourceLocation.fromNamespaceAndPath(
            SemClassSystem.MODID, "textures/models/curios/nvg_smart_3d.png");

    // Reflected/synced by UnitCuriosRenderLayer as well as updated per-frame here.
    private final HumanoidModel<LivingEntity> model;
    private final HumanoidModel<LivingEntity> glowModel;
    private final ResourceLocation texture;

    private NvgCurioRenderer(LayerDefinition bodyLayer, LayerDefinition glowLayer, ResourceLocation texture) {
        this.model = new HumanoidModel<>(bodyLayer.bakeRoot());
        this.glowModel = new HumanoidModel<>(glowLayer.bakeRoot());
        this.texture = texture;
    }

    public static void register() {
        CuriosRendererRegistry.register(
                SemcsRegistries.NVG_NIGHT.get(),
                () -> new NvgCurioRenderer(
                        createBodyLayer(NvgMode.NIGHT_VISION),
                        createGlowLayer(NvgMode.NIGHT_VISION),
                        TEX_NIGHT));
        CuriosRendererRegistry.register(
                SemcsRegistries.NVG_INFRARED.get(),
                () -> new NvgCurioRenderer(
                        createBodyLayer(NvgMode.INFRARED),
                        createGlowLayer(NvgMode.INFRARED),
                        TEX_INFRARED));
        CuriosRendererRegistry.register(
                SemcsRegistries.NVG_SMART.get(),
                () -> new NvgCurioRenderer(
                        createBodyLayer(NvgMode.SMART),
                        createGlowLayer(NvgMode.SMART),
                        TEX_SMART));
    }

    @Override
    public <T extends LivingEntity, M extends EntityModel<T>> void render(
            ItemStack stack,
            SlotContext slotContext,
            PoseStack poseStack,
            RenderLayerParent<T, M> renderLayerParent,
            MultiBufferSource bufferSource,
            int light,
            float limbSwing,
            float limbSwingAmount,
            float partialTicks,
            float ageInTicks,
            float netHeadYaw,
            float headPitch) {
        LivingEntity entity = slotContext.entity();
        if (entity == null) {
            return;
        }

        this.model.prepareMobModel(entity, limbSwing, limbSwingAmount, partialTicks);
        this.model.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        ICurioRenderer.followBodyRotations(entity, this.model);

        this.glowModel.head.copyFrom(this.model.head);

        poseStack.pushPose();
        ICurioRenderer.translateIfSneaking(poseStack, entity);
        ICurioRenderer.rotateIfSneaking(poseStack, entity);

        VertexConsumer bodyConsumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(this.texture));
        this.model.head.render(poseStack, bodyConsumer, light, OverlayTexture.NO_OVERLAY,
                1.0F, 1.0F, 1.0F, 1.0F);

        VertexConsumer glowConsumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(this.texture));
        this.glowModel.head.render(poseStack, glowConsumer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                1.0F, 1.0F, 1.0F, 1.0F);

        poseStack.popPose();
    }

    private static MeshDefinition emptyHumanoidMesh() {
        MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
        PartDefinition root = mesh.getRoot();
        CubeListBuilder empty = CubeListBuilder.create();
        PartPose zero = PartPose.ZERO;
        root.addOrReplaceChild("head", empty, zero);
        root.addOrReplaceChild("hat", empty, zero);
        root.addOrReplaceChild("body", empty, zero);
        root.addOrReplaceChild("right_arm", empty, zero);
        root.addOrReplaceChild("left_arm", empty, zero);
        root.addOrReplaceChild("right_leg", empty, zero);
        root.addOrReplaceChild("left_leg", empty, zero);
        return mesh;
    }

    private static LayerDefinition createBodyLayer(NvgMode mode) {
        MeshDefinition mesh = emptyHumanoidMesh();
        PartDefinition root = mesh.getRoot();
        PartDefinition head;

        switch (mode) {
            case NIGHT_VISION -> {
                // Dual-tube PNV-31: strap (0,0), shroud (0,12), bridge (10,12), tubes (0,20), battery (12,20)
                head = root.addOrReplaceChild("head", CubeListBuilder.create()
                                .texOffs(0, 0).addBox(-4.5F, -6.5F, -4.5F, 9.0F, 1.0F, 9.0F)
                                .texOffs(0, 12).addBox(-1.5F, -7.8F, -5.3F, 3.0F, 3.0F, 1.0F)
                                .texOffs(10, 12).addBox(-2.5F, -4.7F, -6.2F, 5.0F, 1.0F, 1.0F)
                                .texOffs(24, 12).addBox(-0.5F, -6.5F, -6.0F, 1.0F, 2.0F, 1.0F)
                                .texOffs(0, 20).addBox(1.1F, -4.6F, -8.3F, 2.0F, 2.0F, 3.0F)
                                .texOffs(0, 20).addBox(-3.1F, -4.6F, -8.3F, 2.0F, 2.0F, 3.0F)
                                .texOffs(12, 20).addBox(-1.5F, -6.6F, 4.4F, 3.0F, 2.0F, 1.0F),
                        PartPose.ZERO);
            }
            case INFRARED -> {
                // FLIR thermal imager: strap (0,0), shroud (0,12), bridge (12,12),
                // wide thermal pod (0,20), heatsink (14,20), aux scope (24,20), temple pod (0,28)
                head = root.addOrReplaceChild("head", CubeListBuilder.create()
                                .texOffs(0, 0).addBox(-4.5F, -6.5F, -4.5F, 9.0F, 1.0F, 9.0F)
                                .texOffs(0, 12).addBox(-2.0F, -7.6F, -5.3F, 4.0F, 3.0F, 1.0F)
                                .texOffs(12, 12).addBox(-3.0F, -4.8F, -6.0F, 6.0F, 1.0F, 1.0F)
                                .texOffs(0, 20).addBox(0.3F, -4.9F, -8.2F, 3.0F, 2.0F, 3.0F)
                                .texOffs(14, 20).addBox(0.5F, -5.5F, -7.8F, 2.0F, 1.0F, 2.0F)
                                .texOffs(24, 20).addBox(-2.9F, -4.5F, -7.4F, 2.0F, 1.0F, 2.0F)
                                .texOffs(0, 28).addBox(4.1F, -6.2F, -2.0F, 1.0F, 2.0F, 4.0F)
                                .texOffs(12, 28).addBox(-1.5F, -6.5F, 4.4F, 3.0F, 2.0F, 1.0F),
                        PartPose.ZERO);
            }
            case SMART -> {
                // Quad-tube GPNVG-18 panoramic: strap (0,0), shroud (0,12), yoke (12,12),
                // inner tubes (0,20), AR sensor pod (12,20), battery (22,20), outer tubes (0,28)
                head = root.addOrReplaceChild("head", CubeListBuilder.create()
                                .texOffs(0, 0).addBox(-4.5F, -6.5F, -4.5F, 9.0F, 1.0F, 9.0F)
                                .texOffs(0, 12).addBox(-2.0F, -7.9F, -5.4F, 4.0F, 3.0F, 1.0F)
                                .texOffs(12, 12).addBox(-4.0F, -4.8F, -6.1F, 8.0F, 1.0F, 1.0F)
                                .texOffs(0, 20).addBox(0.8F, -4.6F, -8.4F, 2.0F, 2.0F, 3.0F)
                                .texOffs(0, 20).addBox(-2.8F, -4.6F, -8.4F, 2.0F, 2.0F, 3.0F)
                                .texOffs(12, 20).addBox(-4.9F, -5.8F, -3.8F, 1.0F, 2.0F, 3.0F)
                                .texOffs(22, 20).addBox(-2.0F, -6.8F, 4.4F, 4.0F, 2.0F, 1.0F),
                        PartPose.ZERO);
                head.addOrReplaceChild("outer_right", CubeListBuilder.create()
                                .texOffs(0, 28).addBox(-1.0F, -1.0F, -3.0F, 2.0F, 2.0F, 3.0F),
                        PartPose.offsetAndRotation(3.0F, -3.6F, -5.5F, 0.0F, -0.32F, 0.0F));
                head.addOrReplaceChild("outer_left", CubeListBuilder.create()
                                .texOffs(0, 28).addBox(-1.0F, -1.0F, -3.0F, 2.0F, 2.0F, 3.0F),
                        PartPose.offsetAndRotation(-3.0F, -3.6F, -5.5F, 0.0F, 0.32F, 0.0F));
            }
            default -> root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        }

        return LayerDefinition.create(mesh, 64, 64);
    }

    private static LayerDefinition createGlowLayer(NvgMode mode) {
        MeshDefinition mesh = emptyHumanoidMesh();
        PartDefinition root = mesh.getRoot();
        PartDefinition head;
        CubeDeformation inset = new CubeDeformation(-0.25F);

        switch (mode) {
            case NIGHT_VISION -> {
                // Bright phosphor-green objective lenses inset inside each 2x2 tube bezel
                head = root.addOrReplaceChild("head", CubeListBuilder.create()
                                .texOffs(40, 0).addBox(1.1F, -4.6F, -8.61F, 2.0F, 2.0F, 1.0F, inset)
                                .texOffs(40, 0).addBox(-3.1F, -4.6F, -8.61F, 2.0F, 2.0F, 1.0F, inset),
                        PartPose.ZERO);
            }
            case INFRARED -> {
                // Glowing germanium amber-orange thermal lens (right pod) + aux sensor (left)
                head = root.addOrReplaceChild("head", CubeListBuilder.create()
                                .texOffs(40, 0).addBox(0.3F, -4.9F, -8.51F, 3.0F, 2.0F, 1.0F, inset)
                                .texOffs(40, 6).addBox(-2.9F, -4.5F, -7.71F, 2.0F, 1.0F, 1.0F,
                                        new CubeDeformation(-0.20F)),
                        PartPose.ZERO);
            }
            case SMART -> {
                // 4 glowing cyan panoramic lenses inset inside all 4 tubes + rear battery LED strip
                head = root.addOrReplaceChild("head", CubeListBuilder.create()
                                .texOffs(40, 0).addBox(0.8F, -4.6F, -8.71F, 2.0F, 2.0F, 1.0F, inset)
                                .texOffs(40, 0).addBox(-2.8F, -4.6F, -8.71F, 2.0F, 2.0F, 1.0F, inset)
                                .texOffs(40, 6).addBox(-1.0F, -6.3F, 4.71F, 2.0F, 1.0F, 1.0F,
                                        new CubeDeformation(-0.15F)),
                        PartPose.ZERO);
                head.addOrReplaceChild("glow_outer_right", CubeListBuilder.create()
                                .texOffs(40, 0).addBox(-1.0F, -1.0F, -3.31F, 2.0F, 2.0F, 1.0F, inset),
                        PartPose.offsetAndRotation(3.0F, -3.6F, -5.5F, 0.0F, -0.32F, 0.0F));
                head.addOrReplaceChild("glow_outer_left", CubeListBuilder.create()
                                .texOffs(40, 0).addBox(-1.0F, -1.0F, -3.31F, 2.0F, 2.0F, 1.0F, inset),
                        PartPose.offsetAndRotation(-3.0F, -3.6F, -5.5F, 0.0F, 0.32F, 0.0F));
            }
            default -> root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.ZERO);
        }

        return LayerDefinition.create(mesh, 64, 64);
    }
}
