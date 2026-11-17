package com.poseidon.sem_classsystem.item;

import com.poseidon.sem_classsystem.block.CommsRadarBlockEntity;
import com.poseidon.sem_classsystem.entity.VdfUnitEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.Comparator;
import java.util.List;

// Squad Target Designator / Laser Marker:
//  - Right-click (up to 96m): paints an enemy unit or ground position with a red
//    laser beam and orders nearby owned PMC/BHQ units (<= 64m) to focus-fire or
//    advance/assault the marked coordinates.
//  - Sneak + Right-click: cancels active move/attack designations and recalls the
//    squad to FOLLOW_COMMANDER formation.
public class LaserDesignatorItem extends Item {

    private static final double DESIGNATE_RANGE = 96.0D;
    private static final double SQUAD_RADIUS = 64.0D;
    private static final DustParticleOptions LASER_DUST =
            new DustParticleOptions(new Vector3f(1.0F, 0.14F, 0.14F), 1.0F);
    private static final DustParticleOptions BEACON_DUST =
            new DustParticleOptions(new Vector3f(1.0F, 0.25F, 0.12F), 1.4F);

    public LaserDesignatorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }

        List<PmcUnitEntity> units = level.getEntitiesOfClass(PmcUnitEntity.class,
                player.getBoundingBox().inflate(SQUAD_RADIUS),
                u -> u.isAlive() && u.isOwnedBy(player));
        units.sort(Comparator.comparingInt(PmcUnitEntity::getId));

        if (units.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("message.sem_classsystem.designator_no_units")
                            .withStyle(ChatFormatting.YELLOW),
                    true);
            return InteractionResultHolder.fail(stack);
        }

        // Sneak + Right-Click -> Recall / clear active laser designation
        if (player.isShiftKeyDown()) {
            int idx = 0;
            for (PmcUnitEntity unit : units) {
                unit.setAttackTargetId(-1);
                unit.setTarget(null);
                unit.releaseMovementLock();
                unit.setFormationIndex(idx++);
                unit.setOrder(OrderType.FOLLOW_COMMANDER);
                unit.resetCommanderGoalCooldown();
            }
            level.playSound(null, player.blockPosition(), SoundEvents.UI_BUTTON_CLICK.value(),
                    SoundSource.PLAYERS, 0.6F, 1.3F);
            player.displayClientMessage(
                    Component.translatable("message.sem_classsystem.designator_recall", units.size())
                            .withStyle(ChatFormatting.AQUA),
                    true);
            player.getCooldowns().addCooldown(this, 10);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 look = player.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(DESIGNATE_RANGE));

        BlockHitResult blockHit = level.clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        double blockDistSq = blockHit.getType() != HitResult.Type.MISS
                ? eye.distanceToSqr(blockHit.getLocation())
                : Double.MAX_VALUE;

        AABB sweepBox = player.getBoundingBox().expandTowards(look.scale(DESIGNATE_RANGE)).inflate(1.5D);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                player, eye, end, sweepBox,
                e -> e instanceof LivingEntity l && l.isAlive() && l.isPickable()
                        && !l.isSpectator() && isValidLaserTarget(l),
                Math.min(DESIGNATE_RANGE * DESIGNATE_RANGE, blockDistSq));

        if (entityHit != null && entityHit.getEntity() instanceof LivingEntity target) {
            int dist = (int) Math.round(player.distanceTo(target));
            target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 140, 0, false, false));

            int idx = 0;
            for (PmcUnitEntity unit : units) {
                unit.releaseMovementLock();
                unit.setAttackTargetId(target.getId());
                unit.setTarget(target);
                if (unit.distanceTo(target) > 26.0F) {
                    unit.setMoveToTarget(staggerOffset(target.position(), idx));
                    unit.setOrder(OrderType.MOVE_TO_POSITION);
                    unit.resetCommanderGoalCooldown();
                }
                idx++;
            }

            if (level instanceof ServerLevel sl) {
                spawnLaserBeam(sl, eye.add(look.scale(0.8D)),
                        target.getEyePosition().add(0.0D, -0.2D, 0.0D), true);
            }
            level.playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP,
                    SoundSource.PLAYERS, 0.6F, 1.75F);
            player.displayClientMessage(
                    Component.translatable("message.sem_classsystem.designator_target",
                                    target.getDisplayName(), dist, units.size())
                            .withStyle(ChatFormatting.RED),
                    true);
            player.getCooldowns().addCooldown(this, 12);
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }

        if (blockHit.getType() == HitResult.Type.MISS) {
            player.displayClientMessage(
                    Component.translatable("message.sem_classsystem.designator_miss")
                            .withStyle(ChatFormatting.DARK_GRAY),
                    true);
            return InteractionResultHolder.fail(stack);
        }

        Vec3 markPos = Vec3.atBottomCenterOf(blockHit.getBlockPos().relative(blockHit.getDirection()));
        int dist = (int) Math.round(player.distanceToSqr(markPos) > 0 ? Math.sqrt(player.distanceToSqr(markPos)) : 0);

        // Check for hostiles near the designated area so advancing units engage them cleanly
        LivingEntity areaHostile = level.getEntitiesOfClass(LivingEntity.class,
                        new AABB(markPos, markPos).inflate(12.0D),
                        e -> e.isAlive() && isValidLaserTarget(e))
                .stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(markPos)))
                .orElse(null);

        int idx = 0;
        for (PmcUnitEntity unit : units) {
            unit.releaseMovementLock();
            if (areaHostile != null) {
                unit.setAttackTargetId(areaHostile.getId());
                unit.setTarget(areaHostile);
            } else {
                unit.setAttackTargetId(-1);
                unit.setTarget(null);
            }
            unit.setMoveToTarget(staggerOffset(markPos, idx++));
            unit.setOrder(OrderType.MOVE_TO_POSITION);
            unit.resetCommanderGoalCooldown();
        }

        if (level instanceof ServerLevel sl) {
            spawnLaserBeam(sl, eye.add(look.scale(0.8D)), blockHit.getLocation(), false);
        }
        level.playSound(null, player.blockPosition(), SoundEvents.SPYGLASS_USE,
                SoundSource.PLAYERS, 0.85F, 1.35F);
        player.displayClientMessage(
                Component.translatable("message.sem_classsystem.designator_point", dist, units.size())
                        .withStyle(ChatFormatting.GOLD),
                true);
        player.getCooldowns().addCooldown(this, 12);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    private static boolean isValidLaserTarget(LivingEntity entity) {
        if (entity instanceof VdfUnitEntity) {
            return false;
        }
        if (entity instanceof PmcUnitEntity pmc && pmc.getOwnerUUID() != null) {
            return false;
        }
        if (CommsRadarBlockEntity.isHostileContact(entity)) {
            return true;
        }
        return entity instanceof Monster && !(entity instanceof AbstractUnit);
    }

    private static Vec3 staggerOffset(Vec3 center, int index) {
        if (index == 0) {
            return center;
        }
        double angle = (index * 2.399963D); // golden angle spread avoids stacking on 1 block
        double radius = Math.min(3.5D, 1.4D + 0.35D * index);
        return center.add(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius);
    }

    private static void spawnLaserBeam(ServerLevel sl, Vec3 start, Vec3 end, boolean targetLock) {
        Vec3 delta = end.subtract(start);
        double len = delta.length();
        int steps = Math.max(8, Math.min(48, (int) (len * 1.4D)));
        Vec3 step = delta.scale(1.0D / steps);
        for (int i = 1; i <= steps; i++) {
            Vec3 p = start.add(step.scale(i));
            sl.sendParticles(LASER_DUST, p.x, p.y, p.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        // Vertical laser designator beacon column at the marked coordinate/target
        for (int h = 0; h < 6; h++) {
            sl.sendParticles(BEACON_DUST,
                    end.x, end.y + 0.25D + h * 0.45D, end.z,
                    targetLock ? 2 : 1, 0.05D, 0.05D, 0.05D, 0.0D);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.sem_classsystem.laser_designator.desc")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.sem_classsystem.laser_designator.hint")
                .withStyle(ChatFormatting.RED));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
