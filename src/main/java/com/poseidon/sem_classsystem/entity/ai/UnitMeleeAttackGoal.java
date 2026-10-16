package com.poseidon.sem_classsystem.entity.ai;

import com.poseidon.sem_classsystem.faction.UnitClass;
import com.tacz.guns.api.item.gun.AbstractGunItem;
import com.tacz.guns.api.item.nbt.GunItemDataAccessor;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraftforge.common.ForgeMod;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.util.SoldierState;

// Fast-response melee AI goal for SEM units holding melee weapons (including Better Combat weapons).
// Bypasses vanilla MeleeAttackGoal's 20-tick start delay, actively acquires nearby hostiles,
// suppresses SEM cover-seeking locks, and cycles a 3-hit combo attack cadence.
public class UnitMeleeAttackGoal extends MeleeAttackGoal {

    private final double rushSpeed;
    private int attackCooldown = 0;
    private int repathTicker = 0;

    public UnitMeleeAttackGoal(PathfinderMob mob, double speedModifier, boolean followingTargetEvenIfNotSeen) {
        super(mob, speedModifier, followingTargetEvenIfNotSeen);
        this.rushSpeed = speedModifier;
    }

    public static boolean isMeleeWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof AbstractGunItem || stack.getItem() instanceof GunItemDataAccessor) {
            return false;
        }
        if (stack.getItem() instanceof SwordItem
                || stack.getItem() instanceof AxeItem
                || stack.getItem() instanceof TridentItem) {
            return true;
        }
        return !stack.getAttributeModifiers(EquipmentSlot.MAINHAND)
                .get(Attributes.ATTACK_DAMAGE)
                .isEmpty();
    }

    @Override
    public boolean canUse() {
        if (!isMeleeWeapon(this.mob.getMainHandItem())) {
            return false;
        }
        if (this.mob instanceof PmcUnitEntity pmc && pmc.getOrder() == OrderType.CEASE_FIRE) {
            return false;
        }
        LivingEntity target = this.mob.getTarget();
        if (target == null || !target.isAlive()) {
            target = findImmediateMeleeTarget();
            if (target != null) {
                this.mob.setTarget(target);
            }
        }
        return target != null && target.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        if (!isMeleeWeapon(this.mob.getMainHandItem())) {
            return false;
        }
        LivingEntity target = this.mob.getTarget();
        return target != null && target.isAlive() && !target.isSpectator();
    }

    @Override
    public void start() {
        releaseUnitLocks();
        this.attackCooldown = 0;
        this.repathTicker = 0;
        this.mob.setAggressive(true);
        this.mob.setSprinting(true);
        LivingEntity target = this.mob.getTarget();
        if (target != null) {
            this.mob.getNavigation().moveTo(target, this.rushSpeed);
        }
    }

    @Override
    public void stop() {
        this.mob.setSprinting(false);
        super.stop();
    }

    @Override
    public void tick() {
        releaseUnitLocks();
        LivingEntity target = this.mob.getTarget();
        if (target == null || !target.isAlive()) {
            return;
        }

        this.mob.getLookControl().setLookAt(target, 35.0F, 35.0F);
        this.mob.setSprinting(true);

        if (this.attackCooldown > 0) {
            this.attackCooldown--;
        }

        if (--this.repathTicker <= 0) {
            this.repathTicker = 3;
            this.mob.getNavigation().moveTo(target, this.rushSpeed);
        }

        double distSqr = this.mob.distanceToSqr(target.getX(), target.getY(), target.getZ());
        checkAndPerformAttack(target, distSqr);
    }

    private void releaseUnitLocks() {
        if (this.mob.isShiftKeyDown()) {
            this.mob.setShiftKeyDown(false);
        }
        if (this.mob.getPose() == net.minecraft.world.entity.Pose.SWIMMING) {
            this.mob.setPose(net.minecraft.world.entity.Pose.STANDING);
        }
        if (this.mob instanceof AbstractUnit unit) {
            if (unit.isMovementLockedByManager()) {
                unit.releaseMovementLock();
            }
            if (unit.getSoldierState() != SoldierState.ENGAGE) {
                unit.setSoldierState(SoldierState.ENGAGE);
            }
        }
    }

    @Override
    protected void checkAndPerformAttack(LivingEntity enemy, double distToEnemySqr) {
        double reachSqr = this.getAttackReachSqr(enemy);
        if (distToEnemySqr <= reachSqr && this.attackCooldown <= 0) {
            // Compute attack interval from weapon attack speed (supports Better Combat & modded weapons)
            this.attackCooldown = computeSwingIntervalTicks();
            this.mob.swing(InteractionHand.MAIN_HAND, true);
            this.mob.doHurtTarget(enemy);
        }
    }

    private int computeSwingIntervalTicks() {
        double atkSpeed = 1.6D;
        AttributeInstance inst = this.mob.getAttribute(Attributes.ATTACK_SPEED);
        if (inst != null && inst.getValue() > 0.2D) {
            atkSpeed = inst.getValue();
        } else {
            var mods = this.mob.getMainHandItem().getAttributeModifiers(EquipmentSlot.MAINHAND)
                    .get(Attributes.ATTACK_SPEED);
            if (!mods.isEmpty()) {
                double delta = mods.iterator().next().getAmount();
                atkSpeed = Math.max(0.6D, 4.0D + delta);
            }
        }
        return Math.max(8, Math.min(20, (int) Math.round(20.0D / Math.max(0.8D, atkSpeed))));
    }

    @Override
    protected double getAttackReachSqr(LivingEntity enemy) {
        double baseReach = 2.65D;
        AttributeInstance reachAttr = this.mob.getAttribute(ForgeMod.ENTITY_REACH.get());
        if (reachAttr != null && reachAttr.getValue() > 0.0D) {
            baseReach = Math.max(baseReach, reachAttr.getValue());
        } else {
            var reachMods = this.mob.getMainHandItem().getAttributeModifiers(EquipmentSlot.MAINHAND)
                    .get(ForgeMod.ENTITY_REACH.get());
            if (!reachMods.isEmpty()) {
                baseReach += Math.max(0.0D, reachMods.iterator().next().getAmount());
            }
        }
        double total = baseReach + enemy.getBbWidth() * 0.6D;
        return total * total;
    }

    private LivingEntity findImmediateMeleeTarget() {
        if ((this.mob.tickCount + this.mob.getId()) % 4 != 0) {
            return null;
        }
        UnitClass myFaction = UnitClass.of(this.mob);
        boolean friendlyOp = this.mob instanceof PmcUnitEntity;

        var candidates = this.mob.level().getEntitiesOfClass(
                LivingEntity.class,
                this.mob.getBoundingBox().inflate(24.0D, 8.0D, 24.0D),
                e -> {
                    if (e == this.mob || !e.isAlive() || e.isSpectator() || this.mob.isAlliedTo(e)) {
                        return false;
                    }
                    if (e instanceof Player p && (p.isCreative() || friendlyOp)) {
                        return false;
                    }
                    UnitClass otherFaction = UnitClass.of(e);
                    if (myFaction != null && otherFaction != null) {
                        if (myFaction == otherFaction) return false;
                        if (friendlyOp && e instanceof PmcUnitEntity) return false;
                        return true;
                    }
                    return e instanceof Enemy && !(friendlyOp && e instanceof PmcUnitEntity);
                });

        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity c : candidates) {
            if (!this.mob.canAttack(c)) continue;
            double d = this.mob.distanceToSqr(c);
            if (d < bestDist && this.mob.getSensing().hasLineOfSight(c)) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }
}
