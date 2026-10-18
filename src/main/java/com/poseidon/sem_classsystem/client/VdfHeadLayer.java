// Client package - the villager face that makes the militia what it is
package com.poseidon.sem_classsystem.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.nekoyuni.SimpleEnemyMod.entity.client.ru_unit.RUunitModel;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;

// Renders a genuine villager head - nose, brim and all - on top of the
// soldier body. The head mesh comes from the vanilla VillagerModel layer
// (we grab its "head" part, which carries nose+brim children) and follows
// the SEM rig's own "head" bone so look/aim animations turn the face too.
// Skin is vanilla's plains villager texture - zero art needed.
public class VdfHeadLayer extends RenderLayer<RUunitEntity, RUunitModel<RUunitEntity>> {

    // vanilla plains-villager skin - the face the militia was born with
    private static final ResourceLocation VILLAGER_SKIN =
            ResourceLocation.withDefaultNamespace("textures/entity/villager/villager.png");

    private final ModelPart villagerHead;                               // head cube + nose + brim
    private ModelPart rigHead;                                          // SEM head bone, resolved lazily

    public VdfHeadLayer(RenderLayerParent<RUunitEntity, RUunitModel<RUunitEntity>> parent,
                        EntityModelSet modelSet) {
        super(parent);
        ModelPart villagerRoot = modelSet.bakeLayer(ModelLayers.VILLAGER);
        this.villagerHead = new VillagerModel<>(villagerRoot).root().getChild("head");
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       RUunitEntity entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        ModelPart head = rigHead();
        if (head == null) {
            return;                                                     // SEM restructured its rig - render without the face
        }
        poseStack.pushPose();
        head.translateAndRotate(poseStack);                             // enter the SEM head bone's space (neck pivot)
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutoutNoCull(VILLAGER_SKIN));
        this.villagerHead.render(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }

    // SEM rigs nest the skeleton under "unit" - RUunitModel wraps it in a
    // "fakeRoot" first while other rigs hang it straight off root. Handle
    // both shapes, cache the hit, and a missing bone disables the layer
    // instead of crashing the renderer.
    private ModelPart rigHead() {
        if (this.rigHead == null) {
            ModelPart root = getParentModel().root();
            ModelPart unit = root.hasChild("fakeRoot") ? root.getChild("fakeRoot")
                    : root.hasChild("unit") ? root : null;
            this.rigHead = unit != null && unit.hasChild("unit") && unit.getChild("unit").hasChild("head")
                    ? unit.getChild("unit").getChild("head")
                    : null;
        }
        return this.rigHead;
    }
}
