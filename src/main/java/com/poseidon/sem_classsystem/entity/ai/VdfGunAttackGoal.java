// AI package - VDF militia combat behaviours
package com.poseidon.sem_classsystem.entity.ai;

import com.mojang.logging.LogUtils;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.entity.VdfUnitEntity;
import com.poseidon.sem_classsystem.faction.UnitClass;
import com.poseidon.sem_classsystem.faction.UnitClassAttributes;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ReloadState;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.BulletData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.config.common.AiShootingConfig;
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.utils.AiGunSpreadHelper;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.EnumSet;

// Standalone gun-attack goal for the independent VDF entity - replaces the
// slice of SEM's role-package combat (RangedGunAttackGoal) the militia used.
//
// Since the militia joined the weapon-class system it runs the SAME
// per-class tuning pipeline MixinRangedGunAttackGoal +
// MixinMoveToAttackRange layer onto SEM's goal:
//   - engage distance = the class's preferred fighting distance (Jack
//     reads the held gun's type); approach speed x class multiplier
//   - max firing distance = SEM's 90 x class shoot-distance multiplier
//   - burst length + cooldown rolled from AiShootingConfig x class
//     multipliers (snipers fire single deliberate shots)
//   - per-shot spread goes through SEM's own AiGunSpreadHelper:
//     faction multiplier x class spread x stance bonus on the base,
//     class falloff on the per-block term - literally the same call the
//     mixin makes for PMC/RU/US/BHQ
//   - crouch classes kneel while firing; Campers go prone past their
//     proneDistance and hold the trigger until the scope is up
//   - sniper aim-lead, shoot-and-scoot displacement, keep-away kiting,
//     blast-radius hold, and a friendly-fire line check so a militia
//     never hoses a villager standing in the way
//   - the ShootResult contract is unchanged: NEED_BOLT pumps manual guns,
//     NO_AMMO reloads from the dummy reserve, NOT_DRAW shoulders again;
//     tube-fed shotguns cut a feed short once a shell is chambered
public class VdfGunAttackGoal extends Goal {

    private static final Logger LOG = LogUtils.getLogger();

    // Stand-and-shoot distance for a classless militia member - the goal's
    // original fixed range, kept as the fallback when the unit somehow has
    // no weapon class at all.
    private static final double DEFAULT_ENGAGE = 15.0D;
    // SEM's unscaled max firing distance (AiShootingConfig default 90) -
    // the class shoot-distance multiplier scales this, same as the mixin's
    // MAX_SHOOT_DISTANCE_SQR retune.
    private static final double BASE_SHOOT_DISTANCE = 90.0D;
    // Ticks of continuous line-of-sight required before the first shot -
    // models the "shoulder the rifle" moment so contact isn't instant death.
    private static final int AIM_SETTLE_TICKS = 8;

    private final VdfUnitEntity unit;
    private final double speedModifier;
    private int seeTime;

    // Burst cadence - mirrors RangedGunAttackGoal's burst cycle: N shots,
    // then a pause, both rolled from AiShootingConfig x class multipliers.
    private int burstShotsFired;
    private int burstTarget = 1;
    private int burstCooldownTicks;
    // Successful shots since the Camper last displaced - reposition trigger.
    private int shotsSinceMove;

    // Firing stance - same ownership contract as the mixin: WE set the
    // sneak flag / prone pose, so stop() only ever clears flags we own.
    private boolean crouching;
    private boolean prone;
    private int stationaryTicks;
    private int proneHoldTicks;
    private int proneCooldownTicks;
    private int proneBlockedLosTicks;

    // Per-stack CommonGunIndex cache to avoid repeated TimelessAPI lookups per tick.
    private ItemStack cachedIndexStack = ItemStack.EMPTY;
    @Nullable
    private ResourceLocation cachedIndexGunId = null;
    @Nullable
    private CommonGunIndex cachedGunIndex = null;

    @Nullable
    private CommonGunIndex getHeldGunIndex(ItemStack gun) {
        IGun iGun = IGun.getIGunOrNull(gun);
        if (iGun == null) {
            this.cachedIndexStack = ItemStack.EMPTY;
            this.cachedIndexGunId = null;
            this.cachedGunIndex = null;
            return null;
        }
        ResourceLocation gunId = iGun.getGunId(gun);
        if (gunId == null) {
            return null;
        }
        if (gun == this.cachedIndexStack && gunId.equals(this.cachedIndexGunId)) {
            return this.cachedGunIndex;
        }
        this.cachedIndexStack = gun;
        this.cachedIndexGunId = gunId;
        this.cachedGunIndex = TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        return this.cachedGunIndex;
    }

