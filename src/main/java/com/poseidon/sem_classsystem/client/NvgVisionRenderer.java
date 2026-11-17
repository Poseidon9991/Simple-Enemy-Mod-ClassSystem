package com.poseidon.sem_classsystem.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.block.CommsRadarBlockEntity;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.entity.TerroristUnitEntity;
import com.poseidon.sem_classsystem.entity.VdfUnitEntity;
import com.poseidon.sem_classsystem.faction.UnitClass;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.poseidon.sem_classsystem.item.NvgMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

// Client-only HUD + 3D world-space optic rendering for the 3 Curios head-slot NVGs:
//  - NIGHT_VISION: Phosphor green HUD tint/vignette + Night Vision effect
//  - INFRARED:     Cool indigo FLIR background + live thermography readout + 3D heat glows
//  - SMART:        Slate-cyan AR HUD + 3D enemy unit location boxes & tactical callout tags
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class NvgVisionRenderer {

    private static final double INFRARED_ENTITY_RANGE = 72.0D;
    private static final double SMART_UNIT_RANGE = 96.0D;
    private static final double SMART_MOB_RANGE = 44.0D;
    private static final int HEAT_BLOCK_SCAN_RADIUS = 12;

    private static final List<BlockPos> CACHED_HEAT_BLOCKS = new ArrayList<>();
    private static long lastHeatScanTick = -100L;

    // Lightweight per-frame scan summary shared with HUD telemetry
    private static int smartContactCount = 0;
    private static int smartNearestDist = -1;
    private static float smartNearestYaw = 0.0F;

    private NvgVisionRenderer() {
    }

    // -------------------------------------------------------------------------
    // HUD Optic Overlays (rendered beneath hotbar/hearts/crosshair)
    // -------------------------------------------------------------------------
    @SubscribeEvent
    public static void onRenderGuiPre(RenderGuiOverlayEvent.Pre event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null || mc.screen != null) {
            return;
        }
        NvgMode equipped = ClientNvgState.getEquippedMode(player);
        if (equipped == NvgMode.NONE) {
            return;
        }

        GuiGraphics g = event.getGuiGraphics();
        int w = event.getWindow().getGuiScaledWidth();
        int h = event.getWindow().getGuiScaledHeight();

        if (!ClientNvgState.isEnabled()) {
            drawBadge(g, mc.font, 8, 8, 0x9014181E, 0xFF55606C,
                    Component.translatable("hud.sem_classsystem.nvg_standby").getString(), 0xFF9AA4B2);
            return;
        }

        switch (equipped) {
            case NIGHT_VISION -> renderNightVisionHud(g, mc.font, w, h);
            case INFRARED -> renderInfraredHud(g, mc, player, w, h);
            case SMART -> renderSmartHud(g, mc, player, w, h);
            default -> {
            }
        }
    }

    private static void renderNightVisionHud(GuiGraphics g, Font font, int w, int h) {
        // Classic phosphor green atmospheric wash + subtle binocular outer darkening
        g.fill(0, 0, w, h, 0x1D0B3B13);
        g.fill(0, 0, w, 12, 0x38041207);
        g.fill(0, h - 12, w, h, 0x38041207);
        g.fill(0, 12, 16, h - 12, 0x38041207);
        g.fill(w - 16, 12, w, h - 12, 0x38041207);

        drawCornerBrackets(g, w, h, 10, 18, 0xAA3BFF5A);
        drawBadge(g, font, 14, 14, 0x99061B0A, 0xFF2EDB4D,
                Component.translatable("hud.sem_classsystem.nvg_night").getString(), 0xFF68FF7E);
    }

    private static void renderInfraredHud(GuiGraphics g, Minecraft mc, Player player, int w, int h) {
        // Cool indigo FLIR polarity wash + faint scanlines
        g.fill(0, 0, w, h, 0x32090F2B);
        for (int y = 0; y < h; y += 4) {
            g.fill(0, y, w, y + 1, 0x0D000000);
        }
        drawCornerBrackets(g, w, h, 10, 20, 0xBBFF9E2C);

        // Thermal reticle brackets near center
        int cx = w / 2;
        int cy = h / 2;
        g.fill(cx - 14, cy - 1, cx - 7, cy + 1, 0xAAFF9E2C);
        g.fill(cx + 8, cy - 1, cx + 15, cy + 1, 0xAAFF9E2C);
        g.fill(cx - 1, cy - 14, cx + 1, cy - 7, 0xAAFF9E2C);
        g.fill(cx - 1, cy + 8, cx + 1, cy + 15, 0xAAFF9E2C);

        drawBadge(g, mc.font, 14, 14, 0xA8110B22, 0xFFFF8C1A,
                Component.translatable("hud.sem_classsystem.nvg_infrared").getString(), 0xFFFFC14D);

        // Live thermography readout under crosshair
        String tempLabel = sampleCrosshairTemperature(mc, player);
        if (tempLabel != null) {
            int color = tempLabel.contains("CRITICAL") ? 0xFFFFF4A8
                    : tempLabel.contains("WARM") ? 0xFFFFAA33
                    : 0xFF6BC2FF;
            int tw = mc.font.width(tempLabel);
            g.fill(cx - tw / 2 - 4, cy + 19, cx + tw / 2 + 4, cy + 31, 0x990B0E1D);
            g.drawString(mc.font, tempLabel, cx - tw / 2, cy + 21, color, true);
        }
    }

    private static void renderSmartHud(GuiGraphics g, Minecraft mc, Player player, int w, int h) {
        // Ultra-clear tactical AR glass wash + crisp cyan HUD brackets
        g.fill(0, 0, w, h, 0x10042230);
        drawCornerBrackets(g, w, h, 10, 22, 0xCC2DE1FF);

        String header = Component.translatable("hud.sem_classsystem.nvg_smart", smartContactCount).getString();
        drawBadge(g, mc.font, 14, 14, 0xA4061822, 0xFF28D7FF, header, 0xFF72ECFF);

        if (smartNearestDist >= 0) {
            String dir = bearingCardinal(smartNearestYaw);
            String alert = Component.translatable("hud.sem_classsystem.nvg_smart_nearest",
                    smartNearestDist, dir).getString();
            drawBadge(g, mc.font, 14, 30, 0xA828070B, 0xFFFF3642, alert, 0xFFFF7A82);
        } else {
            String clear = Component.translatable("hud.sem_classsystem.nvg_smart_clear").getString();
            drawBadge(g, mc.font, 14, 30, 0x90061B1A, 0xFF27C28F, clear, 0xFF66F2B8);
        }
    }

    private static void drawCornerBrackets(GuiGraphics g, int w, int h, int pad, int len, int color) {
        // Top-left
        g.fill(pad, pad, pad + len, pad + 1, color);
        g.fill(pad, pad, pad + 1, pad + len, color);
        // Top-right
        g.fill(w - pad - len, pad, w - pad, pad + 1, color);
        g.fill(w - pad - 1, pad, w - pad, pad + len, color);
        // Bottom-left
        g.fill(pad, h - pad - 1, pad + len, h - pad, color);
        g.fill(pad, h - pad - len, pad + 1, h - pad, color);
        // Bottom-right
        g.fill(w - pad - len, h - pad - 1, w - pad, h - pad, color);
        g.fill(w - pad - 1, h - pad - len, w - pad, h - pad, color);
    }

    private static void drawBadge(GuiGraphics g, Font font, int x, int y,
                                  int bgColor, int accentColor, String text, int textColor) {
        int tw = font.width(text);
        g.fill(x, y, x + tw + 10, y + 13, bgColor);
        g.fill(x, y, x + 2, y + 13, accentColor);
        g.drawString(font, text, x + 5, y + 3, textColor, true);
    }

    private static String sampleCrosshairTemperature(Minecraft mc, Player player) {
        double maxDist = 64.0D;
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 look = player.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(maxDist));
        AABB box = player.getBoundingBox().expandTowards(look.scale(maxDist)).inflate(1.2D);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                player, eye, end, box,
                e -> e instanceof LivingEntity && e.isPickable() && !e.isSpectator(),
                maxDist * maxDist);

        // Verify block occlusion before reporting entity thermal lock
        BlockHitResult blockHit = player.level().clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
        double blockDistSq = blockHit.getType() != HitResult.Type.MISS
                ? eye.distanceToSqr(blockHit.getLocation())
                : Double.MAX_VALUE;

        if (entityHit != null && entityHit.getEntity() instanceof LivingEntity living) {
            double entDistSq = eye.distanceToSqr(entityHit.getLocation());
            if (entDistSq <= blockDistSq + 1.5D) {
                ThermalClass tc = classifyThermal(living);
                return switch (tc) {
                    case EXTREME -> "420.0°C [CRITICAL HEAT]";
                    case WARM -> "37.4°C [WARM]";
                    case COLD -> "4.8°C [COLD]";
                };
            }
        }

        if (blockHit.getType() != HitResult.Type.MISS && blockDistSq <= maxDist * maxDist) {
            BlockState state = mc.level.getBlockState(blockHit.getBlockPos());
            if (isHotBlock(state)) {
                return "340.0°C [CRITICAL HEAT SOURCE]";
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // 3D World Rendering: Infrared Thermal Silhouettes & Smart Enemy Target Boxes
    // -------------------------------------------------------------------------
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        NvgMode mode = ClientNvgState.getActiveMode(player);
        if (mode != NvgMode.INFRARED && mode != NvgMode.SMART) {
            smartContactCount = 0;
            smartNearestDist = -1;
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();
        float partialTick = event.getPartialTick();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        try {
            if (mode == NvgMode.INFRARED) {
                renderInfraredWorld(mc, player, poseStack, camPos, partialTick);
            } else {
                renderSmartWorld(mc, player, poseStack, camPos, partialTick);
            }
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            poseStack.popPose();
        }
    }

    private enum ThermalClass {
        EXTREME,
        WARM,
        COLD
    }

    private static ThermalClass classifyThermal(LivingEntity entity) {
        if (entity.isOnFire()
                || entity instanceof Blaze
                || entity instanceof MagmaCube
                || entity instanceof Ghast
                || entity instanceof Strider) {
            return ThermalClass.EXTREME;
        }
        if (entity.getMobType() == MobType.UNDEAD) {
            return ThermalClass.COLD;
        }
        if (entity instanceof Player
                || entity instanceof AbstractUnit
                || entity instanceof VdfUnitEntity
                || entity instanceof Raider
                || entity instanceof AbstractVillager
                || entity instanceof Animal) {
            return ThermalClass.WARM;
        }
        return entity instanceof Monster ? ThermalClass.COLD : ThermalClass.WARM;
    }

    private static boolean isHotBlock(BlockState state) {
        if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK)) {
            return true;
        }
        if (state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE)
                || state.is(Blocks.SMOKER)) {
            return state.hasProperty(BlockStateProperties.LIT)
                    && Boolean.TRUE.equals(state.getValue(BlockStateProperties.LIT));
        }
        return false;
    }

    private static void renderInfraredWorld(Minecraft mc, Player player, PoseStack poseStack,
                                            Vec3 camPos, float partialTick) {
        updateHeatBlockCache(mc, player);

        double maxDistSq = INFRARED_ENTITY_RANGE * INFRARED_ENTITY_RANGE;
        List<LivingEntity> warmOrHot = new ArrayList<>();
        List<LivingEntity> coldEntities = new ArrayList<>();

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity living) || !living.isAlive()) {
                continue;
            }
            if (living == player && mc.options.getCameraType().isFirstPerson()) {
                continue;
            }
            if (living.distanceToSqr(camPos) > maxDistSq) {
                continue;
            }
            ThermalClass tc = classifyThermal(living);
            if (tc == ThermalClass.COLD) {
                coldEntities.add(living);
            } else {
                warmOrHot.add(living);
            }
        }

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buf = tesselator.getBuilder();
        Matrix4f mat = poseStack.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        // 1. Subtle thermal bleed through leaves/close walls (<= 30m) for warm/hot targets
        RenderSystem.disableDepthTest();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (LivingEntity living : warmOrHot) {
            if (living.distanceToSqr(camPos) > 30.0D * 30.0D) {
                continue;
            }
            AABB box = interpolatedBox(living, partialTick).inflate(0.03D);
            ThermalClass tc = classifyThermal(living);
            if (tc == ThermalClass.EXTREME) {
                addFilledBox(buf, mat, box, 1.0F, 0.45F, 0.08F, 0.16F);
            } else {
                addFilledBox(buf, mat, box, 1.0F, 0.38F, 0.05F, 0.13F);
            }
        }
        BufferUploader.drawWithShader(buf.end());

        // 2. Direct line-of-sight bright thermal bodies & environmental heat sources (depth-tested)
        RenderSystem.enableDepthTest();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (LivingEntity living : warmOrHot) {
            AABB full = interpolatedBox(living, partialTick);
            ThermalClass tc = classifyThermal(living);
            if (tc == ThermalClass.EXTREME) {
                // White-hot core + outer orange halo
                addFilledBox(buf, mat, full.inflate(0.07D), 1.0F, 0.35F, 0.05F, 0.28F);
                addFilledBox(buf, mat, full.inflate(-0.08D), 1.0F, 0.96F, 0.72F, 0.52F);
            } else {
                // Warm body: red-orange outer heat envelope + bright yellow torso/head core
                addFilledBox(buf, mat, full.inflate(0.04D), 1.0F, 0.28F, 0.04F, 0.24F);
                addFilledBox(buf, mat, full.inflate(-0.06D), 1.0F, 0.86F, 0.15F, 0.42F);
                // Brighter head signature
                double headBottom = full.maxY - (full.getYsize() * 0.32D);
                AABB headCore = new AABB(
                        full.minX + 0.08D, headBottom, full.minZ + 0.08D,
                        full.maxX - 0.08D, full.maxY - 0.02D, full.maxZ - 0.08D);
                addFilledBox(buf, mat, headCore, 1.0F, 0.96F, 0.55F, 0.45F);
            }
        }
        for (BlockPos bp : CACHED_HEAT_BLOCKS) {
            AABB heatBox = new AABB(bp).inflate(0.02D);
            addFilledBox(buf, mat, heatBox, 1.0F, 0.42F, 0.06F, 0.26F);
        }
        BufferUploader.drawWithShader(buf.end());

        // 3. Cold / undead subtle cool blue thermal outlines + crisp warm rims
        buf.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        for (LivingEntity living : warmOrHot) {
            AABB full = interpolatedBox(living, partialTick).inflate(0.04D);
            ThermalClass tc = classifyThermal(living);
            if (tc == ThermalClass.EXTREME) {
                addWireBox(buf, mat, full, 1.0F, 0.92F, 0.45F, 0.85F);
            } else {
                addWireBox(buf, mat, full, 1.0F, 0.62F, 0.12F, 0.75F);
            }
        }
        for (LivingEntity cold : coldEntities) {
            AABB full = interpolatedBox(cold, partialTick).inflate(0.02D);
            addWireBox(buf, mat, full, 0.24F, 0.64F, 1.0F, 0.35F);
        }
        BufferUploader.drawWithShader(buf.end());
    }

    private static void updateHeatBlockCache(Minecraft mc, Player player) {
        long tick = mc.level.getGameTime();
        if (tick >= lastHeatScanTick && tick - lastHeatScanTick < 12L) {
            return;
        }
        lastHeatScanTick = tick;
        CACHED_HEAT_BLOCKS.clear();
        BlockPos origin = player.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = -6; dy <= 8; dy++) {
            for (int dx = -HEAT_BLOCK_SCAN_RADIUS; dx <= HEAT_BLOCK_SCAN_RADIUS; dx++) {
                for (int dz = -HEAT_BLOCK_SCAN_RADIUS; dz <= HEAT_BLOCK_SCAN_RADIUS; dz++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (isHotBlock(mc.level.getBlockState(cursor))) {
                        CACHED_HEAT_BLOCKS.add(cursor.immutable());
                        if (CACHED_HEAT_BLOCKS.size() >= 64) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private static void renderSmartWorld(Minecraft mc, Player player, PoseStack poseStack,
                                         Vec3 camPos, float partialTick) {
        double unitDistSq = SMART_UNIT_RANGE * SMART_UNIT_RANGE;
        double mobDistSq = SMART_MOB_RANGE * SMART_MOB_RANGE;

        List<SmartTarget> targets = new ArrayList<>();
        int count = 0;
        double nearestSq = Double.MAX_VALUE;
        float nearestYaw = 0.0F;

        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity living) || !living.isAlive()) {
                continue;
            }
            if (living == player) {
                continue;
            }
            boolean isEnemyUnit = CommsRadarBlockEntity.isHostileContact(living);
            boolean isOtherHostile = !isEnemyUnit
                    && living instanceof Monster
                    && !(living instanceof AbstractUnit)
                    && !(living instanceof VdfUnitEntity);
            if (!isEnemyUnit && !isOtherHostile) {
                continue;
            }
            double distSq = living.distanceToSqr(camPos);
            if (isEnemyUnit && distSq > unitDistSq) {
                continue;
            }
            if (isOtherHostile && distSq > mobDistSq) {
                continue;
            }
            count++;
            if (distSq < nearestSq) {
                nearestSq = distSq;
                double dx = living.getX() - player.getX();
                double dz = living.getZ() - player.getZ();
                nearestYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            }
            targets.add(new SmartTarget(living, isEnemyUnit, (int) Math.round(Math.sqrt(distSq))));
        }

        smartContactCount = count;
        smartNearestDist = nearestSq < Double.MAX_VALUE ? (int) Math.round(Math.sqrt(nearestSq)) : -1;
        smartNearestYaw = nearestYaw;

        if (targets.isEmpty()) {
            return;
        }

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buf = tesselator.getBuilder();
        Matrix4f mat = poseStack.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        // 1. Subtle interior tint fill inside detected target boxes
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (SmartTarget t : targets) {
            AABB box = interpolatedBox(t.entity, partialTick).inflate(0.08D);
            if (t.isEnemyUnit) {
                addFilledBox(buf, mat, box, 1.0F, 0.14F, 0.18F, 0.10F);
            } else {
                addFilledBox(buf, mat, box, 1.0F, 0.65F, 0.12F, 0.06F);
            }
        }
        BufferUploader.drawWithShader(buf.end());

        // 2. Crisp tactical 3D boxes + reinforced corner brackets + HP bars
        buf.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        for (SmartTarget t : targets) {
            AABB box = interpolatedBox(t.entity, partialTick).inflate(0.08D);
            if (t.isEnemyUnit) {
                addWireBox(buf, mat, box, 1.0F, 0.20F, 0.24F, 0.62F);
                addCornerBracketBox(buf, mat, box.inflate(0.03D), 0.28F, 1.0F, 0.16F, 0.20F, 1.0F);
            } else {
                addCornerBracketBox(buf, mat, box, 0.24F, 1.0F, 0.65F, 0.12F, 0.88F);
            }

            // Overhead HP bar frame in 3D space
            float hpFrac = Mth.clamp(t.entity.getHealth() / Math.max(1.0F, t.entity.getMaxHealth()), 0.0F, 1.0F);
            float barW = (float) Math.max(0.65D, box.getXsize());
            float cx = (float) ((box.minX + box.maxX) * 0.5D);
            float cz = (float) ((box.minZ + box.maxZ) * 0.5D);
            float yBar = (float) box.maxY + 0.14F;
            float x0 = cx - barW * 0.5F;
            float x1 = x0 + barW * hpFrac;
            float r = t.isEnemyUnit ? 1.0F : 1.0F;
            float g = t.isEnemyUnit ? 0.22F : 0.70F;
            float b = t.isEnemyUnit ? 0.26F : 0.15F;
            buf.vertex(mat, x0, yBar, cz).color(r, g, b, 0.95F).endVertex();
            buf.vertex(mat, x1, yBar, cz).color(r, g, b, 0.95F).endVertex();
        }
        BufferUploader.drawWithShader(buf.end());

        // 3. Billboarded tactical callout badges for enemy units (<= 72m) & close hostiles (<= 24m)
        MultiBufferSource.BufferSource textBuffer = mc.renderBuffers().bufferSource();
        for (SmartTarget t : targets) {
            if (t.isEnemyUnit && t.distance <= 72) {
                drawSmartCalloutTag(mc, poseStack, textBuffer, t, partialTick);
            } else if (!t.isEnemyUnit && t.distance <= 24) {
                drawSmartCalloutTag(mc, poseStack, textBuffer, t, partialTick);
            }
        }
        textBuffer.endBatch();
    }

    private static void drawSmartCalloutTag(Minecraft mc, PoseStack poseStack,
                                            MultiBufferSource buffer, SmartTarget t, float partialTick) {
        double ix = Mth.lerp(partialTick, t.entity.xo, t.entity.getX());
        double iy = Mth.lerp(partialTick, t.entity.yo, t.entity.getY()) + t.entity.getBbHeight() + 0.38D;
        double iz = Mth.lerp(partialTick, t.entity.zo, t.entity.getZ());

        String label = formatSmartLabel(t);
        int textColor = t.isEnemyUnit ? 0xFFFF555E : 0xFFFFB84D;
        int bgColor = t.isEnemyUnit ? 0xA026060A : 0x90221604;

        poseStack.pushPose();
        poseStack.translate(ix, iy, iz);
        poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        float scale = Mth.clamp(0.022F + t.distance * 0.00055F, 0.022F, 0.060F);
        poseStack.scale(-scale, -scale, scale);

        float x = -mc.font.width(label) * 0.5F;
        Matrix4f tagMat = poseStack.last().pose();
        mc.font.drawInBatch(label, x, 0.0F, textColor, false, tagMat, buffer,
                Font.DisplayMode.SEE_THROUGH, bgColor, LightTexture.FULL_BRIGHT);
        poseStack.popPose();
    }

    private static String formatSmartLabel(SmartTarget t) {
        LivingEntity e = t.entity;
        if (t.isEnemyUnit) {
            String faction = "HOSTILE";
            if (e instanceof TerroristUnitEntity) {
                faction = "INSURGENT";
            } else if (e instanceof Raider) {
                faction = "RAIDER";
            } else {
                UnitClass uc = UnitClass.of(e);
                if (uc == UnitClass.RU_UNIT) {
                    faction = "RU";
                } else if (uc == UnitClass.US_UNIT) {
                    faction = "US";
                }
            }
            WeaponClass wc = WeaponClassSupport.get(e);
            String role = wc != null ? " • " + ClassSystemConfig.classNickname(wc).toUpperCase() : "";
            return "[" + faction + role + "] " + t.distance + "m";
        }
        return e.getType().getDescription().getString() + " " + t.distance + "m";
    }

    private static AABB interpolatedBox(Entity e, float partialTick) {
        double ix = Mth.lerp(partialTick, e.xo, e.getX());
        double iy = Mth.lerp(partialTick, e.yo, e.getY());
        double iz = Mth.lerp(partialTick, e.zo, e.getZ());
        double halfW = e.getBbWidth() * 0.5D;
        return new AABB(ix - halfW, iy, iz - halfW, ix + halfW, iy + e.getBbHeight(), iz + halfW);
    }

    private static void addWireBox(BufferBuilder buf, Matrix4f mat, AABB b,
                                   float r, float g, float bl, float a) {
        float x0 = (float) b.minX, y0 = (float) b.minY, z0 = (float) b.minZ;
        float x1 = (float) b.maxX, y1 = (float) b.maxY, z1 = (float) b.maxZ;
        // Bottom
        line(buf, mat, x0, y0, z0, x1, y0, z0, r, g, bl, a);
        line(buf, mat, x1, y0, z0, x1, y0, z1, r, g, bl, a);
        line(buf, mat, x1, y0, z1, x0, y0, z1, r, g, bl, a);
        line(buf, mat, x0, y0, z1, x0, y0, z0, r, g, bl, a);
        // Top
        line(buf, mat, x0, y1, z0, x1, y1, z0, r, g, bl, a);
        line(buf, mat, x1, y1, z0, x1, y1, z1, r, g, bl, a);
        line(buf, mat, x1, y1, z1, x0, y1, z1, r, g, bl, a);
        line(buf, mat, x0, y1, z1, x0, y1, z0, r, g, bl, a);
        // Verticals
        line(buf, mat, x0, y0, z0, x0, y1, z0, r, g, bl, a);
        line(buf, mat, x1, y0, z0, x1, y1, z0, r, g, bl, a);
        line(buf, mat, x1, y0, z1, x1, y1, z1, r, g, bl, a);
        line(buf, mat, x0, y0, z1, x0, y1, z1, r, g, bl, a);
    }

    private static void addCornerBracketBox(BufferBuilder buf, Matrix4f mat, AABB b, float frac,
                                            float r, float g, float bl, float a) {
        float x0 = (float) b.minX, y0 = (float) b.minY, z0 = (float) b.minZ;
        float x1 = (float) b.maxX, y1 = (float) b.maxY, z1 = (float) b.maxZ;
        float lx = (x1 - x0) * frac;
        float ly = (y1 - y0) * frac;
        float lz = (z1 - z0) * frac;

        // 8 corners, 3 short arms each
        float[] xs = {x0, x1};
        float[] ys = {y0, y1};
        float[] zs = {z0, z1};
        for (int xi = 0; xi < 2; xi++) {
            for (int yi = 0; yi < 2; yi++) {
                for (int zi = 0; zi < 2; zi++) {
                    float cx = xs[xi], cy = ys[yi], cz = zs[zi];
                    float dx = xi == 0 ? lx : -lx;
                    float dy = yi == 0 ? ly : -ly;
                    float dz = zi == 0 ? lz : -lz;
                    line(buf, mat, cx, cy, cz, cx + dx, cy, cz, r, g, bl, a);
                    line(buf, mat, cx, cy, cz, cx, cy + dy, cz, r, g, bl, a);
                    line(buf, mat, cx, cy, cz, cx, cy, cz + dz, r, g, bl, a);
                }
            }
        }
    }

    private static void addFilledBox(BufferBuilder buf, Matrix4f mat, AABB b,
                                     float r, float g, float bl, float a) {
        float x0 = (float) b.minX, y0 = (float) b.minY, z0 = (float) b.minZ;
        float x1 = (float) b.maxX, y1 = (float) b.maxY, z1 = (float) b.maxZ;

        // -Y (bottom)
        quad(buf, mat, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, bl, a);
        // +Y (top)
        quad(buf, mat, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, r, g, bl, a);
        // -Z (north)
        quad(buf, mat, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, bl, a);
        // +Z (south)
        quad(buf, mat, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, r, g, bl, a);
        // -X (west)
        quad(buf, mat, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, r, g, bl, a);
        // +X (east)
        quad(buf, mat, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, bl, a);
    }

    private static void line(BufferBuilder buf, Matrix4f mat,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float r, float g, float b, float a) {
        buf.vertex(mat, x0, y0, z0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y1, z1).color(r, g, b, a).endVertex();
    }

    private static void quad(BufferBuilder buf, Matrix4f mat,
                             float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float r, float g, float b, float a) {
        buf.vertex(mat, x0, y0, z0).color(r, g, b, a).endVertex();
        buf.vertex(mat, x1, y1, z1).color(r, g, b, a).endVertex();
        buf.vertex(mat, x2, y2, z2).color(r, g, b, a).endVertex();
        buf.vertex(mat, x3, y3, z3).color(r, g, b, a).endVertex();
    }

    private static String bearingCardinal(float yawDeg) {
        float norm = ((yawDeg % 360.0F) + 360.0F) % 360.0F;
        String[] dirs = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        return dirs[Math.round(norm / 45.0F) % 8];
    }

    private record SmartTarget(LivingEntity entity, boolean isEnemyUnit, int distance) {
    }
}
