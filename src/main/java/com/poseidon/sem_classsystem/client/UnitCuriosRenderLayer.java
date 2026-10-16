package com.poseidon.sem_classsystem.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.compat.geckolib.GeckoCompat;
import net.nekoyuni.SimpleEnemyMod.compat.geckolib.GeckoCompatClient;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;
import top.theillusivec4.curios.api.client.ICurioRenderer;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.lang.reflect.Field;
import java.util.Map;

// Renders equipped Curios items (Artifacts, Charm/Totem of Undying, medals,
// belts, backpacks, goggles) on SEM units (PMC, BHQ, RU, US).
// - If the item has a registered ICurioRenderer, syncs the renderer's HumanoidModel
//   bones to the unit's HierarchicalModel bones and invokes ICurioRenderer.render.
// - If the item has no ICurioRenderer (e.g. Charm of Undying / Totem / 2D charm),
//   renders the 3D item badge directly on the matching body bone (chest, belt, back, head).
public class UnitCuriosRenderLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {

    private final RenderLayerParent<T, M> parent;
    private final HumanoidModel<T> dummyHumanoid;

    public UnitCuriosRenderLayer(RenderLayerParent<T, M> parent, HumanoidModel<T> dummyHumanoid) {
        super(parent);
        this.parent = parent;
        this.dummyHumanoid = dummyHumanoid;
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       T entity, float limbSwing, float limbSwingAmount,
                       float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {
        if (entity.isDeadOrDying()) {
            return;
        }
        M parentModel = this.getParentModel();
        if (!(parentModel instanceof HierarchicalModel<?> hModel)) {
            return;
        }
        ModelPart fakeRoot = hModel.root();
        if (!fakeRoot.hasChild("unit")) {
            return;
        }
        ModelPart unitBone = fakeRoot.getChild("unit");
        syncHumanoidFromUnit(unitBone, this.dummyHumanoid);

        RenderLayerParent<T, HumanoidModel<T>> humanoidProxy = new RenderLayerParent<>() {
            @Override
            public HumanoidModel<T> getModel() {
                return dummyHumanoid;
            }

            @Override
            public ResourceLocation getTextureLocation(T e) {
                return parent.getTextureLocation(e);
            }
        };

        CuriosApi.getCuriosInventory(entity).ifPresent(curiosInv -> {
            for (Map.Entry<String, ICurioStacksHandler> entry : curiosInv.getCurios().entrySet()) {
                String slotId = entry.getKey();
                ICurioStacksHandler stacksHandler = entry.getValue();
                if (!stacksHandler.isVisible()) {
                    continue;
                }
                IDynamicStackHandler stacks = stacksHandler.getStacks();
                IDynamicStackHandler cosmetics = stacksHandler.getCosmeticStacks();
                var renders = stacksHandler.getRenders();

                for (int i = 0; i < stacks.getSlots(); i++) {
                    boolean renderAllowed = i >= renders.size() || Boolean.TRUE.equals(renders.get(i));
                    if (!renderAllowed) {
                        continue;
                    }
                    ItemStack stack = i < cosmetics.getSlots() ? cosmetics.getStackInSlot(i) : ItemStack.EMPTY;
                    boolean cosmetic = true;
                    if (stack.isEmpty()) {
                        stack = stacks.getStackInSlot(i);
                        cosmetic = false;
                    }
                    if (stack.isEmpty()) {
                        continue;
                    }
                    // Armor items are already rendered by GeckoArmorLayerImpl
                    boolean isGeckoArmor = GeckoCompat.LOADED && GeckoCompatClient.isGeckoArmor(stack);
                    if (isGeckoArmor || stack.getItem() instanceof ArmorItem) {
                        continue;
                    }

                    SlotContext slotCtx = new SlotContext(slotId, entity, i, cosmetic, true);
                    var rendererOpt = CuriosRendererRegistry.getRenderer(stack.getItem());
                    if (rendererOpt.isPresent()) {
                        ICurioRenderer curioRenderer = rendererOpt.get();
                        // Two renderer families exist:
                        // - Bone-attached (Artifacts): embed a HumanoidModel field,
                        //   synced above, and need the unit-bone transform so the
                        //   bones' unit-local poses land correctly.
                        // - Model-space (Superb Warfare goggles/parachute): draw
                        //   their own geometry where Curios would on a player -
                        //   entity space. Applying the unit-bone transform here
                        //   shifts their head-height geometry down to the feet.
                        boolean boneSynced = syncEmbeddedHumanoidModels(curioRenderer, this.dummyHumanoid);
                        poseStack.pushPose();
                        if (boneSynced) {
                            fakeRoot.translateAndRotate(poseStack);
                            unitBone.translateAndRotate(poseStack);
                        }
                        try {
                            curioRenderer.render(stack, slotCtx, poseStack, humanoidProxy, buffer, packedLight,
                                    limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch);
                        } catch (Throwable ignored) {
                        }
                        poseStack.popPose();
                    } else {
                        renderFallbackBadge(poseStack, buffer, packedLight, entity, stack, slotId, i, fakeRoot, unitBone);
                    }
                }
            }
        });
    }

    private static void syncHumanoidFromUnit(ModelPart unitBone, HumanoidModel<?> target) {
        if (unitBone.hasChild("head")) target.head.copyFrom(unitBone.getChild("head"));
        if (unitBone.hasChild("body")) target.body.copyFrom(unitBone.getChild("body"));
        if (unitBone.hasChild("rightArm")) target.rightArm.copyFrom(unitBone.getChild("rightArm"));
        if (unitBone.hasChild("leftArm")) target.leftArm.copyFrom(unitBone.getChild("leftArm"));
        if (unitBone.hasChild("rightLeg")) target.rightLeg.copyFrom(unitBone.getChild("rightLeg"));
        if (unitBone.hasChild("leftLeg")) target.leftLeg.copyFrom(unitBone.getChild("leftLeg"));
        target.hat.copyFrom(target.head);
        target.crouching = false;
        target.riding = false;
        target.young = false;
    }

    // Artifacts and other Curios mods store a private HumanoidModel field inside
    // their ICurioRenderer and call ICurioRenderer.followBodyRotations (which
    // no-ops on non-HumanoidModel entity renderers). Copying our synced bones
    // directly into any HumanoidModel field on the renderer makes 3D Artifacts
    // animate 1:1 with the unit!
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean syncEmbeddedHumanoidModels(ICurioRenderer renderer, HumanoidModel<?> synced) {
        boolean found = false;
        try {
            for (Field f : renderer.getClass().getDeclaredFields()) {
                if (HumanoidModel.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object obj = f.get(renderer);
                    if (obj instanceof HumanoidModel hm) {
                        ((HumanoidModel) synced).copyPropertiesTo(hm);
                        hm.head.copyFrom(synced.head);
                        hm.body.copyFrom(synced.body);
                        hm.rightArm.copyFrom(synced.rightArm);
                        hm.leftArm.copyFrom(synced.leftArm);
                        hm.rightLeg.copyFrom(synced.rightLeg);
                        hm.leftLeg.copyFrom(synced.leftLeg);
                        found = true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return found;
    }

    private static void renderFallbackBadge(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                            LivingEntity entity, ItemStack stack, String slotId, int slotIdx,
                                            ModelPart fakeRoot, ModelPart unitBone) {
        poseStack.pushPose();
        fakeRoot.translateAndRotate(poseStack);
        unitBone.translateAndRotate(poseStack);

        switch (slotId) {
            case "head" -> {
                if (unitBone.hasChild("head")) {
                    unitBone.getChild("head").translateAndRotate(poseStack);
                }
                poseStack.translate(0.0F, -0.28F, -0.30F);
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
                poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));
                poseStack.scale(0.32F, 0.32F, 0.32F);
            }
            case "back" -> {
                if (unitBone.hasChild("body")) {
                    unitBone.getChild("body").translateAndRotate(poseStack);
                }
                poseStack.translate(0.0F, 0.30F, 0.22F);
                poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));
                poseStack.scale(0.42F, 0.42F, 0.42F);
            }
            case "belt" -> {
                if (unitBone.hasChild("body")) {
                    unitBone.getChild("body").translateAndRotate(poseStack);
                }
                float xOff = (slotIdx % 2 == 0 ? -0.14F : 0.14F);
                poseStack.translate(xOff, 0.65F, -0.20F);
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
                poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));
                poseStack.scale(0.24F, 0.24F, 0.24F);
            }
            case "hands", "bracelet", "ring" -> {
                if (unitBone.hasChild("leftArm")) {
                    unitBone.getChild("leftArm").translateAndRotate(poseStack);
                }
                poseStack.translate(0.06F, 0.45F, -0.15F);
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
                poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));
                poseStack.scale(0.22F, 0.22F, 0.22F);
            }
            default -> {
                // charm, necklace, curio, body -> render as a visible medal/charm on the front of the vest
                if (unitBone.hasChild("body")) {
                    unitBone.getChild("body").translateAndRotate(poseStack);
                }
                float xOff = -0.12F + (slotIdx % 3) * 0.12F;
                poseStack.translate(xOff, 0.22F, -0.24F);
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
                poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));
                poseStack.scale(0.26F, 0.26F, 0.26F);
            }
        }

        Minecraft.getInstance().getItemRenderer().renderStatic(
                entity,
                stack,
                ItemDisplayContext.FIXED,
                false,
                poseStack,
                buffer,
                entity.level(),
                packedLight,
                OverlayTexture.NO_OVERLAY,
                entity.getId() + slotIdx
        );
        poseStack.popPose();
    }
}