    public VdfGunAttackGoal(VdfUnitEntity unit, double speedModifier) {
        this.unit = unit;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean holdsGun() {
        return IGun.getIGunOrNull(this.unit.getMainHandItem()) != null;
    }

    @Override
    public boolean canUse() {
        LivingEntity target = this.unit.getTarget();
        return target != null && target.isAlive() && this.holdsGun();
    }

    @Override
    public boolean canContinueToUse() {
        return this.canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.seeTime = 0;
        this.burstShotsFired = 0;
        this.burstTarget = Math.max(1, rollBurstSize(
                WeaponClassSupport.getEffective(this.unit)));
        this.shotsSinceMove = 0;
        this.stationaryTicks = 0;
        this.proneHoldTicks = 0;
        this.proneBlockedLosTicks = 0;
        this.unit.setAggressive(true);
        // Draw the weapon first - TaCZ's draw cooldown blocks early shots
        // on its own, matching SEM's "shoulder arms" delay.
        IGunOperator.fromLivingEntity(this.unit).draw(this.unit::getMainHandItem);
    }

    @Override
    public void stop() {
        this.unit.setAggressive(false);
        IGunOperator.fromLivingEntity(this.unit).aim(false);
        this.unit.getNavigation().stop();
        clearProneAndCrouch();
    }

    @Override
    public void tick() {
        LivingEntity target = this.unit.getTarget();
        if (target == null) {
            return;
        }
        if (this.proneCooldownTicks > 0) {
            this.proneCooldownTicks--;
        }
        // "Planted" = actually not translating (deltaMovement is the truth -
        // same rule the mixin uses; a stale path flicker must not count).
        boolean stationary = this.unit.onGround()
                && this.unit.getDeltaMovement().horizontalDistanceSqr() < 0.01D;
        this.stationaryTicks = stationary ? this.stationaryTicks + 1 : 0;
        if (this.prone) {
            this.proneHoldTicks++;
            // Every-tick pin: other goals re-issue paths between the 5-tick
            // stance evals - kill them so a prone unit never creeps.
            if (!this.unit.getNavigation().isDone()) {
                this.unit.getNavigation().stop();
            }
        } else {
            this.proneHoldTicks = 0;
        }

        double distSqr = this.unit.distanceToSqr(target);
        double dist = Math.sqrt(distSqr);
        boolean canSee = this.unit.getSensing().hasLineOfSight(target);
        this.seeTime = canSee ? this.seeTime + 1 : 0;

        // Effective class = assigned class with gun-subclass overlay
        // (a pistol in hand fights like a Sidearm) - identical to the
        // resolution every SEM combat mixin uses.
        WeaponClass cls = WeaponClassSupport.getEffective(this.unit);
        String gunType = cls == WeaponClass.JACK
                ? WeaponClassSupport.heldGunType(this.unit) : null;

        // Per-class movement tuning (MixinMoveToAttackRange equivalents):
        // preferred fighting distance, max firing distance, advance speed.
        double engage = cls == null ? DEFAULT_ENGAGE
                : cls == WeaponClass.JACK
                        ? WeaponClassSupport.jackEngageDistance(gunType)
                        : ClassSystemConfig.classEngageDistance(cls);
        double maxShoot = BASE_SHOOT_DISTANCE * (cls == null ? 1.0
                : cls == WeaponClass.JACK
                        ? WeaponClassSupport.jackShootDistance(gunType)
                        : ClassSystemConfig.classShootDistance(cls));
        double approach = this.speedModifier * (cls == null ? 1.0
                : cls == WeaponClass.JACK
                        ? WeaponClassSupport.jackApproachSpeed(gunType)
                        : ClassSystemConfig.classApproachSpeed(cls));

        if (this.unit.tickCount % 5 == 0) {
            updateStance(cls, target, dist);
        }

        smartRetargetAndAlert(target, distSqr, canSee);

        IGunOperator operator = IGunOperator.fromLivingEntity(this.unit);
        operator.aim(true);

        ItemStack gunStack = this.unit.getMainHandItem();
        IGun iGun = IGun.getIGunOrNull(gunStack);
        if (iGun == null) {
            this.unit.setSprinting(false);
            return;
        }

        ReloadState.StateType reloadState = operator.getSynReloadState().getStateType();
        boolean reloading = reloadState != null && reloadState.isReloading();

        if (updateWeaponSwap(gunStack, iGun, distSqr, dist, canSee, reloading)) {
            return;
        }

        updateCombatMovement(cls, target, dist, approach, engage, canSee, reloading);
        this.unit.getLookControl().setLookAt(target, 30.0F, 30.0F);

        int ammo = iGun.getCurrentAmmoCount(gunStack);

        // Reload in progress: shoot() would only come back IS_RELOADING, and
        // calling reload() again is a TaCZ no-op - wait it out. Tube-fed
        // shotguns load one shell per feed step, so once at least one round
        // is chambered and the target is still visible, cut the feed short
        // and fight with a partial tube instead of standing in the open.
        if (reloading) {
            if (ammo > 0 && !reloadState.isReloadFinishing() && canSee) {
                operator.cancelReload();
            }
            return;
        }
        if (ammo <= 0) {
            operator.reload();                                       // dummy-ammo reserve refills
            return;
        }
        if (this.seeTime < AIM_SETTLE_TICKS) {
            return;                                                  // still shouldering
        }
        if (dist > maxShoot) {
            return;                  // class-limited reach - advance, hold fire
        }
        if (this.burstCooldownTicks > 0) {
            this.burstCooldownTicks--;                             // between bursts
            return;
        }
        // Friendly-fire hold: a villager, golem, fellow militia or neutral
        // operator crossing the ray pauses the trigger (same check the PMC
        // mixin runs - a militia lives among its protected allies).
        if (allyInFireLine(target)) {
            return;
        }
        // Blast standoff: never fire a launcher inside its own splash zone.
        if ((cls == WeaponClass.HEAVY || cls == WeaponClass.JACK)
                && insideBlastRadius(target, dist)) {
            return;
        }
        // Distance-scaled scoped hold: close-range snaps fire immediately or via quick-scope.
        if (cls == WeaponClass.SNIPER) {
            float requiredAim = dist <= 10.0D ? 0.0F : (dist <= 20.0D ? 0.35F : 0.85F);
            if (requiredAim > 0.0F && operator.getSynAimingProgress() < requiredAim) {
                return;
            }
        }

        // Aim at the target's eye line with the SAME spread pipeline the
        // mixin wraps around SEM's shot: faction multiplier x class spread
        // x stance bonus on the base spread, class falloff on the
        // per-block term, AiShootingConfig.MAX_SPREAD cap inside.
        double classSpread = cls == null ? 1.0 : ClassSystemConfig.classSpread(cls);
        double classFalloff = cls == null ? 1.0 : ClassSystemConfig.classFalloff(cls);
        // Monster-hunter bonus (mixin parity): campers hit zombies far more
        // reliably than players - players must stay dodgeable.
        if (cls == WeaponClass.SNIPER && target instanceof Monster) {
            classSpread *= 0.4;
        }
        boolean isProne = this.unit.getPose() == Pose.SWIMMING;
        double stanceBonus = cls != null && (this.unit.isShiftKeyDown() || isProne)
                ? ClassSystemConfig.classCrouchSpread(cls) * (isProne ? 0.75 : 1.0)
                : 1.0;
        float baseSpread = AiShootingConfig.BASE_SPREAD.get().floatValue()
                * (float) (UnitClassAttributes.spreadMultiplier(this.unit)
                * classSpread * stanceBonus);
        float spreadInc = AiShootingConfig.SPREAD_INCREASE.get().floatValue()
                * (float) classFalloff;

        Vec3 aim = leadAimPoint(target, cls, dist);
        double dx = aim.x - this.unit.getX();
        double dz = aim.z - this.unit.getZ();
        double dy = aim.y - this.unit.getEyeY();
        double flat = Math.sqrt(dx * dx + dz * dz);
        float idealPitch = (float) (-Mth.atan2(dy, flat) * Mth.RAD_TO_DEG);
        float idealYaw = (float) (-Mth.atan2(dx, dz) * Mth.RAD_TO_DEG);
        final float pitch = AiGunSpreadHelper.CalculateSpread(idealPitch,
                (float) dist, baseSpread, spreadInc, this.unit.getRandom());
        final float yaw = AiGunSpreadHelper.CalculateSpread(idealYaw,
                (float) dist, baseSpread, spreadInc, this.unit.getRandom());
        ShootResult result = operator.shoot(() -> pitch, () -> yaw);
        logShootResult(result);

        // SEM's RangedGunAttackGoal contract: drive the gun through its
        // action states instead of assuming shoot() always fires. Manual
        // guns (m870 pump) answer NEED_BOLT after every shot, an empty mag
        // answers NO_AMMO, a holstered weapon answers NOT_DRAW; everything
        // else (COOL_DOWN / IS_BOLTING / IS_DRAWING / IS_SPRINTING) is a
        // transient TaCZ state the next tick resolves on its own.
        switch (result) {
            case NEED_BOLT -> operator.bolt();
            case NO_AMMO -> operator.reload();
            case NOT_DRAW -> operator.draw(this.unit::getMainHandItem);
            default -> {
            }
        }

        if (result == ShootResult.SUCCESS) {
            // Burst cycle: count shots, then rest for a class-scaled
            // cooldown before the next burst (SNIPER = single shots).
            if (++this.burstShotsFired >= this.burstTarget) {
                this.burstShotsFired = 0;
                this.burstTarget = rollBurstSize(cls);
                this.burstCooldownTicks = rollBurstCooldown(cls);
            }
            // Camper shoot-and-scoot: after a couple of landed shots, arc
            // to a new firing position at the same range.
            if (cls == WeaponClass.SNIPER && ++this.shotsSinceMove >= 2) {
                this.shotsSinceMove = 0;
                tryDisplace(target, dist);
            }
        }
    }

    // ---- per-class rolls --------------------------------------------------

    // Shots per burst: AiShootingConfig min/max x class burst multiplier,
    // min 1. Campers always fire exactly one round per burst - the same
    // hard override the mixin applies.
    private int rollBurstSize(@Nullable WeaponClass cls) {
        if (cls == WeaponClass.SNIPER) {
            return 1;
        }
        int min = AiShootingConfig.MIN_BURST.get();
        int max = AiShootingConfig.MAX_BURST.get();
        int rolled = min + this.unit.getRandom().nextInt(Math.max(1, max - min + 1));
        double mult = cls == null ? 1.0 : ClassSystemConfig.classBurst(cls);
        return Math.max(1, (int) Math.round(rolled * mult));
    }

    // Pause between bursts: AiShootingConfig min/max x class cooldown
    // multiplier, min 1 tick.
    private int rollBurstCooldown(@Nullable WeaponClass cls) {
        int min = AiShootingConfig.MIN_BURST_COOLDOWN.get();
        int max = AiShootingConfig.MAX_BURST_COOLDOWN.get();
        int rolled = min + this.unit.getRandom().nextInt(Math.max(1, max - min + 1));
        double mult = cls == null ? 1.0 : ClassSystemConfig.classCooldown(cls);
        return Math.max(1, (int) Math.round(rolled * mult));
    }

    // ---- aim lead ---------------------------------------------------------

    // The point the shot is aimed at. Campers (and launcher crews) lead
    // moving targets and raise for bullet drop - straight port of the
    // mixin's predictive aim, minus the mortar-arc machinery VDF's gun
    // pools never produce (no lobbers in the militia tables).
    private Vec3 leadAimPoint(LivingEntity target, @Nullable WeaponClass cls,
                            double dist) {
        Vec3 pos = new Vec3(target.getX(), target.getEyeY(), target.getZ());
        if (cls != WeaponClass.SNIPER && cls != WeaponClass.HEAVY
                && cls != WeaponClass.JACK) {
            return pos;
        }
        CommonGunIndex index = getHeldGunIndex(this.unit.getMainHandItem());
        if (index == null || index.getBulletData() == null) {
            return pos;
        }
        BulletData bullet = index.getBulletData();
        boolean launcher = "rpg".equals(index.getType());
        // JACK only predicts launchers; plain bullets stay plain shots.
        if (cls == WeaponClass.JACK && !launcher) {
            return pos;
        }
        if (bullet.getSpeed() <= 0) {
            return pos;
        }
        double t = Math.min(dist / (bullet.getSpeed() / 20.0),
                launcher ? 24.0 : 8.0);
        Vec3 delta = target.getDeltaMovement();        // blocks per tick
        // Monsters move dumb and get fully led; players stay dodgeable.
        double leadScale = switch (cls) {
            case SNIPER -> target instanceof Monster ? 1.0 : 0.6;
            case HEAVY -> target instanceof Monster ? 1.0 : 0.8;
            default -> target instanceof Monster ? 0.8 : 0.6;   // JACK
        };
        // Only airborne motion (jumps/falls) moves the aim point vertically.
        double leadY = target.onGround() ? 0.0 : delta.y * t * leadScale;
        double drop = Math.min(0.5 * bullet.getGravity() * t * t,
                launcher ? 30.0 : 3.0);
        return new Vec3(pos.x + delta.x * t * leadScale,
                pos.y + leadY + drop,
                pos.z + delta.z * t * leadScale);
    }

    // True when firing would catch the shooter in its own shell's blast
    // (Demolisher safety - mirrors the mixin's insideBlastRadius).
    private boolean insideBlastRadius(LivingEntity target, double dist) {
        CommonGunIndex index = getHeldGunIndex(this.unit.getMainHandItem());
        BulletData bullet = index == null ? null : index.getBulletData();
        if (bullet == null || bullet.getExplosionData() == null
                || !bullet.getExplosionData().isExplode()) {
            return false;
        }
        double danger = Math.min(bullet.getExplosionData().getRadius() + 2.5D, 12.0D);
        return dist < danger;
    }

    // ---- tactical movement, weapon swap & squad awareness ----------------

    private boolean updateWeaponSwap(ItemStack held, IGun heldGun, double distSqr, double dist,
                                     boolean canSee, boolean reloading) {
        if ((this.unit.tickCount + this.unit.getId()) % 8 != 1) {
            return false;
        }
        WeaponClass assigned = WeaponClassSupport.get(this.unit);
        if (assigned == null || assigned == WeaponClass.PISTOL) {
            return false;
        }
        long now = this.unit.level().getGameTime();
        if (!UnitWeaponFallbackGoal.canSwapNow(this.unit, now)) {
            return false;
        }
        boolean holdingBackup = UnitWeaponFallbackGoal.isCqbBackupItem(held, assigned);
        double drawRange = ClassSystemConfig.classSidearmDrawDistance(assigned);
        if (assigned == WeaponClass.HEAVY) {
            drawRange = Math.max(11.0D, drawRange);
        }
        boolean primaryLowInCqb = !holdingBackup && (reloading || heldGun.getCurrentAmmoCount(held) <= 0);
        boolean wantBackup = canSee && ((drawRange > 0.0D && dist <= drawRange)
                || (primaryLowInCqb && dist <= 12.0D));

        if (!holdingBackup && wantBackup) {
            return UnitWeaponFallbackGoal.swapToBackup(this.unit, assigned, now);
        }
        if (holdingBackup) {
            double returnDist = Math.max(14.0D, Math.max(drawRange + 4.0D,
                    ClassSystemConfig.classSidearmReturnDistance(assigned)));
            if (distSqr >= returnDist * returnDist
                    && UnitWeaponFallbackGoal.hasReadyPrimaryForDistance(this.unit, assigned, distSqr, returnDist)) {
                return UnitWeaponFallbackGoal.swapToPrimary(this.unit, assigned, now, false);
            }
        }
        return false;
    }

    private void updateCombatMovement(@Nullable WeaponClass cls, LivingEntity target, double dist,
                                      double approach, double engage, boolean canSee, boolean reloading) {
        if (this.prone) {
            this.unit.setSprinting(false);
            this.unit.getNavigation().stop();
            return;
        }

        double dx = target.getX() - this.unit.getX();
        double dz = target.getZ() - this.unit.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        double tx = len > 0.01D ? dx / len : 1.0D;
        double tz = len > 0.01D ? dz / len : 0.0D;
        double px = -tz;
        double pz = tx;

        // 1. Reload evasive sidestep when under fire in sight
        if (reloading && canSee && len > 0.01D) {
            this.unit.setSprinting(false);
            if ((this.unit.tickCount + this.unit.getId()) % 12 == 0) {
                double dir = (((this.unit.tickCount / 12) + this.unit.getId()) % 2 == 0) ? 1.0D : -1.0D;
                double wx = this.unit.getX() + px * 2.6D * dir - tx * 1.0D;
                double wz = this.unit.getZ() + pz * 2.6D * dir - tz * 1.0D;
                if (isWalkableSpot(wx, this.unit.getY(), wz)) {
                    this.unit.getMoveControl().setWantedPosition(wx, this.unit.getY(), wz, 1.08D);
                }
            }
            return;
        }

        // 2. Keep-away kiting (SNIPER / HEAVY / MEDIC) with walkable fan-out
        double kite = cls == null ? 0.0D : ClassSystemConfig.classKiteDistance(cls);
        if (kite > 0.0D && canSee && dist < kite) {
            this.unit.setSprinting(false);
            int interval = cls == WeaponClass.SNIPER ? 10 : 6;
            if ((this.unit.tickCount + this.unit.getId()) % interval == 0 && len > 0.01D) {
                double backAngle = Math.atan2(-dz, -dx);
                double bestScore = -1.0D;
                double bestX = 0.0D, bestZ = 0.0D;
                double reach = Math.min(8.0D, kite * 0.6D);
                for (double deg : new double[]{0.0D, -40.0D, 40.0D, -80.0D, 80.0D}) {
                    double a = backAngle + Math.toRadians(deg);
                    double cx = this.unit.getX() + Math.cos(a) * reach;
                    double cz = this.unit.getZ() + Math.sin(a) * reach;
                    if (!isWalkableSpot(cx, this.unit.getY(), cz)) {
                        continue;
                    }
                    double score = (cx - target.getX()) * (cx - target.getX())
                            + (cz - target.getZ()) * (cz - target.getZ());
                    if (score > bestScore) {
                        bestScore = score;
                        bestX = cx;
                        bestZ = cz;
                    }
                }
                if (bestScore > 0.0D) {
                    this.unit.getMoveControl().setWantedPosition(bestX, this.unit.getY(), bestZ, approach);
                }
            }
            return;
        }

        // 3. Shotgun serpentine advance & CQB circle-strafing
        if (cls == WeaponClass.SHOTGUN) {
            if (dist > 7.0D || !canSee) {
                this.unit.setSprinting(dist > 9.0D);
                if ((this.unit.tickCount + this.unit.getId()) % 8 == 0 || this.unit.getNavigation().isDone()) {
                    double side = (((this.unit.tickCount + this.unit.getId() * 5) / 14) % 2 == 0) ? 1.0D : -1.0D;
                    double stepAngle = Math.atan2(dz, dx) + Math.toRadians(24.0D * side);
                    double stepDist = Math.min(6.0D, dist);
                    double wx = this.unit.getX() + Math.cos(stepAngle) * stepDist;
                    double wz = this.unit.getZ() + Math.sin(stepAngle) * stepDist;
                    if (dist > 8.0D && isWalkableSpot(wx, this.unit.getY(), wz)) {
                        this.unit.getNavigation().moveTo(wx, this.unit.getY(), wz, Math.max(1.12D, approach));
                    } else {
                        this.unit.getNavigation().moveTo(target, Math.max(1.12D, approach));
                    }
                }
            } else {
                this.unit.setSprinting(false);
                if ((this.unit.tickCount + this.unit.getId()) % 10 == 0) {
                    double side = (((this.unit.tickCount + this.unit.getId() * 3) / 14) % 2 == 0) ? 1.0D : -1.0D;
                    double radial = dist < 2.8D ? -1.4D : (dist > 5.5D ? 0.9D : 0.0D);
                    double wx = this.unit.getX() + px * 2.8D * side + tx * radial;
                    double wz = this.unit.getZ() + pz * 2.8D * side + tz * radial;
                    if (isWalkableSpot(wx, this.unit.getY(), wz)) {
                        this.unit.getMoveControl().setWantedPosition(wx, this.unit.getY(), wz, 1.08D);
                    }
                }
            }
            return;
        }

        // 4. Advance if outside engage range or obstructed
        this.unit.setSprinting(false);
        if (dist > engage || !canSee) {
            if ((this.unit.tickCount + this.unit.getId()) % 6 == 0 || this.unit.getNavigation().isDone()) {
                this.unit.getNavigation().moveTo(target, approach);
            }
            return;
        }

        // 5. Engaged within range: spread from stacked militia + agile skirmish strafing
        this.unit.getNavigation().stop();
        if ((this.unit.tickCount + this.unit.getId()) % 14 == 0 && len > 0.01D) {
            var crowded = this.unit.level().getEntitiesOfClass(
                    VdfUnitEntity.class,
                    this.unit.getBoundingBox().inflate(1.6D, 1.0D, 1.6D),
                    other -> other != this.unit && other.isAlive());
            if (!crowded.isEmpty()) {
                VdfUnitEntity mate = crowded.get(0);
                double dot = (this.unit.getX() - mate.getX()) * px + (this.unit.getZ() - mate.getZ()) * pz;
                double side = dot >= 0.0D ? 1.0D : -1.0D;
                double wx = this.unit.getX() + px * 2.0D * side;
                double wz = this.unit.getZ() + pz * 2.0D * side;
                if (isWalkableSpot(wx, this.unit.getY(), wz)) {
                    this.unit.getMoveControl().setWantedPosition(wx, this.unit.getY(), wz, 1.02D);
                }
                return;
            }
            if (cls != WeaponClass.SNIPER && cls != WeaponClass.HEAVY_MG) {
                boolean smg = cls == WeaponClass.SMG;
                double side = (((this.unit.tickCount / 14) + this.unit.getId()) % 2 == 0) ? 1.0D : -1.0D;
                double lat = smg ? 2.6D : 2.0D;
                double fwd = smg ? (dist > 11.0D ? 1.0D : (dist < 5.5D ? -1.0D : 0.0D)) : 0.0D;
                double wx = this.unit.getX() + px * lat * side + tx * fwd;
                double wz = this.unit.getZ() + pz * lat * side + tz * fwd;
                if (isWalkableSpot(wx, this.unit.getY(), wz)) {
                    this.unit.getMoveControl().setWantedPosition(wx, this.unit.getY(), wz, smg ? 1.08D : 1.0D);
                }
            }
        }
    }

    private boolean isWalkableSpot(double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        if (!this.unit.level().hasChunkAt(pos)) {
            return false;
        }
        Vec3 delta = new Vec3(x - this.unit.getX(), y - this.unit.getY(), z - this.unit.getZ());
        if (!this.unit.level().noCollision(this.unit, this.unit.getBoundingBox().move(delta))) {
            return false;
        }
        return this.unit.level().getBlockState(pos.below())
                .entityCanStandOnFace(this.unit.level(), pos.below(), this.unit, Direction.UP)
                && this.unit.level().getFluidState(pos).isEmpty();
    }

    private void smartRetargetAndAlert(LivingEntity current, double currentDistSqr, boolean currentVisible) {
        if ((this.unit.tickCount + this.unit.getId()) % 16 != 4) {
            return;
        }
        LivingEntity hurtBy = this.unit.getLastHurtByMob();
        if (hurtBy != null && hurtBy.isAlive() && hurtBy != current && this.unit.canAttack(hurtBy)) {
            int hurtAge = this.unit.tickCount - this.unit.getLastHurtByMobTimestamp();
            if (hurtAge <= 40 && (!currentVisible || this.unit.distanceToSqr(hurtBy) < currentDistSqr * 0.65D)
                    && this.unit.getSensing().hasLineOfSight(hurtBy)) {
                this.unit.setTarget(hurtBy);
                current = hurtBy;
            }
        }
        var allies = this.unit.level().getEntitiesOfClass(
                VdfUnitEntity.class,
                this.unit.getBoundingBox().inflate(28.0D, 12.0D, 28.0D),
                other -> other != this.unit && other.isAlive() && other.getTarget() == null);
        for (VdfUnitEntity ally : allies) {
            if (ally.canAttack(current)) {
                ally.setTarget(current);
            }
        }
    }

    // True when a protected ally or same-faction unit stands in the firing
    // line; nudges the shooter sideways to clear the ally's back, exactly
    // like the PMC mixin (for the militia "protected ally" = villagers,
    // golems, other VDF and neutral PMC/BHQ via isAlliedTo + UnitClass).
    private boolean allyInFireLine(LivingEntity target) {
        Vec3 start = this.unit.getEyePosition();
        Vec3 end = target.getEyePosition();
        UnitClass ownFaction = UnitClass.of(this.unit);
        var allies = this.unit.level().getEntitiesOfClass(
                LivingEntity.class,
                this.unit.getBoundingBox().expandTowards(end.subtract(start)).inflate(0.6D),
                other -> other != this.unit && other != target && other.isAlive()
                        && (this.unit.isAlliedTo(other)
                        || VdfUnitEntity.isProtectedAlly(other)
                        || (ownFaction != null && ownFaction == UnitClass.of(other))));
        for (LivingEntity ally : allies) {
            if (ally.getBoundingBox().inflate(0.45D).clip(start, end).isPresent()) {
                double ddx = end.x - start.x;
                double ddz = end.z - start.z;
                double len = Math.sqrt(ddx * ddx + ddz * ddz);
                if (len > 0.01D) {
                    double px = -ddz / len;
                    double pz = ddx / len;
                    double side = ((this.unit.getX() - ally.getX()) * px
                            + (this.unit.getZ() - ally.getZ()) * pz) >= 0.0D ? 1.0D : -1.0D;
                    this.unit.getMoveControl().setWantedPosition(
                            this.unit.getX() + px * 1.8D * side,
                            this.unit.getY(),
                            this.unit.getZ() + pz * 1.8D * side,
                            1.1D);
                }
                return true;
            }
        }
        return false;
    }

    // Camper displacement: a new firing point on the same range-ring around
    // the target, rotated 60-90 degrees - straight port of the mixin's
    // tryDisplace so a militia sniper isn't a fixed turret either.
    private void tryDisplace(LivingEntity target, double dist) {
        if (!this.unit.getNavigation().isDone()) {
            return;
        }
        Vec3 flatDir = new Vec3(this.unit.getX() - target.getX(), 0.0,
                this.unit.getZ() - target.getZ());
        if (dist < 1.0) {
            return;
        }
        flatDir = flatDir.normalize();
        double side = this.unit.getRandom().nextBoolean() ? 1.0 : -1.0;
        double angle = side * (Math.PI / 3.0
                + this.unit.getRandom().nextDouble() * Math.PI / 6.0);
        double cos = Math.cos(angle), sin = Math.sin(angle);
        double nx = flatDir.x * cos - flatDir.z * sin;
        double nz = flatDir.x * sin + flatDir.z * cos;
        this.unit.getNavigation().moveTo(
                target.getX() + nx * dist, target.getY(),
                target.getZ() + nz * dist, 1.15);
    }

    // ---- firing stance ----------------------------------------------------
    // Direct port of the mixin's updateStance: crouch classes kneel while
    // planted, Campers go prone past their proneDistance with the same
    // hysteresis (stationary entry, distance/LoS exits, 4s cooldown) so the
    // stance never flaps. The client reads the sneak flag / SWIMMING pose
    // and renders the matching class stance - same as SEM units.
    private void updateStance(@Nullable WeaponClass cls, LivingEntity target,
                            double dist) {
        if (cls == null || !ClassSystemConfig.classCrouchWhileFiring(cls)
                || this.unit.isUsingItem()) {
            clearProneAndCrouch();
            return;
        }

        double dy = Math.abs(target.getY() - this.unit.getY());
        double proneDist = ClassSystemConfig.classProneDistance(cls);
        boolean moving = !this.unit.getNavigation().isDone()
                || this.unit.getDeltaMovement().horizontalDistanceSqr() >= 0.0035D;

        if (this.prone) {
            // Prone is a commitment: stay flat until a real event - target
            // pushes inside the standoff, prone LoS stays blocked, or the
            // unit leaves the ground. Navigation noise must NOT break it.
            this.proneBlockedLosTicks = hasProneLineOfSight(target)
                    ? 0 : this.proneBlockedLosTicks + 5;

            boolean hardExit = !this.unit.onGround()
                    || dist < Math.max(10.0D, proneDist - 8.0D);
            boolean softExit = dy > 10.0D || this.proneBlockedLosTicks >= 20;

            if (hardExit || (softExit && this.proneHoldTicks >= 40)) {
                this.unit.setPose(Pose.STANDING);
                this.prone = false;
                this.proneHoldTicks = 0;
                this.proneCooldownTicks = 80;   // 4s before going prone again
            } else {
                // Pin the spot and keep the pose latched.
                if (!this.unit.getNavigation().isDone()) {
                    this.unit.getNavigation().stop();
                }
                if (this.unit.getPose() != Pose.SWIMMING) {
                    this.unit.setPose(Pose.SWIMMING);
                }
                if (this.crouching) {
                    this.unit.setShiftKeyDown(false);
                    this.crouching = false;
                }
                return;
            }
        }

        // Eligible to enter prone: sniper class, planted >= 20 ticks, past
        // proneDistance, workable firing lane from prone eye height.
        boolean canEnterProne = proneDist > 0.0
                && cls == WeaponClass.SNIPER
                && this.proneCooldownTicks <= 0
                && this.stationaryTicks >= 20
                && dist >= proneDist
                && dy <= 8.0D
                && hasProneLineOfSight(target);

        if (canEnterProne) {
            this.unit.setPose(Pose.SWIMMING);
            if (this.crouching) {
                this.unit.setShiftKeyDown(false);
                this.crouching = false;
            }
            this.prone = true;
            this.proneHoldTicks = 0;
            this.proneBlockedLosTicks = 0;
            return;
        }

        // Kneel: only when not actively moving in CQB (rushers stay up).
        boolean shouldKneel = this.unit.onGround()
                && !moving
                && dist >= (cls == WeaponClass.SHOTGUN || cls == WeaponClass.SMG
                        ? 12.0D : 5.5D);
        if (shouldKneel) {
            if (!this.crouching) {
                this.unit.setShiftKeyDown(true);
                this.crouching = true;
            }
        } else if (this.crouching && (moving || dist < 4.5D)) {
            this.unit.setShiftKeyDown(false);
            this.crouching = false;
        }
    }

    // Prone LoS probe from just above the prone hitbox (0.45 - raised-head
    // aim height). Two aim points: target eye and chest - either reachable
    // means the lane is workable; clutter within ~2.5 blocks of the muzzle
    // is poked over instead of vetoing the stance. Same math the mixin
    // runs for SEM snipers.
    private boolean hasProneLineOfSight(LivingEntity target) {
        Vec3 proneEye = new Vec3(this.unit.getX(),
                this.unit.getY() + 0.45D, this.unit.getZ());
        Vec3[] aims = {
                target.getEyePosition(),
                target.position().add(0.0, target.getBbHeight() * 0.55D, 0.0)
        };
        for (Vec3 aim : aims) {
            var hit = this.unit.level().clip(new net.minecraft.world.level.ClipContext(
                    proneEye, aim,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                    this.unit));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
                return true;
            }
            if (hit.getLocation().distanceToSqr(proneEye) <= 2.5D * 2.5D) {
                Vec3 past = proneEye.add(aim.subtract(proneEye).normalize().scale(2.8D));
                var lane = this.unit.level().clip(new net.minecraft.world.level.ClipContext(
                        past, aim,
                        net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.NONE,
                        this.unit));
                if (lane.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
                    return true;
                }
            }
        }
        return false;
    }

    private void clearProneAndCrouch() {
        if (this.prone) {
            this.unit.setPose(Pose.STANDING);
            this.prone = false;
        }
        if (this.crouching) {
            this.unit.setShiftKeyDown(false);
            this.crouching = false;
        }
    }

    // Rate-limited diagnostic: every non-successful shoot result is worth a
    // line (they are the states that used to stall the gun), while normal
    // fire only proves the loop is alive a few times a second.
    private void logShootResult(ShootResult result) {
        if (result != ShootResult.SUCCESS) {
            LOG.debug("[SEMCVdf] shoot result={} gun={}", result,
                    this.unit.getMainHandItem().getItem());
        } else if ((this.unit.tickCount & 0x3F) == 0) {
            LOG.debug("[SEMCVdf] shoot result=SUCCESS gun={}",
                    this.unit.getMainHandItem().getItem());
        }
    }
}
