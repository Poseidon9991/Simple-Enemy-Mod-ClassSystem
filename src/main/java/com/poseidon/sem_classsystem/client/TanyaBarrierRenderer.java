// Client-only - renders the 3D pixelated Minecraft-style Type-95 Hexagonal
// Mana Barrier, minimal HUD indicator, and client-side cyan spell-bullet tracers.
package com.poseidon.sem_classsystem.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.event.TanyaFlightEvents;
import com.poseidon.sem_classsystem.event.TanyaMagicCombatEvents;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

@Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class TanyaBarrierRenderer {

    public static final ResourceLocation BARRIER_TEX = ResourceLocation.fromNamespaceAndPath(
            SemClassSystem.MODID, "textures/entity/tanya_mana_barrier.png");

    private TanyaBarrierRenderer() {
    }

    // ---- 1. Client-side Spell Bullet Tracer Sync ----------------------------
    @SubscribeEvent
    public static void onClientBulletSpawn(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() || !(event.getEntity() instanceof EntityKineticBullet bullet)) {
            return;
        }
        if (bullet.getOwner() instanceof Player owner) {
            ClientTanyaState.Entry state = ClientTanyaState.get(owner.getId());
            if (state != null && state.infusionActive
                    && state.manaTicks >= ClassSystemConfig.tanyaSpellBulletManaCost()) {
                CompoundTag bTag = bullet.getPersistentData();
                bTag.putBoolean(TanyaMagicCombatEvents.NBT_MANA_BULLET, true);
                bTag.putIntArray(EntityKineticBullet.TRACER_COLOR_OVERRIDER_KEY, new int[]{24, 252, 240});
                bTag.putFloat(EntityKineticBullet.TRACER_SIZE_OVERRIDER_KEY, 2.35F);
            }
        }
    }

    // ---- 2. Pixelated 3D Hexagonal Energy Shield Rendering ------------------
    @SubscribeEvent
    public static void onRenderWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 camPos = event.getCamera().getPosition();
        float partialTick = event.getPartialTick();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        long nowMs = System.currentTimeMillis();

        RenderType barrierType = RenderType.entityTranslucentEmissive(BARRIER_TEX);
        VertexConsumer vc = null;

        for (Player player : mc.level.players()) {
            if (!player.isAlive() || player.isSpectator()
                    || !ClientTanyaState.isBarrierActive(player.getId())) {
                continue;
            }
            if (vc == null) {
                vc = buffers.getBuffer(barrierType);
            }
            ClientTanyaState.Entry state = ClientTanyaState.get(player.getId());
            float hitPulse = 0.0F;
            if (state != null && state.lastHitTimeMs > 0L) {
                long age = nowMs - state.lastHitTimeMs;
                if (age >= 0L && age < 320L) {
                    hitPulse = 1.0F - (age / 320.0F);
                }
            }

            if (player == mc.player && mc.options.getCameraType().isFirstPerson()) {
                renderFirstPersonRim(poseStack, vc, hitPulse, nowMs);
            } else {
                renderThirdPersonHexBarrier(poseStack, vc, camPos, player, partialTick, hitPulse, nowMs);
            }
        }

        if (vc != null) {
            buffers.endBatch(barrierType);
        }
    }

    private static void renderThirdPersonHexBarrier(PoseStack poseStack,
                                                    VertexConsumer vc,
                                                    Vec3 camPos,
                                                    Player player,
                                                    float partialTick,
                                                    float hitPulse,
                                                    long nowMs) {
        double px = Mth.lerp(partialTick, player.xo, player.getX());
        double py = Mth.lerp(partialTick, player.yo, player.getY()) + player.getBbHeight() * 0.54D;
        double pz = Mth.lerp(partialTick, player.zo, player.getZ());

        float yawDeg = Mth.lerp(partialTick, player.yHeadRotO, player.yHeadRot);
        float yawRad = (float) Math.toRadians(yawDeg);
        Vector3f forward = new Vector3f(-Mth.sin(yawRad), 0.0F, Mth.cos(yawRad)).normalize();
        Vector3f right = new Vector3f(forward.z, 0.0F, -forward.x).normalize();
        Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F);

        float timeSec = (nowMs % 100000L) / 1000.0F;
        float breathe = 0.03F * Mth.sin(timeSec * 3.2F);
        float shieldDist = 0.98F + breathe + hitPulse * 0.12F;

        Vector3f domeCenter = new Vector3f(
                (float) (px - camPos.x) + forward.x * shieldDist,
                (float) (py - camPos.y),
                (float) (pz - camPos.z) + forward.z * shieldDist
        );

        poseStack.pushPose();
        Matrix4f mat = poseStack.last().pose();
        Matrix3f nMat = poseStack.last().normal();

        float tileHalfSize = 0.42F;
        float rTint = 0.85F + hitPulse * 0.15F;
        float gTint = 1.0F;
        float bTint = 1.0F;
        float alphaCenter = Math.min(1.0F, 0.78F + hitPulse * 0.22F);

        // Center pixel-art hex tile
        drawHexQuad(mat, nMat, vc, domeCenter, right, up, forward, tileHalfSize,
                rTint, gTint, bTint, alphaCenter);

        // 6 surrounding tilted honeycomb pixel-art tiles
        float ringSpacing = tileHalfSize * 1.66F;
        for (int i = 0; i < 6; i++) {
            float angle = (float) (Math.PI / 6.0D + i * (Math.PI / 3.0D));
            float ca = Mth.cos(angle);
            float sa = Mth.sin(angle);

            Vector3f cellCenter = new Vector3f(domeCenter)
                    .fma(ca * ringSpacing, right)
                    .fma(sa * ringSpacing, up)
                    .fma(-0.18F, forward);

            Vector3f radial = new Vector3f().fma(ca, right).fma(sa, up).normalize();
            Vector3f cellNormal = new Vector3f(forward).mul(0.95F).fma(0.31F, radial).normalize();
            Vector3f cellRight = new Vector3f(up).cross(cellNormal).normalize();
            Vector3f cellUp = new Vector3f(cellNormal).cross(cellRight).normalize();

            float shimmer = 0.08F * Mth.sin(timeSec * 4.5F + i * 1.05F);
            float alphaRing = Mth.clamp(0.68F + shimmer + hitPulse * 0.28F, 0.40F, 1.0F);
            drawHexQuad(mat, nMat, vc, cellCenter, cellRight, cellUp, cellNormal, tileHalfSize * 0.92F,
                    rTint, gTint, bTint, alphaRing);
        }

        poseStack.popPose();
    }

    private static void renderFirstPersonRim(PoseStack poseStack,
                                             VertexConsumer vc,
                                             float hitPulse,
                                             long nowMs) {
        Minecraft mc = Minecraft.getInstance();
        float yawRad = (float) Math.toRadians(mc.gameRenderer.getMainCamera().getYRot());
        float pitchRad = (float) Math.toRadians(mc.gameRenderer.getMainCamera().getXRot());

        Vector3f forward = new Vector3f(
                -Mth.sin(yawRad) * Mth.cos(pitchRad),
                -Mth.sin(pitchRad),
                Mth.cos(yawRad) * Mth.cos(pitchRad)).normalize();
        Vector3f right = new Vector3f(Mth.cos(yawRad), 0.0F, Mth.sin(yawRad)).normalize();
        Vector3f up = new Vector3f(right).cross(forward).normalize();
        Vector3f backNormal = new Vector3f(forward).negate();

        poseStack.pushPose();
        Matrix4f mat = poseStack.last().pose();
        Matrix3f nMat = poseStack.last().normal();
        float alpha = Math.min(0.85F, 0.42F + hitPulse * 0.43F);

        for (int side = -1; side <= 1; side += 2) {
            Vector3f center = new Vector3f(forward).mul(0.85F)
                    .fma(side * 0.56F, right)
                    .fma(-0.24F, up);
            drawHexQuad(mat, nMat, vc, center, right, up, backNormal, 0.17F,
                    0.85F + hitPulse * 0.15F, 1.0F, 1.0F, alpha);
        }

        poseStack.popPose();
    }

    private static void drawHexQuad(Matrix4f mat, Matrix3f nMat, VertexConsumer vc,
                                    Vector3f center, Vector3f axisX, Vector3f axisY, Vector3f normal,
                                    float halfSize,
                                    float r, float g, float b, float alpha) {
        Vector3f bl = new Vector3f(center).fma(-halfSize, axisX).fma(-halfSize, axisY);
        Vector3f br = new Vector3f(center).fma(halfSize, axisX).fma(-halfSize, axisY);
        Vector3f tr = new Vector3f(center).fma(halfSize, axisX).fma(halfSize, axisY);
        Vector3f tl = new Vector3f(center).fma(-halfSize, axisX).fma(halfSize, axisY);

        emitTexturedQuad(mat, nMat, vc, bl, br, tr, tl, normal, r, g, b, alpha);
        // Double-sided reverse winding so players see it cleanly from inside & outside
        Vector3f revNorm = new Vector3f(normal).negate();
        emitTexturedQuad(mat, nMat, vc, tl, tr, br, bl, revNorm, r, g, b, alpha * 0.65F);
    }

    private static void emitTexturedQuad(Matrix4f mat, Matrix3f nMat, VertexConsumer vc,
                                         Vector3f v0, Vector3f v1, Vector3f v2, Vector3f v3,
                                         Vector3f norm,
                                         float r, float g, float b, float alpha) {
        vc.vertex(mat, v0.x, v0.y, v0.z).color(r, g, b, alpha).uv(0.0F, 1.0F)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal(nMat, norm.x, norm.y, norm.z).endVertex();
        vc.vertex(mat, v1.x, v1.y, v1.z).color(r, g, b, alpha).uv(1.0F, 1.0F)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal(nMat, norm.x, norm.y, norm.z).endVertex();
        vc.vertex(mat, v2.x, v2.y, v2.z).color(r, g, b, alpha).uv(1.0F, 0.0F)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal(nMat, norm.x, norm.y, norm.z).endVertex();
        vc.vertex(mat, v3.x, v3.y, v3.z).color(r, g, b, alpha).uv(0.0F, 0.0F)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal(nMat, norm.x, norm.y, norm.z).endVertex();
    }

    // ---- 3. Minimal Type-95 Mana & Ability HUD Indicator ---------------------
    @SubscribeEvent
    public static void onRenderHud(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.options.hideGui || !TanyaFlightEvents.hasFullTanyaSet(player)) {
            return;
        }
        ClientTanyaState.Entry state = ClientTanyaState.get(player.getId());
        if (state == null) {
            return;
        }

        GuiGraphics gg = event.getGuiGraphics();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        int x = sw / 2 + 96;
        int y = sh - 22;
        int barW = 86;

        gg.fill(x - 2, y - 12, x + barW + 2, y + 10, 0x9910141A);
        gg.fill(x, y + 1, x + barW, y + 6, 0xFF1A232B);

        float ratio = Mth.clamp((float) state.manaTicks / (float) Math.max(1, state.maxManaTicks), 0.0F, 1.0F);
        int fillW = Math.round(barW * ratio);
        int barCol = ratio < 0.15F ? 0xFFFF4D4D : (state.barrierActive ? 0xFF42FFF6 : 0xFF1CE2D8);
        if (fillW > 0) {
            gg.fill(x, y + 1, x + fillW, y + 6, barCol);
        }

        String bTag = state.barrierActive ? "§b[B]SHIELD" : "§7[B]SHIELD";
        String nTag = state.infusionActive ? "§b[N]SPELL" : "§7[N]SPELL";
        gg.drawString(mc.font, bTag + " " + nTag, x, y - 9, 0xFFFFFF, true);
    }
}
