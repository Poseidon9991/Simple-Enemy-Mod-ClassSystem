// Client-only - renders armor pieces as their real 3D GeckoLib models when
// dropped, framed, or held, falling back to the flat sprite in GUIs.
package com.poseidon.sem_classsystem.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.item.BhqArmorItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.renderer.GeoItemRenderer;

// The geo files are authored in entity space (helmet cubes sit ~24-34px up,
// vests ~12-25px, pants ~0-14px, boots ~0-6px). GeoItemRenderer places the
// model origin at block center plus a 0.51 lift, so each piece is shifted
// down by its own mid-height to rest like a normal dropped item. The flat
// sprite is kept for GUI slots where a crisp icon reads better.
public class SemcsArmorItemRenderer extends GeoItemRenderer<BhqArmorItem> {

    private static final float SCALE = 0.65f;
    private static final float REST_Y = 0.35f;

    public SemcsArmorItemRenderer() {
        super(new BhqArmorModel());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
                             MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (context == ItemDisplayContext.GUI) {
            renderFlat(stack, context, poseStack, bufferSource, LightTexture.FULL_BRIGHT, packedOverlay);
            return;
        }
        float midPx = switch (((ArmorItem) stack.getItem()).getType()) {
            case HELMET -> 29f;
            case CHESTPLATE -> 18.5f;
            case LEGGINGS -> 7f;
            case BOOTS -> 3f;
        };
        float scale = switch (context) {
            case FIXED -> 0.72f;
            case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND -> 0.52f;
            case THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> 0.55f;
            default -> SCALE;
        };
        float restY = switch (context) {
            case FIXED -> 0.48f;
            case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND -> 0.42f;
            case THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> 0.38f;
            default -> REST_Y;
        };
        float xz = 0.5f - 0.5f * scale;
        float y = restY - scale * (0.51f + midPx / 16f);
        poseStack.pushPose();
        poseStack.translate(xz, y, xz);
        if (context == ItemDisplayContext.FIXED) {
            poseStack.translate(0.5f * scale, 0.0f, 0.5f * scale);
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0f));
            poseStack.translate(-0.5f * scale, 0.0f, -0.5f * scale);
        }
        poseStack.scale(scale, scale, scale);
        super.renderByItem(stack, context, poseStack, bufferSource, packedLight, packedOverlay);
        poseStack.popPose();
    }

    private void renderFlat(ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
                            MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        String piece = ((BhqArmorItem) stack.getItem()).piece();
        BakedModel flat = Minecraft.getInstance().getModelManager().getModel(
                new ModelResourceLocation(SemClassSystem.MODID, piece + "_flat", "inventory"));
        // Ensure flat GUI lighting is active so builtin/entity outer models
        // never shade the 2D sprite with oblique 3D directional light.
        Lighting.setupForFlatItems();
        // Undo the -0.5 corner shift ItemRenderer applies for builtin/entity
        // models so the sprite lands back in slot space.
        poseStack.pushPose();
        poseStack.translate(0.5f, 0.5f, 0.5f);
        Minecraft.getInstance().getItemRenderer().render(stack, context, false, poseStack,
                bufferSource, packedLight, packedOverlay, flat);
        poseStack.popPose();
    }
}
