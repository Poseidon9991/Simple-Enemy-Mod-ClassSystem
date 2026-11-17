// Mixin package - retunes SEM's tactical brain so each class plays its role
package com.poseidon.sem_classsystem.mixin;

// Our helpers: read the unit's effective class + per-class tactical tuning
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.entity.VdfUnitEntity;
import com.poseidon.sem_classsystem.entity.ai.UnitMeleeAttackGoal;
import com.poseidon.sem_classsystem.entity.ai.UnitSelfHealGoal;
import com.poseidon.sem_classsystem.entity.ai.UnitWeaponFallbackGoal;
import com.poseidon.sem_classsystem.event.NightRaidEvents;
import com.poseidon.sem_classsystem.faction.UnitClass;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ReloadState;
import com.tacz.guns.api.item.IGun;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.util.SoldierState;
import net.minecraft.world.phys.Vec3;
// SEM's per-unit tactical manager - THE CLASS WE PATCH. It decides when the
// unit seeks cover, flanks around the target, or commits to a forced rush.
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.TacticalManagerGoal;
// Mixin annotations:
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// SEM's TacticalManagerGoal stores its behaviour knobs as per-instance finals
// loaded from AIDifficultySettings (patience timeout, flank duration, rush
// speed, retreat health, the close/mid range band edge). We shadow those
// fields, capture the constructor's values once, and re-scale them per class
// every tick - so a Camper waits out the target while a Sweeper flanks fast
// and charges hard.
//
// The second hook is the forced-rush gate: when a flank times out SEM sets
// isForcedRush and sends the unit charging. Classes with tacticalForcedRush
// = false (Camper, Suppressor) never charge - we clear the flag and re-arm a
// fresh flank arc instead, so they keep repositioning like a sniper should.
@Mixin(TacticalManagerGoal.class)
public abstract class MixinTacticalManagerGoal {

    // @Shadow mirrors SEM's private fields. remap=false: SEM-internal names,
    // identical in dev and production. @Mutable unlocks writing to finals.
    @Shadow(remap = false) @Final private AbstractUnit unit;
    @Shadow(remap = false) @Final @Mutable private long PATIENCE_TIMEOUT;
    @Shadow(remap = false) @Final @Mutable private long FLANKING_DURATION;
    @Shadow(remap = false) @Final @Mutable private double RUSH_SPEED;
    @Shadow(remap = false) @Final @Mutable private float RETREAT_HEALTH_PCT;
    @Shadow(remap = false) @Final @Mutable private double ENGAGE_DIST_MID;
    @Shadow(remap = false) private boolean isForcedRush;

    // Which class the manager was last tuned for (re-class / weapon-swap support)
    @Unique
    private WeaponClass semcs$tunedClass = null;
    // Constructor originals - retunes always start from these, never from
    // already-scaled values
    @Unique
    private long semcs$origPatience;
    @Unique
    private long semcs$origFlankDuration;
    @Unique
    private double semcs$origRushSpeed;
    @Unique
    private float semcs$origRetreatHealth;
    @Unique
    private double semcs$origCloseBand;
    @Unique
    private boolean semcs$origCaptured = false;

    // tick() runs every evaluation - retune here so a weapon swap (pistol
    // subclass) or a /semcs class change updates tactics mid-fight, then run
    // the forced-rush gate before SEM's own logic consumes the flag.
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void semcs$tuneAndGateRush(CallbackInfo ci) {
        if (!this.unit.level().isClientSide) {
            semcs$smartRetarget();
            semcs$syncTeamworkLink();
            semcs$updateWeaponSwap();
            semcs$relocateAfterKill();
            semcs$watchStuckTarget();
        }
        if (UnitMeleeAttackGoal.isMeleeWeapon(this.unit.getMainHandItem())) {
            if (this.unit.isMovementLockedByManager()) {
                this.unit.releaseMovementLock();
            }
            ci.cancel();
            return;
        }
        WeaponClass cls = semcs$applyTuning();
        if (cls == null) {
            return;
        }
        if (this.isForcedRush && !ClassSystemConfig.classForcedRush(cls)) {
            this.isForcedRush = false;
            this.unit.releaseMovementLock();
            if (!this.unit.isFlankingActive()) {
                this.unit.setFlankingActive(true);
            }
            this.unit.setFlankingStartTick(this.unit.level().getGameTime());
            this.unit.setSoldierState(SoldierState.TACTICAL_MOV);
        }
        semcs$evasiveReload();
        semcs$kiteIfTooClose(cls);
        semcs$strafeAndSpread(cls);
    }

