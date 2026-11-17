// Client-only - custom GeckoLib armor renderer for the Imperial Aerial Mage ("Tanya") set.
// Solves two cross-limb limitations of vanilla static armor models:
//   1. Hinges the front-mounted Iron-Cross computation pack outward dynamically when
//      either thigh swings forward or the wearer crouches, preventing leg clipping.
//   2. Tracks the live transformed 3D anchor of the box's nozzle ("hose_start" on torso)
//      and the right boot's ankle manifold ("hose_end" on right leg) every frame,
//      rendering an articulated 3D corrugated Bezier power conduit that stays connected
//      between torso and foot across walking, sprinting, crouching, and aerial flight.
package com.poseidon.sem_classsystem.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.poseidon.sem_classsystem.item.BhqArmorItem;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.EquipmentSlot;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoArmorRenderer;
import software.bernie.geckolib.util.RenderUtils;

public class TanyaArmorRenderer extends GeoArmorRenderer<BhqArmorItem> {

    private static final int PIPE_SEGMENTS = 16;
    private static final int PIPE_SIDES = 6;
    private static final float PIPE_RADIUS_PX = 0.40F;

    private boolean capturedStart;
    private boolean capturedEnd;
    private final Vector3f startGeoPos = new Vector3f();
    private final Vector3f endGeoPos = new Vector3f();
    private final Vector3f startGeoDir = new Vector3f();
    private final Vector3f endGeoDir = new Vector3f();

    public TanyaArmorRenderer() {
        super(new BhqArmorModel());
    }

    @Override
    protected void applyBoneVisibilityBySlot(EquipmentSlot currentSlot) {
        super.applyBoneVisibilityBySlot(currentSlot);
        if (currentSlot == EquipmentSlot.LEGS && this.baseModel != null) {
            setBoneVisible(this.body, this.baseModel.body.visible);
        }
    }

    @Override
    public void actuallyRender(PoseStack poseStack,
                               BhqArmorItem animatable,
                               BakedGeoModel model,
                               RenderType renderType,
                               MultiBufferSource bufferSource,
                               VertexConsumer buffer,
                               boolean isReRender,
                               float partialTick,
                               int packedLight,
                               int packedOverlay,
                               float red, float green, float blue, float alpha) {
        this.capturedStart = false;
        this.capturedEnd = false;

        if (this.currentSlot == EquipmentSlot.LEGS) {
            // Item drop / item frame renderer draws "static_hose"; worn armor
            // hides it in favor of the live kinematic conduit below.
            model.getBone("static_hose").ifPresent(b -> b.setHidden(true));
            applyFrontBoxHinge(model);
        }

        super.actuallyRender(poseStack, animatable, model, renderType, bufferSource, buffer,
                isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);

        if (this.currentSlot == EquipmentSlot.LEGS && this.capturedStart && this.capturedEnd) {
            VertexConsumer vc = buffer != null ? buffer : bufferSource.getBuffer(renderType);
            poseStack.pushPose();
            poseStack.translate(0.0F, 1.5F, 0.0F);
            poseStack.scale(-1.0F, -1.0F, 1.0F);
            renderArticulatedPipe(poseStack, vc, packedLight, packedOverlay, red, green, blue, alpha);
            poseStack.popPose();
        }
    }

    private void applyFrontBoxHinge(BakedGeoModel model) {
        model.getBone("tanya_front_box").ifPresent(box -> {
            float leftForward = this.leftLeg != null ? Math.max(0.0F, this.leftLeg.getRotX()) : 0.0F;
            float rightForward = this.rightLeg != null ? Math.max(0.0F, this.rightLeg.getRotX()) * 0.45F : 0.0F;
            float crouchLift = (this.baseModel != null && this.baseModel.crouching) ? 0.25F : 0.0F;
            float tilt = Math.min(0.68F, Math.max(Math.max(leftForward, rightForward * 0.8F), crouchLift) * 0.58F);
            box.setRotX(tilt);
        });
    }

