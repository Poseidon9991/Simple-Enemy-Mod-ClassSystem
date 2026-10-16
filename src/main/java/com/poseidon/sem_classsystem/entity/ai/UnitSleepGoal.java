package com.poseidon.sem_classsystem.entity.ai;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.needs.UnitNeeds;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import com.poseidon.sem_classsystem.world.PlayerChunkClaims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

// Sleep cycle for owned units ([needs].enabled). An exhausted unit claims a
// free bed inside the OWNER's claimed chunks, walks over, and sleeps - day
// or night, so guard/patrol shifts rotate instead of stalling at sundown.
//
// Peaceful only: the goal never starts while the unit has a combat target,
// and an active follow/move order (whistle rally, map move order) suppresses
// it - those are explicit commands that outrank rest. Wakes on hurt (see
// UnitNeedsEvents) or when the order changes mid-nap.
//
// Priority 2, MOVE flag: ties UnitChunkDutyGoal, but duty yields to
// UnitNeeds.needsRest so an exhausted guard hands his post off cleanly
// instead of fighting the sleep goal for the MOVE flag.
public class UnitSleepGoal extends Goal {

    // Orders that mean "player needs you right now" - a unit carrying one of
    // these stays on its feet however tired it is.
    private static final Set<OrderType> BUSY_ORDERS = Set.of(
            OrderType.FOLLOW_COMMANDER, OrderType.FORM_WEDGE, OrderType.FORM_COLUMN,
            OrderType.MOVE_TO_POSITION, OrderType.ATTACK_THAT_TARGET);

    private static final int SCAN_TICKS = 60;
    private static final double REACH_SQR = 2.8 * 2.8;

    private final PmcUnitEntity unit;
    private BlockPos bed;
    private int scanTicks = 0;
    private int sleepTicks = 0;