    // TAIL of tick(): dynamic CQB movement for Sweeper (SHOTGUN) and
    // Breacher (SMG) so shotguns serpentine-rush and circle-strafe in CQB,
    // and SMGs actively flank broken LoS instead of stalling in cover.
    @Inject(method = "tick", at = @At("TAIL"))
    private void semcs$enforceSweeperCharge(CallbackInfo ci) {
        if (this.unit.level().isClientSide || this.unit.isUsingItem()) {
            return;
        }
        if (this.unit instanceof PmcUnitEntity pmc && pmc.getOrder() == OrderType.HOLD_POSITION) {
            return;
        }
        WeaponClass cls = WeaponClassSupport.getEffective(this.unit);
        if (cls != WeaponClass.SHOTGUN && cls != WeaponClass.SMG) {
            return;
        }
        LivingEntity target = this.unit.getTarget();
        if (target == null || !target.isAlive()) {
            return;
        }
        float hpPct = this.unit.getHealth() / this.unit.getMaxHealth();
        if (hpPct < (cls == WeaponClass.SHOTGUN ? 0.18F : 0.30F)
                && UnitSelfHealGoal.hasAnyConsumable(this.unit)) {
            return;
        }

        double dist = this.unit.distanceTo(target);
        if (cls == WeaponClass.SMG) {
            // Breachers only break cover to flank when the target is within 24 blocks and hidden.
            SoldierState state = this.unit.getSoldierState();
            if ((state == SoldierState.SEEK_COVER || state == SoldierState.HOLD_COVER)
                    && dist <= 24.0D && !this.unit.getSensing().hasLineOfSight(target)) {
                this.unit.releaseMovementLock();
                this.unit.setFlankingActive(false);
                if (this.unit.isShiftKeyDown()) {
                    this.unit.setShiftKeyDown(false);
                }
                if ((this.unit.tickCount + this.unit.getId()) % 10 == 0 || this.unit.getNavigation().isDone()) {
                    semcs$moveWithFlankArc(target, Math.min(8.0D, dist), 22.0D,
                            Math.max(1.08D, this.RUSH_SPEED), 18);
                }
            }
            return;
        }

        // SHOTGUN (Sweeper): cancel passive cover lock and fight dynamically
        SoldierState state = this.unit.getSoldierState();
        if (state == SoldierState.SEEK_COVER || state == SoldierState.HOLD_COVER) {
            this.unit.releaseMovementLock();
            this.unit.setFlankingActive(false);
        }
        if (this.unit.isShiftKeyDown()) {
            this.unit.setShiftKeyDown(false);
        }

        this.unit.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (dist > 7.5D) {
            this.isForcedRush = true;
            this.unit.setSoldierState(SoldierState.TACTICAL_MOV);
            this.unit.setSprinting(dist > 9.5D);
            if ((this.unit.tickCount + this.unit.getId()) % 8 == 0 || this.unit.getNavigation().isDone()) {
                semcs$moveWithFlankArc(target, 6.5D, 26.0D,
                        Math.max(1.12D, this.RUSH_SPEED), 16);
            }
        } else {
            this.unit.setSoldierState(SoldierState.ENGAGE);
            this.unit.setSprinting(false);
            if ((this.unit.tickCount + this.unit.getId()) % 10 == 0) {
                double dx = target.getX() - this.unit.getX();
                double dz = target.getZ() - this.unit.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len > 0.05D) {
                    double tx = dx / len, tz = dz / len;
                    double px = -tz, pz = tx;
                    double side = (((this.unit.tickCount + this.unit.getId() * 3) / 14) % 2 == 0) ? 1.0D : -1.0D;
                    double radial = dist < 2.8D ? -1.4D : (dist > 5.8D ? 1.0D : 0.0D);
                    double wx = this.unit.getX() + px * 3.0D * side + tx * radial;
                    double wz = this.unit.getZ() + pz * 3.0D * side + tz * radial;
                    if (semcs$walkablePos(wx, this.unit.getY(), wz)) {
                        this.unit.getMoveControl().setWantedPosition(wx, this.unit.getY(), wz, 1.12D);
                    }
                }
            }
        }
    }

    @Unique
    private void semcs$moveWithFlankArc(LivingEntity target, double stepDist, double arcDeg,
                                        double speed, int periodTicks) {
        double dx = target.getX() - this.unit.getX();
        double dz = target.getZ() - this.unit.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len <= stepDist + 1.5D) {
            this.unit.getNavigation().moveTo(target, speed);
            return;
        }
        double side = (((this.unit.tickCount + this.unit.getId() * 5) / periodTicks) % 2 == 0) ? 1.0D : -1.0D;
        double base = Math.atan2(dz, dx) + Math.toRadians(arcDeg * side);
        double wx = this.unit.getX() + Math.cos(base) * stepDist;
        double wz = this.unit.getZ() + Math.sin(base) * stepDist;
        if (semcs$walkablePos(wx, this.unit.getY(), wz)) {
            this.unit.getNavigation().moveTo(wx, this.unit.getY(), wz, speed);
        } else {
            this.unit.getNavigation().moveTo(target, speed);
        }
    }

    // True while the unit is holding our prone stance (Pose.SWIMMING on
    // ground, not actually swimming). Movement helpers must not disturb a
    // prone unit - the gun-goal pin + its exit rules decide when it gets up.
    @Unique
    private boolean semcs$isProneStance() {
        return this.unit.getPose() == net.minecraft.world.entity.Pose.SWIMMING
                && this.unit.onGround() && !this.unit.isInWater();
    }

    // Keep-away: while ENGAGED, a class with a positive tacticalKiteDistance
    // back-pedals when the target pushes inside its standoff range instead of
    // standing still and letting the fight get personal. This is what stops a
    // Camper drifting toward the enemy - he fires, shuffles backward, fires
    // again, always out near his sniper distance. Only touches ENGAGE state -
    // SEM's cover/flank/patrol strategies keep full control otherwise.
    @Unique
    private void semcs$kiteIfTooClose(WeaponClass cls) {
        if (semcs$isProneStance()) {
            return;   // prone units hold their ground - no kiting flat
        }
        double kiteDistance = ClassSystemConfig.classKiteDistance(cls);
        int interval = cls == WeaponClass.SNIPER ? 12 : 4;
        if (kiteDistance <= 0
                || this.unit.getSoldierState() != SoldierState.ENGAGE
                || (this.unit.tickCount + this.unit.getId()) % interval != 0) {
            return;
        }
        LivingEntity target = this.unit.getTarget();
        if (target == null || this.unit.distanceToSqr(target) >= kiteDistance * kiteDistance) {
            return;
        }
        if (this.unit.isMovementLockedByManager()) {
            this.unit.releaseMovementLock();
        }
        double dx = this.unit.getX() - target.getX();
        double dz = this.unit.getZ() - target.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) {
            return;
        }
        double reach = kiteDistance * 0.75;
        // Obstacle-aware: sample retreat bearings (straight back, then
        // fanning sideways) and keep the first walkable one that gains
        // the most ground - a blind back-pedal just walks into walls,
        // water, or off ledges and the kiter gets cornered.
        double baseAngle = Math.atan2(dz, dx);
        double bestScore = -1.0D;
        double bestX = 0.0D, bestZ = 0.0D;
        for (double spreadDeg : new double[]{0.0D, -40.0D, 40.0D, -80.0D, 80.0D, -125.0D, 125.0D}) {
            double a = baseAngle + Math.toRadians(spreadDeg);
            double cx = this.unit.getX() + Math.cos(a) * reach;
            double cz = this.unit.getZ() + Math.sin(a) * reach;
            if (!semcs$walkablePos(cx, this.unit.getY(), cz)) {
                continue;
            }
            double tdx = cx - target.getX();
            double tdz = cz - target.getZ();
            double score = tdx * tdx + tdz * tdz;
            if (score > bestScore) {
                bestScore = score;
                bestX = cx;
                bestZ = cz;
            }
        }
        if (bestScore > 0.0D) {
            this.unit.getMoveControl().setWantedPosition(
                    bestX, this.unit.getY(), bestZ, this.RUSH_SPEED);
        }
    }

    // A spot the unit can physically stand in: bounding box fits, solid
    // floor below, no fluid at feet or face. Cheap static probe - the path
    // finder still does the real navigation, this just vetoes dead ends.
    @Unique
    private boolean semcs$walkablePos(double x, double y, double z) {
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(x, y, z);
        if (!this.unit.level().hasChunkAt(pos)) {
            return false;
        }
        Vec3 from = this.unit.position();
        Vec3 to = new Vec3(x, y, z);
        if (!this.unit.level().noCollision(this.unit, this.unit.getBoundingBox().move(to.subtract(from)))) {
            return false;
        }
        if (!this.unit.level().getBlockState(pos.below())
                .entityCanStandOnFace(this.unit.level(), pos.below(), this.unit,
                        net.minecraft.core.Direction.UP)) {
            return false;
        }
        return this.unit.level().getFluidState(pos).isEmpty()
                && this.unit.level().getFluidState(pos.above()).isEmpty()
                && this.unit.level().getFluidState(pos.below()).isEmpty();
    }

    // Re-scales the shadowed knobs from the captured ORIGINALS (a class swap
    // retunes correctly instead of stacking multipliers). Returns the class
    // this tick resolved to, or null for unclassified units.
    @Unique
    private WeaponClass semcs$applyTuning() {
        if (!this.semcs$origCaptured) {
            this.semcs$origPatience = this.PATIENCE_TIMEOUT;
            this.semcs$origFlankDuration = this.FLANKING_DURATION;
            this.semcs$origRushSpeed = this.RUSH_SPEED;
            this.semcs$origRetreatHealth = this.RETREAT_HEALTH_PCT;
            this.semcs$origCloseBand = this.ENGAGE_DIST_MID;
            this.semcs$origCaptured = true;
        }
        // Effective class: a pistol in hand swaps the unit's tactical
        // personality for the sidearm's, same as the combat mixins.
        WeaponClass cls = WeaponClassSupport.getEffective(this.unit);
        if (cls == null || cls == this.semcs$tunedClass) {
            return cls;
        }
        this.semcs$tunedClass = cls;

        this.PATIENCE_TIMEOUT = Math.max(1L,
                Math.round(this.semcs$origPatience * ClassSystemConfig.classPatienceMultiplier(cls)));
        this.FLANKING_DURATION = Math.max(1L,
                Math.round(this.semcs$origFlankDuration * ClassSystemConfig.classFlankDurationMultiplier(cls)));
        this.RUSH_SPEED = this.semcs$origRushSpeed * ClassSystemConfig.classRushSpeedMultiplier(cls);
        this.RETREAT_HEALTH_PCT = (float) (this.semcs$origRetreatHealth
                * ClassSystemConfig.classRetreatHealthMultiplier(cls));
        this.ENGAGE_DIST_MID = this.semcs$origCloseBand * ClassSystemConfig.classCloseRangeMultiplier(cls);
        return cls;
    }

    // -------------------------------------------------------------------
    // 1. Evasive Reload / Reload-in-Cover
    //    When reloading in the open with a live target, seek cover or
    //    sidestep laterally instead of standing frozen.
    // -------------------------------------------------------------------
    @Unique
    private void semcs$evasiveReload() {
        if (semcs$isProneStance()) {
            return;   // reloading flat is fine - don't sidestep out of prone
        }
        if (this.unit instanceof PmcUnitEntity pmc && pmc.getOrder() == OrderType.HOLD_POSITION) {
            return;
        }
        LivingEntity target = this.unit.getTarget();
        if (target == null || !target.isAlive()) {
            return;
        }
        IGunOperator operator = IGunOperator.fromLivingEntity(this.unit);
        ReloadState rs = operator != null ? operator.getSynReloadState() : null;
        boolean reloading = rs != null && rs.getStateType() != null && rs.getStateType().isReloading();
        boolean needsCoverToHeal = this.unit.getHealth() <= this.unit.getMaxHealth() * 0.65F
                && UnitSelfHealGoal.hasAnyConsumable(this.unit);
        if (!reloading && !needsCoverToHeal) {
            return;
        }
        if (this.unit.getSoldierState() == SoldierState.ENGAGE
                && this.unit.getSensing().hasLineOfSight(target)) {
            this.unit.setSoldierState(SoldierState.SEEK_COVER);
        }
        if (!this.unit.isMovementLockedByManager() && this.unit.tickCount % 10 == 0) {
            double dx = target.getX() - this.unit.getX();
            double dz = target.getZ() - this.unit.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.01D) {
                double dir = ((this.unit.getId() + (this.unit.tickCount / 10)) % 2 == 0) ? 1.0D : -1.0D;
                double sx = (-dz / len) * 2.5D * dir - (dx / len) * 1.2D;
                double sz = (dx / len) * 2.5D * dir - (dz / len) * 1.2D;
                this.unit.getMoveControl().setWantedPosition(
                        this.unit.getX() + sx,
                        this.unit.getY(),
                        this.unit.getZ() + sz,
                        1.15D);
            }
        }
    }

    // -------------------------------------------------------------------
    // 2. Smart Threat Re-Targeting
    //    Switch to a closer/visible attacker if our current target is
    //    behind cover or much farther away than whoever just shot us.
    // -------------------------------------------------------------------
    @Unique
    private void semcs$smartRetarget() {
        if ((this.unit.tickCount + this.unit.getId()) % 15 != 2) {
            return;
        }
        boolean isRaiding = this.unit.getPersistentData().contains(NightRaidEvents.RAID_TARGET_KEY);
        LivingEntity current = this.unit.getTarget();
        LivingEntity hurtBy = this.unit.getLastHurtByMob();
        if (hurtBy != null && hurtBy.isAlive() && hurtBy != current && !this.unit.isAlliedTo(hurtBy)) {
            boolean validRaidAttacker = !isRaiding
                    || hurtBy instanceof Player
                    || hurtBy instanceof PmcUnitEntity
                    || hurtBy instanceof VdfUnitEntity;
            int hurtAge = this.unit.tickCount - this.unit.getLastHurtByMobTimestamp();
            if (validRaidAttacker && hurtAge <= 40) {
                boolean currentHidden = current == null || !current.isAlive()
                        || !this.unit.getSensing().hasLineOfSight(current);
                boolean attackerCloser = current != null
                        && this.unit.distanceToSqr(hurtBy) < this.unit.distanceToSqr(current) * 0.65D;
                if (currentHidden || attackerCloser) {
                    this.unit.setTarget(hurtBy);
                    return;
                }
            }
        }
        if (current != null && current.isAlive() && !this.unit.getSensing().hasLineOfSight(current)) {
            double currentDistSqr = this.unit.distanceToSqr(current);
            var nearby = this.unit.level().getEntitiesOfClass(
                    LivingEntity.class,
                    this.unit.getBoundingBox().inflate(24.0D, 8.0D, 24.0D),
                    e -> e != this.unit && e != current && e.isAlive()
                            && (!isRaiding || e instanceof Player || e instanceof PmcUnitEntity || e instanceof VdfUnitEntity)
                            && e.getLastHurtMob() == this.unit
                            && !this.unit.isAlliedTo(e));
            for (LivingEntity candidate : nearby) {
                if (this.unit.distanceToSqr(candidate) < currentDistSqr
                        && this.unit.getSensing().hasLineOfSight(candidate)) {
                    this.unit.setTarget(candidate);
                    break;
                }
            }
        }
    }

    // -------------------------------------------------------------------
    // 3. Dynamic Sidearm / CQB Weapon Switching
    //    All classes participate (Camper, Demolisher, Rifleman, etc.) via
    //    UnitWeaponFallbackGoal's shared swap helpers.
    // -------------------------------------------------------------------
    @Unique
    private void semcs$updateWeaponSwap() {
        if ((this.unit.tickCount + this.unit.getId()) % 10 != 7) {
            return;
        }
        WeaponClass assigned = WeaponClassSupport.get(this.unit);
        if (assigned == null || assigned == WeaponClass.PISTOL) {
            return;
        }
        long now = this.unit.level().getGameTime();
        if (!UnitWeaponFallbackGoal.canSwapNow(this.unit, now)) {
            return;
        }
        LivingEntity target = this.unit.getTarget();
        double distSqr = (target != null && target.isAlive()) ? this.unit.distanceToSqr(target) : Double.MAX_VALUE;

        ItemStack main = this.unit.getMainHandItem();
        boolean holdingCqbBackup = UnitWeaponFallbackGoal.isCqbBackupItem(main, assigned);

        boolean primaryDryOrReloading = false;
        if (!holdingCqbBackup) {
            IGun iGun = IGun.getIGunOrNull(main);
            if (iGun != null && iGun.getCurrentAmmoCount(main) <= 0) {
                primaryDryOrReloading = true;
            } else {
                IGunOperator op = IGunOperator.fromLivingEntity(this.unit);
                ReloadState rs = op != null ? op.getSynReloadState() : null;
                if (rs != null && rs.getStateType() != null && rs.getStateType().isReloading()) {
                    primaryDryOrReloading = true;
                }
            }
        }

        double drawRange = ClassSystemConfig.classSidearmDrawDistance(assigned);
        if (assigned == WeaponClass.HEAVY) {
            drawRange = Math.max(11.0D, drawRange);
        }
        boolean wantCqb = target != null && target.isAlive()
                && ((drawRange > 0.0D && distSqr <= drawRange * drawRange && this.unit.getSensing().hasLineOfSight(target))
                || (primaryDryOrReloading && distSqr <= 16.0D * 16.0D));

        double backOff = Math.max(14.0D, Math.max(drawRange + 4.0D,
                ClassSystemConfig.classSidearmReturnDistance(assigned)));
        boolean wantPrimary = holdingCqbBackup
                && (((target == null || !target.isAlive())
                        && UnitWeaponFallbackGoal.hasUsablePrimary(this.unit, assigned))
                || (target != null && target.isAlive()
                        && UnitWeaponFallbackGoal.hasReadyPrimaryForDistance(this.unit, assigned, distSqr, backOff)));

        if (wantCqb && !holdingCqbBackup) {
            UnitWeaponFallbackGoal.swapToBackup(this.unit, assigned, now);
        } else if (wantPrimary) {
            UnitWeaponFallbackGoal.swapToPrimary(this.unit, assigned, now, false);
        }
    }

    // -------------------------------------------------------------------
    // 4a. Post-Kill Relocation (marksmen only)
    //     When a Sniper's target dies he slips to a fresh overwatch spot a
    //     few steps off his old lane - counters enemies triangulating the
    //     muzzle position. Only runs while he isn't pinned prone.
    // -------------------------------------------------------------------
    @Unique
    private LivingEntity semcs$lastKillWatch = null;

    @Unique
    private void semcs$relocateAfterKill() {
        LivingEntity target = this.unit.getTarget();
        LivingEntity last = this.semcs$lastKillWatch;
        this.semcs$lastKillWatch = target;
        if (last == null || last.isAlive() || target != null
                || WeaponClassSupport.getEffective(this.unit) != WeaponClass.SNIPER
                || semcs$isProneStance()
                || (this.unit.tickCount + this.unit.getId()) % 10 != 0) {
            return;
        }
        // Step perpendicular to the old firing lane, 6-12 blocks either side.
        double bx = last.getX() - this.unit.getX();
        double bz = last.getZ() - this.unit.getZ();
        double bl = Math.sqrt(bx * bx + bz * bz);
        if (bl < 0.001D) {
            return;
        }
        bx /= bl; bz /= bl;
        double px = -bz, pz = bx;
        double side = this.unit.getRandom().nextBoolean() ? 1.0D : -1.0D;
        double reach = 6.0D + this.unit.getRandom().nextDouble() * 6.0D;
        for (double sway : new double[]{0.0D, -30.0D, 30.0D}) {
            double a = Math.toRadians(sway);
            double ca = Math.cos(a), sa = Math.sin(a);
            double ox = (px * ca - pz * sa) * side * reach;
            double oz = (px * sa + pz * ca) * side * reach;
            double cx = this.unit.getX() + ox;
            double cz = this.unit.getZ() + oz;
            if (semcs$walkablePos(cx, this.unit.getY(), cz)) {
                this.unit.getNavigation().moveTo(cx, this.unit.getY(), cz, 1.0D);
                return;
            }
        }
    }

    // -------------------------------------------------------------------
    // 4b. Unreachable-Target Fallback
    //     If the unit has spent ~6s trying to reach a target it cannot see
    //     and made no real progress (nav done or pathing in place), it stops
    //     fixating: picks the closest hostile it CAN see within 24 blocks,
    //     or drops the target so SEM's own acquisition can re-pick.
    // -------------------------------------------------------------------
    @Unique
    private long semcs$stuckAnchorTick = -1L;
    @Unique
    private double semcs$stuckAnchorX = 0.0D, semcs$stuckAnchorZ = 0.0D;
    @Unique
    private int semcs$stuckChecks = 0;

    @Unique
    private void semcs$watchStuckTarget() {
        if ((this.unit.tickCount + this.unit.getId()) % 40 != 11) {
            return;
        }
        if (this.unit.getPersistentData().contains(NightRaidEvents.RAID_TARGET_KEY)) {
            return;
        }
        LivingEntity target = this.unit.getTarget();
        if (target == null || !target.isAlive()
                || this.unit.getSensing().hasLineOfSight(target)) {
            this.semcs$stuckChecks = 0;
            this.semcs$stuckAnchorTick = this.unit.tickCount;
            this.semcs$stuckAnchorX = this.unit.getX();
            this.semcs$stuckAnchorZ = this.unit.getZ();
            return;
        }
        // Blind pursuit - measure progress since the last sample.
        double moved2 = (this.unit.getX() - this.semcs$stuckAnchorX)
                * (this.unit.getX() - this.semcs$stuckAnchorX)
                + (this.unit.getZ() - this.semcs$stuckAnchorZ)
                * (this.unit.getZ() - this.semcs$stuckAnchorZ);
        this.semcs$stuckAnchorX = this.unit.getX();
        this.semcs$stuckAnchorZ = this.unit.getZ();
        this.semcs$stuckChecks = moved2 < 0.81D ? this.semcs$stuckChecks + 1 : 0;
        if (this.semcs$stuckChecks < 3) {
            return;
        }
        this.semcs$stuckChecks = 0;
        // Repick: nearest living hostile we can actually see & may attack.
        var candidates = this.unit.level().getEntitiesOfClass(
                LivingEntity.class,
                this.unit.getBoundingBox().inflate(24.0D, 8.0D, 24.0D),
                e -> e != this.unit && e.isAlive() && !semcs$isSameTeam(this.unit, e)
                        && this.unit.canAttack(e)
                        && this.unit.getSensing().hasLineOfSight(e));
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity c : candidates) {
            double d = this.unit.distanceToSqr(c);
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        this.unit.setTarget(best);   // null = drop fixation entirely
    }

    // -------------------------------------------------------------------
    // 4. Combat Strafe & Squad Anti-Bunching
    //    Spreads stacked squadmates into a firing line and gives mobile
    //    classes (especially SMG Breachers) agile combat strafes.
    // -------------------------------------------------------------------
    @Unique
    private void semcs$strafeAndSpread(WeaponClass cls) {
        if (semcs$isProneStance()) {
            return;   // no strafing while flat
        }
        if (this.unit.getSoldierState() != SoldierState.ENGAGE
                || this.unit.isMovementLockedByManager()
                || this.unit.getPose() == net.minecraft.world.entity.Pose.SWIMMING
                || (this.unit.tickCount + this.unit.getId()) % 12 != 9) {
            return;
        }
        if (this.unit instanceof PmcUnitEntity pmc && pmc.getOrder() == OrderType.HOLD_POSITION) {
            return;
        }
        LivingEntity target = this.unit.getTarget();
        if (target == null || !target.isAlive()) {
            return;
        }
        double dx = target.getX() - this.unit.getX();
        double dz = target.getZ() - this.unit.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01D) {
            return;
        }
        double tx = dx / len, tz = dz / len;
        double px = -tz, pz = tx;

        // A. Anti-bunching: if stacked within 1.6 blocks of an allied unit, step apart
        var crowded = this.unit.level().getEntitiesOfClass(
                AbstractUnit.class,
                this.unit.getBoundingBox().inflate(1.6D, 1.0D, 1.6D),
                other -> other != this.unit && other.isAlive() && this.unit.isAlliedTo(other));
        if (!crowded.isEmpty()) {
            AbstractUnit mate = crowded.get(0);
            double dot = (this.unit.getX() - mate.getX()) * px + (this.unit.getZ() - mate.getZ()) * pz;
            double side = dot >= 0.0D ? 1.0D : -1.0D;
            this.unit.getMoveControl().setWantedPosition(
                    this.unit.getX() + px * 2.2D * side,
                    this.unit.getY(),
                    this.unit.getZ() + pz * 2.2D * side,
                    1.05D);
            return;
        }

        // B. Agile run-and-gun diagonal strafes for SMG Breachers; standard micro-strafe for others
        boolean smg = cls == WeaponClass.SMG;
        int cadence = smg ? 12 : 24;
        if (cls != WeaponClass.SNIPER && cls != WeaponClass.HEAVY_MG
                && len <= (smg ? 24.0D : 28.0D)
                && (smg || (this.unit.tickCount + this.unit.getId() * 7) % cadence == 0)
                && this.unit.getSensing().hasLineOfSight(target)) {
            double side = (((this.unit.tickCount / cadence + this.unit.getId()) % 2) == 0) ? 1.0D : -1.0D;
            double lat = smg ? 2.8D : 2.0D;
            double fwd = smg ? (len > 12.0D ? 1.1D : (len < 5.5D ? -1.1D : 0.0D)) : 0.0D;
            double wx = this.unit.getX() + px * lat * side + tx * fwd;
            double wz = this.unit.getZ() + pz * lat * side + tz * fwd;
            if (!smg || semcs$walkablePos(wx, this.unit.getY(), wz)) {
                this.unit.getMoveControl().setWantedPosition(wx, this.unit.getY(), wz, smg ? 1.12D : 1.05D);
            }
        }
    }

    // -------------------------------------------------------------------
    // 5. Squad & Faction Teamwork Link (Radio Target Sharing)
    //    When one unit spots or engages an enemy squad, it broadcasts the
    //    contact to allied units within 48 blocks. Staggered every 16 ticks.
    // -------------------------------------------------------------------
    @Unique
    private void semcs$syncTeamworkLink() {
        if ((this.unit.tickCount + this.unit.getId()) % 16 != 5) {
            return;
        }
        LivingEntity myTarget = this.unit.getTarget();

        // If we are idle with no target, check if a nearby teammate is already in combat
        if (myTarget == null || !myTarget.isAlive()) {
            var allies = this.unit.level().getEntitiesOfClass(
                    AbstractUnit.class,
                    this.unit.getBoundingBox().inflate(48.0D, 20.0D, 48.0D),
                    other -> other != this.unit && other.isAlive() && semcs$isSameTeam(this.unit, other));
            for (AbstractUnit ally : allies) {
                LivingEntity allyTarget = ally.getTarget();
                if (allyTarget != null && allyTarget.isAlive() && !semcs$isSameTeam(this.unit, allyTarget)) {
                    LivingEntity picked = semcs$pickEnemyFromSquad(this.unit, allyTarget,
                            semcs$enemySquadMembers(allyTarget));
                    semcs$alertAndAdvance(this.unit, picked);
                    return;
                }
            }
            return;
        }

        // We have a live target: broadcast it (and any nearby members of its squad)
        // to all allied units within 48 blocks.
        var allies = this.unit.level().getEntitiesOfClass(
                AbstractUnit.class,
                this.unit.getBoundingBox().inflate(48.0D, 20.0D, 48.0D),
                other -> other != this.unit && other.isAlive() && semcs$isSameTeam(this.unit, other));

        // The enemy-squad box scan depends only on myTarget, not the ally -
        // compute it lazily once instead of once per un-targeted ally.
        java.util.List<LivingEntity> enemyGroup = null;
        for (AbstractUnit ally : allies) {
            LivingEntity allyTarget = ally.getTarget();
            if (allyTarget == null || !allyTarget.isAlive()) {
                if (enemyGroup == null) {
                    enemyGroup = semcs$enemySquadMembers(myTarget);
                }
                LivingEntity picked = semcs$pickEnemyFromSquad(ally, myTarget, enemyGroup);
                semcs$alertAndAdvance(ally, picked);
            } else if (!ally.getSensing().hasLineOfSight(allyTarget)
                    && ally.getSensing().hasLineOfSight(myTarget)
                    && ally.distanceToSqr(myTarget) <= ally.distanceToSqr(allyTarget) * 1.25D) {
                ally.setTarget(myTarget);
            }
        }
    }

    @Unique
    private static void semcs$alertAndAdvance(AbstractUnit ally, LivingEntity enemy) {
        ally.setTarget(enemy);
        ally.getEntityData().set(AbstractUnit.IS_IN_COMBAT, true);
        if (ally instanceof PmcUnitEntity pmc && pmc.getOrder() == OrderType.HOLD_POSITION) {
            return;
        }
        if (!ally.getSensing().hasLineOfSight(enemy) && !ally.isMovementLockedByManager()) {
            ally.setSoldierState(SoldierState.TACTICAL_MOV);
            if (ally.getNavigation().isDone()) {
                ally.getNavigation().moveTo(enemy, 1.15D);
            }
        }
    }

    // Hostile members of the spotted enemy's squad within 18 blocks of
    // primaryEnemy. Only seeker-independent filters live here so the box
    // scan can be shared by every ally in one sync call.
    @Unique
    private static java.util.List<LivingEntity> semcs$enemySquadMembers(LivingEntity primaryEnemy) {
        return primaryEnemy.level().getEntitiesOfClass(
                LivingEntity.class,
                primaryEnemy.getBoundingBox().inflate(18.0D, 8.0D, 18.0D),
                e -> e.isAlive() && !e.isSpectator()
                        && (e == primaryEnemy || semcs$isSameTeam(primaryEnemy, e)));
    }

    // Finds all hostile members of the spotted enemy's squad within 18 blocks
    // of primaryEnemy and picks the best one for 'seeker' (preferring visible
    // enemies, then closest distance) so a squad of allies spreads fire across
    // the enemy squad instead of ignoring the rest of the enemy team.
    @Unique
    private static LivingEntity semcs$pickEnemyFromSquad(AbstractUnit seeker,
            LivingEntity primaryEnemy, java.util.List<LivingEntity> enemyGroup) {
        LivingEntity bestVisible = null;
        double bestVisDist = Double.MAX_VALUE;
        LivingEntity bestAny = primaryEnemy;
        double bestAnyDist = seeker.distanceToSqr(primaryEnemy);

        for (LivingEntity cand : enemyGroup) {
            if (semcs$isSameTeam(seeker, cand) || !seeker.canAttack(cand)) {
                continue;
            }
            double d = seeker.distanceToSqr(cand);
            if (d < bestAnyDist) {
                bestAnyDist = d;
                bestAny = cand;
            }
            if (seeker.getSensing().hasLineOfSight(cand) && d < bestVisDist) {
                bestVisDist = d;
                bestVisible = cand;
            }
        }
        return bestVisible != null ? bestVisible : bestAny;
    }

    @Unique
    private static boolean semcs$isSameTeam(LivingEntity a, LivingEntity b) {
        if (a == b || a.isAlliedTo(b)) {
            return true;
        }
        UnitClass fa = UnitClass.of(a);
        UnitClass fb = UnitClass.of(b);
        if (fa != null && fa == fb) {
            return true;
        }
        // BHQ and PMC are both friendly operator factions
        boolean aFriendlyOp = a instanceof PmcUnitEntity;
        boolean bFriendlyOp = b instanceof PmcUnitEntity;
        return aFriendlyOp && bFriendlyOp;
    }
}