    @Override
    public void renderRecursively(PoseStack poseStack,
                                  BhqArmorItem animatable,
                                  GeoBone bone,
                                  RenderType renderType,
                                  MultiBufferSource bufferSource,
                                  VertexConsumer buffer,
                                  boolean isReRender,
                                  float partialTick,
                                  int packedLight,
                                  int packedOverlay,
                                  float red, float green, float blue, float alpha) {
        if (this.currentSlot == EquipmentSlot.LEGS && this.modelRenderTranslations != null) {
            String name = bone.getName();
            if ("hose_start".equals(name)) {
                captureLocator(poseStack, bone, -0.82F, -0.52F, -0.22F, this.startGeoPos, this.startGeoDir);
                this.capturedStart = true;
                return;
            }
            if ("hose_end".equals(name)) {
                captureLocator(poseStack, bone, -0.30F, 0.90F, -0.32F, this.endGeoPos, this.endGeoDir);
                this.capturedEnd = true;
                return;
            }
        }

        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer,
                isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);
    }

    private void captureLocator(PoseStack poseStack, GeoBone bone,
                                float dirX, float dirY, float dirZ,
                                Vector3f outPos, Vector3f outDir) {
        poseStack.pushPose();
        RenderUtils.prepMatrixForBone(poseStack, bone);
        Matrix4f geoMat = RenderUtils.invertAndMultiplyMatrices(
                poseStack.last().pose(), this.modelRenderTranslations);
        geoMat.transformPosition(
                bone.getPivotX() / 16.0F,
                bone.getPivotY() / 16.0F,
                bone.getPivotZ() / 16.0F,
                outPos);
        geoMat.transformDirection(dirX, dirY, dirZ, outDir).normalize();
        poseStack.popPose();
    }

    private void renderArticulatedPipe(PoseStack poseStack, VertexConsumer vc,
                                       int packedLight, int packedOverlay,
                                       float r, float g, float b, float a) {
        float entityScale = (this.rightLeg != null && this.rightLeg.getScaleX() > 0.1F)
                ? this.rightLeg.getScaleX() : 1.0F;
        float dist = this.startGeoPos.distance(this.endGeoPos);
        if (dist < 1.0e-4F || dist > 3.5F * entityScale) {
            return;
        }

        float ctrl = Math.max((1.6F / 16.0F) * entityScale, dist * 0.44F);
        Vector3f p0 = this.startGeoPos;
        Vector3f p3 = this.endGeoPos;
        Vector3f p1 = new Vector3f(p0).fma(ctrl, this.startGeoDir)
                .add(0.0F, -(0.6F / 16.0F) * entityScale, -(0.35F / 16.0F) * entityScale);
        Vector3f p2 = new Vector3f(p3).fma(ctrl * 0.85F, this.endGeoDir);

        float baseRadius = (PIPE_RADIUS_PX / 16.0F) * entityScale;
        Vector3f[] centers = new Vector3f[PIPE_SEGMENTS + 1];
        Vector3f[][] rings = new Vector3f[PIPE_SEGMENTS + 1][PIPE_SIDES];

        Vector3f ptPrev = new Vector3f();
        Vector3f ptNext = new Vector3f();
        Vector3f tang = new Vector3f();
        Vector3f n1 = new Vector3f();
        Vector3f n2 = new Vector3f();
        Vector3f upRef = new Vector3f();

        for (int s = 0; s <= PIPE_SEGMENTS; s++) {
            float t = (float) s / (float) PIPE_SEGMENTS;
            Vector3f center = evalBezier(p0, p1, p2, p3, t, new Vector3f());
            centers[s] = center;

            evalBezier(p0, p1, p2, p3, Math.max(0.0F, t - 0.02F), ptPrev);
            evalBezier(p0, p1, p2, p3, Math.min(1.0F, t + 0.02F), ptNext);
            tang.set(ptNext).sub(ptPrev);
            if (tang.lengthSquared() < 1.0e-8F) {
                tang.set(0.0F, -1.0F, 0.0F);
            } else {
                tang.normalize();
            }

            if (Math.abs(tang.z) < 0.9F) {
                upRef.set(0.0F, 0.0F, -1.0F);
            } else {
                upRef.set(0.0F, 1.0F, 0.0F);
            }
            tang.cross(upRef, n1).normalize();
            tang.cross(n1, n2).normalize();

            float rad = baseRadius * ((s == 0 || s == PIPE_SEGMENTS) ? 1.18F : 1.0F);
            for (int k = 0; k < PIPE_SIDES; k++) {
                float ang = (float) (2.0 * Math.PI * k / PIPE_SIDES);
                float ca = (float) Math.cos(ang) * rad;
                float sa = (float) Math.sin(ang) * rad;
                rings[s][k] = new Vector3f(center).fma(ca, n1).fma(sa, n2);
            }
        }

        Matrix4f pose = poseStack.last().pose();
        Matrix3f normalMat = poseStack.last().normal();
        float u0 = 242.0F / 256.0F;
        float u1 = 252.0F / 256.0F;
        Vector3f normA = new Vector3f();
        Vector3f normB = new Vector3f();

        for (int s = 0; s < PIPE_SEGMENTS; s++) {
            int row = (s == 0 || s == PIPE_SEGMENTS - 1) ? 254 : (243 + (s % 8));
            float v0 = row / 256.0F;
            float v1 = (row + 0.85F) / 256.0F;

            for (int k = 0; k < PIPE_SIDES; k++) {
                int k2 = (k + 1) % PIPE_SIDES;
                Vector3f a0 = rings[s][k];
                Vector3f a1 = rings[s][k2];
                Vector3f b1 = rings[s + 1][k2];
                Vector3f b0 = rings[s + 1][k];

                normA.set(a0).sub(centers[s]).normalize();
                normB.set(b0).sub(centers[s + 1]).normalize();

                emitVertex(vc, pose, normalMat, a0, u0, v0, normA, packedLight, packedOverlay, r, g, b, a);
                emitVertex(vc, pose, normalMat, a1, u1, v0, normA, packedLight, packedOverlay, r, g, b, a);
                emitVertex(vc, pose, normalMat, b1, u1, v1, normB, packedLight, packedOverlay, r, g, b, a);
                emitVertex(vc, pose, normalMat, b0, u0, v1, normB, packedLight, packedOverlay, r, g, b, a);
            }
        }
    }

    private static Vector3f evalBezier(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3,
                                       float t, Vector3f out) {
        float u = 1.0F - t;
        float uu = u * u;
        float tt = t * t;
        float w0 = uu * u;
        float w1 = 3.0F * uu * t;
        float w2 = 3.0F * u * tt;
        float w3 = tt * t;
        out.set(
                w0 * p0.x + w1 * p1.x + w2 * p2.x + w3 * p3.x,
                w0 * p0.y + w1 * p1.y + w2 * p2.y + w3 * p3.y,
                w0 * p0.z + w1 * p1.z + w2 * p2.z + w3 * p3.z
        );
        return out;
    }

    private static void emitVertex(VertexConsumer vc, Matrix4f pose, Matrix3f normalMat,
                                   Vector3f pos, float u, float v, Vector3f localNorm,
                                   int packedLight, int packedOverlay,
                                   float r, float g, float b, float a) {
        vc.vertex(pose, pos.x, pos.y, pos.z)
                .color(r, g, b, a)
                .uv(u, v)
                .overlayCoords(packedOverlay)
                .uv2(packedLight)
                .normal(normalMat, localNorm.x, localNorm.y, localNorm.z)
                .endVertex();
    }
}