    public UnitSleepGoal(PmcUnitEntity unit) {
        this.unit = unit;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (this.scanTicks > 0) {
            this.scanTicks--;
        }
        if (!UnitNeeds.appliesTo(this.unit) || !this.unit.isAlive()
                || this.unit.isSleeping()) {
            return false;
        }
        // Tired enough to want a bunk?
        if (UnitNeeds.energy(this.unit) > UnitNeeds.EXHAUSTED_AT) {
            return false;
        }
        // Shift roster: DAY-shift units wait for night, NIGHT-shift for day.
        if (!UnitNeeds.canSleepNow(this.unit)) {
            return false;
        }
        // No sleeping mid-fight or mid-order.
        if (this.unit.getTarget() != null || BUSY_ORDERS.contains(this.unit.getOrder())) {
            return false;
        }
        this.bed = findBed();
        return this.bed != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!this.unit.isAlive() || this.unit.getTarget() != null
                || BUSY_ORDERS.contains(this.unit.getOrder())
                || UnitNeeds.energy(this.unit) >= UnitNeeds.WAKE_AT) {
            return false;
        }
        if (this.unit.isSleeping()) {
            return true;
        }
        // Still walking to the bunk - bail if it vanished or got unclaimed.
        return this.bed != null && bedUsable(this.bed);
    }

    @Override
    public void start() {
        if (this.bed != null) {
            this.unit.getNavigation().moveTo(
                    this.bed.getX() + 0.5, this.bed.getY(), this.bed.getZ() + 0.5, 1.0D);
        }
    }

    @Override
    public void tick() {
        if (this.unit.isSleeping()) {
            this.sleepTicks++;
            int energyPace = ClassSystemConfig.sleepEnergyTicks();
            if (energyPace > 0 && this.sleepTicks % energyPace == 0) {
                UnitNeeds.restoreEnergy(this.unit);
                if (UnitNeeds.energy(this.unit) >= UnitNeeds.WAKE_AT) {
                    UnitNeeds.onRested(this.unit);
                }
            }
            // Wounded recovery: rest knits wounds slowly (slower than a medic).
            int healPace = ClassSystemConfig.sleepHealTicks();
            if (healPace > 0 && this.sleepTicks % healPace == 0
                    && this.unit.getHealth() < this.unit.getMaxHealth()) {
                this.unit.heal(1.0F);
            }
            // Drifting Z's every ~2s - our own translucent ZZZ particle.
            if (this.sleepTicks % 40 == 20
                    && this.unit.level() instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(SemcsRegistries.ZZZ_PARTICLE.get(),
                        this.unit.getX() + this.unit.getRandom().nextGaussian() * 0.15,
                        this.unit.getY() + 1.1,
                        this.unit.getZ() + this.unit.getRandom().nextGaussian() * 0.15,
                        1, 0.0D, 0.05D, 0.0D, 0.0D);
            }
            // Shift ends while asleep (day shift wakes at dawn) - let the
            // goal keep running until WAKE_AT only for AUTO; scheduled
            // units surface when their shift's rest window closes.
            if (this.unit.tickCount % 40 == 0 && !UnitNeeds.canSleepNow(this.unit)
                    && UnitNeeds.energy(this.unit) >= 10) {
                UnitNeeds.wake(this.unit);
            }
            return;
        }
        if (this.bed == null) {
            return;
        }
        double dist = this.unit.distanceToSqr(
                this.bed.getX() + 0.5, this.bed.getY() + 0.5, this.bed.getZ() + 0.5);
        if (dist <= REACH_SQR) {
            // Claim the bunk, lie down. startSleeping sets the SLEEPING pose
            // and pins the unit to the bed position.
            UnitNeeds.claimBed(this.unit, this.bed);
            this.unit.getNavigation().stop();
            this.unit.startSleeping(this.bed);
            this.sleepTicks = 0;
        } else if (this.unit.getNavigation().isDone()) {
            this.unit.getNavigation().moveTo(
                    this.bed.getX() + 0.5, this.bed.getY(), this.bed.getZ() + 0.5, 1.0D);
        }
    }

    @Override
    public void stop() {
        if (this.unit.isSleeping()) {
            this.unit.stopSleeping();
        }
        UnitNeeds.releaseBed(this.unit);
        this.bed = null;
        this.sleepTicks = 0;
    }

    // ---- bed search ------------------------------------------------------------

    // The unit's remembered bunk if it is still valid, else the nearest free
    // bed (villager POI system) inside the owner's claimed chunks. Beds that
    // are villager-occupied or already claimed by another tired unit are
    // skipped - everyone gets their own bunk.
    private BlockPos findBed() {
        if (this.scanTicks > 0) {
            return null;
        }
        this.scanTicks = SCAN_TICKS;
        if (!(this.unit.level() instanceof ServerLevel level)) {
            return null;
        }
        long remembered = UnitNeeds.bedPos(this.unit);
        if (remembered != 0) {
            BlockPos pos = BlockPos.of(remembered);
            if (bedUsable(pos)) {
                return pos;
            }
            UnitNeeds.releaseBed(this.unit);
        }

        UUID owner = this.unit.getOwnerUUID();
        String dim = level.dimension().location().toString();
        PlayerChunkClaims claims =
                PlayerChunkClaims.get(level.getServer().overworld());
        int radius = ClassSystemConfig.needsSearchRadius();
        Predicate<Holder<net.minecraft.world.entity.ai.village.poi.PoiType>> isBed =
                h -> h.is(PoiTypes.HOME);
        Predicate<BlockPos> inClaims = p -> claims.isClaimedBy(
                owner, dim, p.getX() >> 4, p.getZ() >> 4) && bedUsable(p);
        return level.getPoiManager()
                .findAll(isBed, inClaims, this.unit.blockPosition(), radius,
                        PoiManager.Occupancy.HAS_SPACE)
                .min((a, b) -> Double.compare(
                        this.unit.distanceToSqr(Vec3.atCenterOf(a)),
                        this.unit.distanceToSqr(Vec3.atCenterOf(b))))
                .orElse(null);
    }

    // A bed counts only if the block still exists, it sits in a chunk the
    // owner claims, and no other unit has already claimed it.
    private boolean bedUsable(BlockPos pos) {
        if (!(this.unit.level() instanceof ServerLevel level)
                || !(level.getBlockState(pos).getBlock() instanceof BedBlock)) {
            return false;
        }
        UUID owner = this.unit.getOwnerUUID();
        String dim = level.dimension().location().toString();
        if (!PlayerChunkClaims.get(level.getServer().overworld())
                .isClaimedBy(owner, dim, pos.getX() >> 4, pos.getZ() >> 4)) {
            return false;
        }
        long key = pos.asLong();
        for (PmcUnitEntity other : level.getEntitiesOfClass(PmcUnitEntity.class,
                this.unit.getBoundingBox().inflate(64.0))) {
            if (other != this.unit && UnitNeeds.bedPos(other) == key) {
                return false;
            }
        }
        return true;
    }
}
