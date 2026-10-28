// Mixin package - patches SEM's gun combat goal for per-class combat tuning
package com.poseidon.sem_classsystem.mixin;

// Our helper: faction x class -> spread multiplier
import com.poseidon.sem_classsystem.faction.ClassLoadouts;
import com.poseidon.sem_classsystem.faction.UnitClassAttributes;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
// TaCZ interfaces: read which gun id the held ItemStack is, plus bullet
// ballistics (speed/gravity) for Camper aim-lead
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.resource.pojo.data.gun.BulletData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import java.util.function.Supplier;
// Vanilla RNG source the goal passes to the spread calculator
import net.minecraft.util.RandomSource;
// Vanilla Mob type of the shooter field inside the goal
import net.minecraft.world.entity.Mob;
// SEM's gun AI goal - the class we patch. It does burst fire + spread math.
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.RangedGunAttackGoal;
// SEM's static helper that adds randomness to the aim angle (the actual spread)
import net.nekoyuni.SimpleEnemyMod.entity.ai.goals.utils.AiGunSpreadHelper;
// Mixin annotations:
import org.spongepowered.asm.mixin.Final;     // the shadowed fields are final in SEM
import org.spongepowered.asm.mixin.Mixin;     // marks this as a mixin
import org.spongepowered.asm.mixin.Mutable;   // unlocks writing to final fields
import org.spongepowered.asm.mixin.Shadow;    // mirrors a private field of the target
import org.spongepowered.asm.mixin.Unique;    // a method/field only this mixin adds
import org.spongepowered.asm.mixin.injection.At;         // WHERE inside a method
import org.spongepowered.asm.mixin.injection.Inject;     // insert code
import org.spongepowered.asm.mixin.injection.Redirect;   // replace a method call
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Patch SEM's RangedGunAttackGoal - the AI goal that makes soldiers shoot.
@Mixin(RangedGunAttackGoal.class)
public abstract class MixinRangedGunAttackGoal {

    // @Shadow mirrors fields that exist in the target class. remap=false because
    // these are SEM's own fields - names are identical in dev and production.
    @Shadow(remap = false)
    @Final
    private Mob mob;

    // The entity SEM is currently aiming at - needed for aim-lead prediction.
    @Shadow(remap = false)
    private LivingEntity target;

    // SEM's constructor-set burst/cooldown values. @Mutable removes "final" at
    // weave time so we can retune them after the constructor.
    @Shadow(remap = false) @Final @Mutable private int MIN_BURST_SHOTS;
    @Shadow(remap = false) @Final @Mutable private int MAX_BURST_SHOTS;
    @Shadow(remap = false) @Final @Mutable private int MIN_BURST_COOLDOWN_TICKS;
    @Shadow(remap = false) @Final @Mutable private int MAX_BURST_COOLDOWN_TICKS;
    // Squared max engagement distance - also mutable so classes get their
    // own firing distance (shotguns hold fire at range, snipers reach far)
    @Shadow(remap = false) @Final @Mutable private float MAX_SHOOT_DISTANCE_SQR;

    // Guard: which class the fields were last tuned for - re-running for the
    // same class does nothing, but a class CHANGE must retune.
    @Unique
    private WeaponClass semcs$tunedClass = null;
    // Jack retunes when the held gun's type changes, not just the class.
    @Unique
    private String semcs$tunedGunType = null;

    // The constructor's original burst/cooldown values, captured once.
    // Tuning always computes original x multiplier - never multiplies an
    // already-scaled field (that would corrupt values on a re-class).
    @Unique
    private int semcs$origMinBurst;
    @Unique
    private int semcs$origMaxBurst;
    @Unique
    private int semcs$origMinCooldown;
    @Unique
    private int semcs$origMaxCooldown;
    @Unique
    private float semcs$origShootSqr;
    @Unique
    private boolean semcs$origCaptured = false;

    // True while WE put this unit into sneak for a crouch-fire stance, so
    // stop() only clears a flag we own (never touches another system).
    @Unique
    private boolean semcs$crouching = false;

    // True while WE hold the gun's aim flag for a Camper, so stop() releases
    // only a flag we set.
    @Unique
    private boolean semcs$aiming = false;

    // True while WE hold the unit in the prone (Pose.SWIMMING) stance.
    @Unique
    private boolean semcs$prone = false;
    @Unique
    private int semcs$stationaryTicks = 0;
    @Unique
    private int semcs$proneHoldTicks = 0;
    @Unique
    private int semcs$proneCooldownTicks = 0;
    @Unique
    private int semcs$proneBlockedLosTicks = 0;

    // Successful shots since the Camper last displaced - reposition trigger.
    @Unique
    private int semcs$shotsSinceMove = 0;

    // ---- launcher fire correction ------------------------------------------
    // Demolishers (and Jacks holding launchers) bracket their fire like a
    // real mortar/GL crew: after each shot we follow the shell entity until
    // it lands, measure how far short/long it fell along the target line,
    // and fold that error into the next shot's aim height. First rounds
    // straddle, later rounds walk onto the target.
    @Unique
    private EntityKineticBullet semcs$trackedShell;
    // Learned aim-height correction in blocks - persists across shots while
    // the same target is engaged, reset when the target changes.
    @Unique
    private double semcs$aimAdjustY = 0.0;
    // The target the current correction was learned against.
    @Unique
    private Entity semcs$adjustTarget = null;

    // TAIL of the constructor: runs right after SEM stored the global
    // AiShootingConfig burst/cooldown values into the finals above -
    // we overwrite them with class-scaled values.
    // remap=false: "<init>" (constructor) is never obfuscated.
    //
    // NOTE: spread fields (BASE_SPREAD_DEGREES / SPREAD_INCREASE_PER_BLOCK)
    // are NOT tuned here - they are scaled inside the CalculateSpread redirect
    // below so each shot reads the live config values.
    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void semcs$tuneForClass(CallbackInfo ci) {
        semcs$applyTuning();
    }

    // Applies burst-length and cooldown multipliers to the shadowed fields,
    // always computed from the captured ORIGINAL constructor values so a
    // re-class retunes correctly instead of scaling the scaled numbers.
    @Unique
    private void semcs$applyTuning() {
        // First call: snapshot SEM's constructor values before we touch them
        if (!this.semcs$origCaptured) {
            this.semcs$origMinBurst = this.MIN_BURST_SHOTS;
            this.semcs$origMaxBurst = this.MAX_BURST_SHOTS;
            this.semcs$origMinCooldown = this.MIN_BURST_COOLDOWN_TICKS;
            this.semcs$origMaxCooldown = this.MAX_BURST_COOLDOWN_TICKS;
            this.semcs$origShootSqr = this.MAX_SHOOT_DISTANCE_SQR;
            this.semcs$origCaptured = true;
        }

        // Which class is the shooter? The EFFECTIVE class - a unit holding
        // a subclass gun (pistol sidearm) fights with that gun's tuning.
        // null -> leave SEM defaults untouched.
        WeaponClass cls = WeaponClassSupport.getEffective(this.mob);
        if (cls == null) {
            return;
        }
        // Jack fights at whatever range suits the gun in its hands - a gun
        // swap retunes even when the class stays the same.
        String gunType = cls == WeaponClass.JACK
                ? WeaponClassSupport.heldGunType(this.mob) : null;
        if (cls == this.semcs$tunedClass
                && java.util.Objects.equals(gunType, this.semcs$tunedGunType)) {
            return;   // already tuned for this class (+ gun, for Jack)
        }
        this.semcs$tunedClass = cls;
        this.semcs$tunedGunType = gunType;

        // --- burst length (min 1 shot, max >= min) ---
        double burst = ClassSystemConfig.classBurst(cls);
        this.MIN_BURST_SHOTS = Math.max(1, (int) Math.round(this.semcs$origMinBurst * burst));
        this.MAX_BURST_SHOTS = Math.max(this.MIN_BURST_SHOTS,
                (int) Math.round(this.semcs$origMaxBurst * burst));
        // Campers fire single deliberate shots - one round per burst,
        // regardless of how high the SEM global config is set.
        if (cls == WeaponClass.SNIPER) {
            this.MAX_BURST_SHOTS = 1;
            this.MIN_BURST_SHOTS = 1;
        }

        // --- cadence between bursts (the "fire rate" knob) ---
        double cooldown = ClassSystemConfig.classCooldown(cls);
        this.MIN_BURST_COOLDOWN_TICKS = Math.max(1,
                (int) Math.round(this.semcs$origMinCooldown * cooldown));
        this.MAX_BURST_COOLDOWN_TICKS = Math.max(this.MIN_BURST_COOLDOWN_TICKS,
                (int) Math.round(this.semcs$origMaxCooldown * cooldown));

        // --- max shooting distance (how far the unit will fire from) ---
        // Field stores distance SQUARED, so the multiplier gets squared too.
        double shootMult = cls == WeaponClass.JACK
                ? WeaponClassSupport.jackShootDistance(gunType)
                : ClassSystemConfig.classShootDistance(cls);
        this.MAX_SHOOT_DISTANCE_SQR = (float) (this.semcs$origShootSqr * shootMult * shootMult);
    }

    // @Redirect replaces a CALL to another method with a call to OUR method.
    // SEM's private handleBurstFiringState calls CalculateSpread twice
    // (pitch + yaw); one @Redirect catches both - same descriptor.
    // remap=false: both names are SEM-internal, identical in prod.
    //
    // The descriptor spells out JVM bytecode types:
    //   (FFFFLnet/minecraft/util/RandomSource;)F =
    //   args: float, float, float, float, RandomSource -> returns float
    @Redirect(method = "handleBurstFiringState", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lnet/nekoyuni/SimpleEnemyMod/entity/ai/goals/utils/AiGunSpreadHelper;CalculateSpread(FFFFLnet/minecraft/util/RandomSource;)F"))
    private float semcs$classScaledSpread(float idealAngle, float distance, float baseSpread,
                                          float spreadIncreasePerBlock, RandomSource random) {
        // Safety net: if the ctor ran before the class was assigned, retune
        // burst/cooldown fields right before the first real shot.
        semcs$applyTuning();

        // Resolve the shooter's effective class - held gun's class overrides
        // the assigned one, so a sidearm shoots with pistol spread/falloff.
        // (null -> neutral 1.0 multipliers)
        WeaponClass cls = WeaponClassSupport.getEffective(this.mob);
        double classSpread = cls == null ? 1.0 : ClassSystemConfig.classSpread(cls);
        double classFalloff = cls == null ? 1.0 : ClassSystemConfig.classFalloff(cls);
        // Monster-hunter bonus: campers hit zombies/monsters far more
        // reliably than they hit players - players must stay dodgeable.
        if (cls == WeaponClass.SNIPER && this.target instanceof Monster) {
            classSpread *= 0.4;
        }
        // Crouched / prone firing is steadier - prone gets an extra stability bonus
        boolean isProne = this.mob.getPose() == net.minecraft.world.entity.Pose.SWIMMING;
        double crouch = cls != null && (this.mob.isShiftKeyDown() || isProne)
                ? ClassSystemConfig.classCrouchSpread(cls) * (isProne ? 0.75 : 1.0) : 1.0;

        // Total spread = SEM's value x faction multiplier x class multiplier.
        // The distance falloff arg is scaled by the class falloff multiplier
        // (shotguns collapse at range, snipers barely degrade).
        return AiGunSpreadHelper.CalculateSpread(idealAngle, distance,
                baseSpread * (float) (UnitClassAttributes.spreadMultiplier(this.mob) * classSpread * crouch),
                (float) (spreadIncreasePerBlock * classFalloff),
                random);
    }

    // ---- sniper aim lead ----------------------------------------------------
    // SEM aims at the target's CURRENT position - at sniper range the bullet
    // needs real flight time, so moving targets are already gone and gravity
    // pulls the round low. Campers get predictive aim: lead the target by
    // (velocity x flight ticks) and raise the aim point by the bullet drop
    // (0.5 * g * t^2, gravity is subtracted from deltaY per tick by
    // EntityKineticBullet). The aim point shift also feeds setLookAt, so the
    // unit visibly tracks the intercept point like a real marksman.
    // remap=true: the INVOKE target position() is vanilla - the refmap must
    // translate it to SRG for production. The SEM-internal method name has no
    // mapping entry and passes through unchanged.
    @Redirect(method = "handleBurstFiringState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;position()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 semcs$leadTargetPosition(LivingEntity entity) {
        Vec3 pos = entity.position();
        if (entity != this.target) {
            return pos;   // only lead the unit's own combat target
        }
        WeaponClass cls = WeaponClassSupport.getEffective(this.mob);
        if (cls != WeaponClass.SNIPER && cls != WeaponClass.HEAVY && cls != WeaponClass.JACK) {
            return pos;
        }
        ItemStack gun = this.mob.getMainHandItem();
        IGun iGun = IGun.getIGunOrNull(gun);
        ResourceLocation gunId = iGun == null ? null : iGun.getGunId(gun);
        if (gunId == null) {
            return pos;
        }
        var index = TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        if (index == null) {
            return pos;
        }
        // TaCZ types every launcher "rpg" - RPGs and grenade launchers alike.
        // The arc difference lives in bullet speed/gravity, which the math
        // below reads per-gun - flat shots get tiny corrections, lobbers get
        // the big aim-up they need. JACK only gets prediction on launchers;
        // for plain bullets it stays a plain shot (decent, not specialist).
        boolean launcher = "rpg".equals(index.getType());
        if (cls == WeaponClass.JACK && !launcher) {
            return pos;
        }
        // Splash wants the middle of the crowd, not the nearest chest - if
        // the target has friends standing close, aim at the group centroid.
        if (launcher) {
            Vec3 centroid = semcs$clusterCentroid(entity);
            if (centroid != null) {
                pos = new Vec3(centroid.x, pos.y, centroid.z);
            }
        }
        BulletData bullet = index.getBulletData();
        if (bullet == null || bullet.getSpeed() <= 0) {
            return pos;
        }
        // gun data: speed is m/s -> /20 for blocks per tick;
        // gravity is subtracted from delta-Y per tick (blocks/tick^2)
        double dist = this.mob.getEyePosition().distanceTo(
                pos.add(0, entity.getBbHeight() / 2.0, 0));
        // Slow lobbers need real flight-time and aim-up budgets; bullets
        // keep the tight caps so nothing aims at the sky.
        double tMax = launcher ? 24.0 : 8.0;
        double dropCap = launcher ? 30.0 : 3.0;
        double t = Math.min(dist / (bullet.getSpeed() / 20.0), tMax);
        Vec3 delta = entity.getDeltaMovement();     // blocks per tick
        // Perfect lead is inhuman - players strafing get partially led
        // (dodgeable by changing direction) while monsters get the full
        // prediction. HEAVY leads a little harder than JACK - its splash
        // still pays off a near-miss, but a specialist reads flight better.
        double leadScale = semcs$leadScale(cls, entity instanceof Monster);
        // don't lead grounded entities' idle gravity drift - only airborne
        // motion (jumps/falls) moves the aim point vertically
        double leadY = entity.onGround() ? 0.0 : delta.y * t * leadScale;
        boolean clearShot = !launcher
                || this.mob.getSensing().hasLineOfSight(entity);
        // With a clear shot, lobbers solve the flattest arc that still
        // reaches - the aim-at-target-plus-drop estimate below steepens
        // badly once the drop grows past a fraction of the range, which
        // made Demolishers mortar targets standing in the open. Blind
        // fire keeps the estimate plus a range-scaled loft to clear
        // whatever blocks the view (a flat +10 over-lofts close targets).
        double drop = launcher && clearShot
                ? semcs$flatArcAimUp(pos, bullet)
                : Math.min(0.5 * bullet.getGravity() * t * t, dropCap);
        // Fold in the learned fire correction - starts at 0 and walks in
        // each time a tracked shell lands short or long (see observeFire).
        double adjust = launcher && this.semcs$adjustTarget == entity
                ? this.semcs$aimAdjustY : 0.0;
        double lob = launcher && !clearShot
                ? Math.min(4.0 + dist * 0.3, 12.0) : 0.0;
        return pos.add(delta.x * t * leadScale,
                leadY + drop + adjust + lob, delta.z * t * leadScale);
    }

    // How perfectly each class predicts intercepts: monsters move dumb and
    // get fully led, players stay dodgeable.
    @Unique
    private static double semcs$leadScale(WeaponClass cls, boolean monster) {
        return switch (cls) {
            case SNIPER -> monster ? 1.0 : 0.6;
            case HEAVY -> monster ? 1.0 : 0.8;
            default -> monster ? 0.8 : 0.6;   // JACK: decent, not lethal
        };
    }

    // Low-arc launch solution for a lobber with line of sight: solves the
    // ballistic quadratic for tan(theta) of the FLAT trajectory from eye to
    // the aim point, then returns how far above that point the unit should
    // look. The naive aim-above estimate equals this only for small drops;
    // past that it keeps inflating the arc and the bracket loop needs
    // several overshooting shots to walk it back down. When no flat
    // solution exists (target beyond the shell's max range) it lofts at
    // ~45 degrees - the best arc a slow shell can do - and observeFire
    // walks it in from there.
    @Unique
    private double semcs$flatArcAimUp(Vec3 aimPos, BulletData bullet) {
        double speed = bullet.getSpeed() / 20.0;   // m/s -> blocks per tick
        double gravity = bullet.getGravity();      // blocks per tick^2
        Vec3 eye = this.mob.getEyePosition();
        double dx = aimPos.x - eye.x;
        double dz = aimPos.z - eye.z;
        double range = Math.sqrt(dx * dx + dz * dz);
        double dy = aimPos.y - eye.y;
        if (range < 1.0 || gravity <= 1.0E-4 || speed <= 0.0) {
            return 0.0;   // point-blank or a flat shooter: aim straight
        }
        double v2 = speed * speed;
        double disc = v2 * v2 - gravity * (gravity * range * range + 2.0 * dy * v2);
        if (disc <= 0.0) {
            return Math.min(range, 30.0);
        }
        double tan = (v2 - Math.sqrt(disc)) / (gravity * range);
        return Math.min(range * tan - dy, 30.0);
    }

    // ---- crouch firing stance ----------------------------------------------
    // Goal lifecycle methods are vanilla (remap stays default). start() puts
    // crouching classes into sneak - the shared entity flag syncs to the
    // client, where MixinUnitModelCrouch turns it into a kneel pose. stop()
    // releases only the flag WE set.
    @Inject(method = "start", at = @At("TAIL"))
    private void semcs$enterCrouch(CallbackInfo ci) {
        this.semcs$stationaryTicks = 0;
        this.semcs$proneHoldTicks = 0;
        this.semcs$proneBlockedLosTicks = 0;
        WeaponClass cls = WeaponClassSupport.getEffective(this.mob);
        if (cls != null && ClassSystemConfig.classCrouchWhileFiring(cls)
                && cls != WeaponClass.SHOTGUN && cls != WeaponClass.SMG) {
            this.mob.setShiftKeyDown(true);
            this.semcs$crouching = true;
        }
    }

    // ---- camper scoped aim -------------------------------------------------
    @Inject(method = "tick", at = @At("HEAD"))
    private void semcs$assertAim(CallbackInfo ci) {
        if (this.semcs$proneCooldownTicks > 0) {
            this.semcs$proneCooldownTicks--;
        }
        // "Planted" = actually not translating, not "nav object happens to be
        // done" - a stale path flickering isDone=false used to zero the counter
        // every time a tactical helper re-pathed, even while the unit never
        // moved an inch. deltaMovement is the truth; once prone, the pin below
        // kills any live path anyway.
        boolean stationary = this.mob.onGround()
                && this.mob.getDeltaMovement().horizontalDistanceSqr() < 0.01D;
        if (stationary) {
            this.semcs$stationaryTicks++;
        } else {
            this.semcs$stationaryTicks = 0;
        }
        if (this.semcs$prone) {
            this.semcs$proneHoldTicks++;
            // Every-tick pin: tactical AI re-issues paths between the 5-tick
            // stance evals - kill them here so a prone unit never creeps.
            if (!this.mob.getNavigation().isDone()) {
                this.mob.getNavigation().stop();
            }
        } else {
            this.semcs$proneHoldTicks = 0;
        }

        if (this.mob.tickCount % 5 != 0) {
            return;
        }
        semcs$updateStance();
        if (WeaponClassSupport.get(this.mob) == WeaponClass.SNIPER
                && WeaponClassSupport.getEffective(this.mob) == WeaponClass.SNIPER) {
            IGunOperator.fromLivingEntity(this.mob).aim(true);
            this.semcs$aiming = true;
        }
    }

    // ---- stance selection with hysteresis & low-eye LoS verification --------
    // Prevents rapid crouch <-> prone oscillation:
    //   1. Entering PRONE requires:
    //      - SNIPER class with proneDistance > 0 (Suppressor never goes
    //        flat - it kneels and braces instead)
    //      - Planted on ground for >= 20 ticks (1s) and cooldown == 0
    //      - Target distance >= proneDist and elevation diff <= 8 blocks
    //      - Workable firing lane from prone eye height (y + 0.45):
    //        eye or chest of the target reachable, muzzle-side clutter
    //        within ~2.5 blocks tolerated (see semcs$hasProneLineOfSight)
    //   2. Staying PRONE uses an 8-block distance hysteresis (proneDist - 8)
    //      and a minimum 50-tick hold unless the unit moves or loses prone LoS
    //      for >= 20 ticks (which triggers an 80-tick prone cooldown).
    //   3. KNEEL (sneak) is used when stationary at >= 6 blocks and not prone
    //      (Shotgun/SMG stay standing while rushing inside 12 blocks).
    @Unique
    private void semcs$updateStance() {
        WeaponClass cls = WeaponClassSupport.getEffective(this.mob);
        if (cls == null || !ClassSystemConfig.classCrouchWhileFiring(cls) || this.mob.isUsingItem()) {
            semcs$clearProneAndCrouch();
            return;
        }

        double dist = this.target != null ? this.mob.distanceTo(this.target) : 0.0D;
        double dy = this.target != null ? Math.abs(this.target.getY() - this.mob.getY()) : 0.0D;
        double proneDist = ClassSystemConfig.classProneDistance(cls);
        boolean moving = !this.mob.getNavigation().isDone()
                || this.mob.getDeltaMovement().horizontalDistanceSqr() >= 0.0035D;

        if (this.semcs$prone) {
            // Prone is a commitment: the unit stays flat until something REAL
            // happens - a hostile pushes inside the standoff (then it gets up
            // and normal AI handles the retreat), the target is lost, prone
            // LoS stays blocked, or a commander orders a move. Tactical-AI
            // noise (cover seek, kiting, reload sidesteps, knockback) must
            // NOT break the stance - that was the prone->crouch->retreat flap.
            boolean commanderMove =
                    this.mob instanceof net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity pmc
                            && (pmc.getOrder()
                                    == net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType.MOVE_TO_POSITION
                            || pmc.getOrder()
                                    == net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType.FOLLOW_COMMANDER);

            boolean hasProneLos = this.target != null && semcs$hasProneLineOfSight(this.target);
            if (!hasProneLos) {
                this.semcs$proneBlockedLosTicks += 5;
            } else {
                this.semcs$proneBlockedLosTicks = 0;
            }

            boolean hardExit = commanderMove
                    || !this.mob.onGround()
                    || this.target == null
                    || dist < Math.max(10.0D, proneDist - 8.0D);
            boolean softExit = dy > 10.0D || this.semcs$proneBlockedLosTicks >= 20;

            if (hardExit || (softExit && this.semcs$proneHoldTicks >= 40)) {
                this.mob.setPose(net.minecraft.world.entity.Pose.STANDING);
                this.semcs$prone = false;
                this.semcs$proneHoldTicks = 0;
                this.semcs$proneCooldownTicks = 80; // 4s cooldown before going prone again
            } else {
                // Pin: kill any path that slipped through anyway and release
                // movement locks, so the unit visibly STAYS in one spot.
                if (!this.mob.getNavigation().isDone()) {
                    this.mob.getNavigation().stop();
                }
                if (this.mob instanceof net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit au
                        && au.isMovementLockedByManager()) {
                    au.releaseMovementLock();
                }
                if (this.mob.getPose() != net.minecraft.world.entity.Pose.SWIMMING) {
                    this.mob.setPose(net.minecraft.world.entity.Pose.SWIMMING);
                }
                if (this.semcs$crouching) {
                    this.mob.setShiftKeyDown(false);
                    this.semcs$crouching = false;
                }
                return;
            }
        }

        // Check if eligible to enter prone
        boolean canEnterProne = proneDist > 0.0
                && cls == WeaponClass.SNIPER
                && this.semcs$proneCooldownTicks <= 0
                && this.semcs$stationaryTicks >= 20
                && this.target != null
                && dist >= proneDist
                && dy <= 8.0D
                && semcs$hasProneLineOfSight(this.target);

        if (canEnterProne) {
            this.mob.setPose(net.minecraft.world.entity.Pose.SWIMMING);
            if (this.semcs$crouching) {
                this.mob.setShiftKeyDown(false);
                this.semcs$crouching = false;
            }
            this.semcs$prone = true;
            this.semcs$proneHoldTicks = 0;
            this.semcs$proneBlockedLosTicks = 0;
            return;
        }

        // Kneel stance (crouch): only when not actively sprinting/moving in CQB
        boolean shouldKneel = this.mob.onGround()
                && !moving
                && dist >= (cls == WeaponClass.SHOTGUN || cls == WeaponClass.SMG ? 12.0D : 5.5D);

        if (shouldKneel) {
            if (!this.semcs$crouching) {
                this.mob.setShiftKeyDown(true);
                this.semcs$crouching = true;
            }
        } else if (this.semcs$crouching && (moving || dist < 4.5D)) {
            this.mob.setShiftKeyDown(false);
            this.semcs$crouching = false;
        }
    }

    // Prone LoS probe from just above the prone hitbox (0.45 - a raised-head
    // aim pose, not the 0.38 hitbox eye). Two aim points: the target's eye and
    // its chest - a ray that reaches either means the lane is workable.
    // Ground clutter within ~2.5 blocks of the muzzle (path lip, farmland,
    // snow layer, a gentle rise right at the unit's feet) does NOT veto:
    // we re-probe the lane starting just past the bump - a real gunner pokes
    // the barrel over it. If the rest of the lane is clear, prone is legal.
    @Unique
    private boolean semcs$hasProneLineOfSight(LivingEntity target) {
        Vec3 proneEye = new Vec3(this.mob.getX(), this.mob.getY() + 0.45D, this.mob.getZ());
        Vec3[] aims = {
                target.getEyePosition(),
                target.position().add(0.0, target.getBbHeight() * 0.55D, 0.0)
        };
        for (Vec3 aim : aims) {
            var hit = this.mob.level().clip(new net.minecraft.world.level.ClipContext(
                    proneEye, aim,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                    this.mob));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
                return true;
            }
            if (hit.getLocation().distanceToSqr(proneEye) <= 2.5D * 2.5D) {
                Vec3 past = proneEye.add(aim.subtract(proneEye).normalize().scale(2.8D));
                var lane = this.mob.level().clip(new net.minecraft.world.level.ClipContext(
                        past, aim,
                        net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.NONE,
                        this.mob));
                if (lane.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
                    return true;
                }
            }
        }
        return false;
    }

    @Unique
    private void semcs$clearProneAndCrouch() {
        if (this.semcs$prone) {
            this.mob.setPose(net.minecraft.world.entity.Pose.STANDING);
            this.semcs$prone = false;
        }
        if (this.semcs$crouching) {
            this.mob.setShiftKeyDown(false);
            this.semcs$crouching = false;
        }
    }

    // Centroid of the target's nearby buddies (players, monsters, other
    // factions - anything that isn't this unit's own faction) - splash
    // rounds land where they hurt the most. null when the target stands
    // alone: aim stays on the target itself.
    @Unique
    private Vec3 semcs$clusterCentroid(LivingEntity target) {
        var ownFaction = com.poseidon.sem_classsystem.faction.UnitClass.of(this.mob);
        java.util.List<LivingEntity> crowd = target.level().getEntitiesOfClass(
                LivingEntity.class, target.getBoundingBox().inflate(5.0),
                e -> e.isAlive() && e != this.mob && e != target
                        && com.poseidon.sem_classsystem.faction.UnitClass.of(e) != ownFaction);
        if (crowd.size() < 2) {
            return null;   // one bystander is not a cluster - aim at target
        }
        double cx = target.getX(), cz = target.getZ();
        for (LivingEntity e : crowd) {
            cx += e.getX();
            cz += e.getZ();
        }
        int n = crowd.size() + 1;
        return new Vec3(cx / n, target.getY(), cz / n);
    }

    // ---- demolisher indirect fire -------------------------------------------
    // SEM refuses to engage or keep firing without line of sight. A grenade
    // launcher exists to break exactly that rule: a Demolisher (or Jack
    // carrying a lobber) may fire BLIND - the ballistic prediction aims the
    // arc and the fire-correction loop walks impacts onto the target.
    // Flat shooters keep the LoS rule - an RPG fired through a wall just
    // digs a hole in it.
    //
    // The goal's LoS gates all funnel through its own private cached
    // helpers (rough = foliage-transparent, clear = strict). HEAD-injecting
    // them covers every caller - canUse, tick, burst - at once.
    // remap=false: SEM-internal method names, same in dev and production.
    @Unique
    private long semcs$lastSeenTick = Long.MIN_VALUE;

    @Inject(method = {"getCachedRoughLineOfSight", "getCachedClearLineOfSight"},
            at = @At("HEAD"), cancellable = true, remap = false)
    private void semcs$indirectFire(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity != this.target) {
            return;
        }
        // Suppressor stagger: the machine-gunner keeps hosing the target's
        // last-known position for ~5s after losing sight - rounds bite into
        // whatever the target ducked behind, pinning it while the squad
        // pushes up. Past the window the goal's real LoS check returns.
        if (WeaponClassSupport.getEffective(this.mob) == WeaponClass.HEAVY_MG) {
            long now = this.mob.level().getGameTime();
            if (this.mob.getSensing().hasLineOfSight(entity)) {
                this.semcs$lastSeenTick = now;
            } else if (now - this.semcs$lastSeenTick <= 100L) {
                cir.setReturnValue(true);
                return;
            }
        }
        if (semcs$heldIsLobber()) {
            cir.setReturnValue(true);
        }
    }

    // Is the held weapon a LOBBER - an rpg-typed gun with enough gravity and
    // low enough muzzle speed that it genuinely arcs (MGL/m320 etc.)? Flat
    // RPGs (high speed, low gravity) return false - they need line of sight.
    @Unique
    private boolean semcs$heldIsLobber() {
        WeaponClass cls = WeaponClassSupport.getEffective(this.mob);
        if (cls != WeaponClass.HEAVY && cls != WeaponClass.JACK) {
            return false;
        }
        ItemStack gun = this.mob.getMainHandItem();
        IGun iGun = IGun.getIGunOrNull(gun);
        ResourceLocation gunId = iGun == null ? null : iGun.getGunId(gun);
        var index = gunId == null ? null
                : TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        if (index == null || !"rpg".equals(index.getType())) {
            return false;
        }
        BulletData bullet = index.getBulletData();
        return bullet != null && bullet.getGravity() >= 0.05F
                && bullet.getSpeed() > 0.0F && bullet.getSpeed() <= 260.0F;
    }

    // ---- vertical FOV boost ------------------------------------------------
    // SEM gates "may I engage / keep engaging" on raw 3D distance, so a
    // target a few blocks up a roof or down a slope reads as much farther
    // and the unit never fires. Redirect those checks through the
    // elevation-tolerant distance (12 free blocks of height difference).
    @Redirect(method = {"canUse", "tick"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Mob;distanceToSqr(Lnet/minecraft/world/entity/Entity;)D"))
    private double semcs$elevationTolerantDistance(Mob self, Entity target) {
        return WeaponClassSupport.elevationTolerantDistanceSqr(self, target);
    }

    // Holds the actual trigger pull until the scope is fully up: while aiming
    // progress is under 1.0 we return COOL_DOWN, which SEM treats as a benign
    // "wait a tick" - the burst state stays put and retries next tick.
    // remap=false: both the method and the target are mod classes.
    @Redirect(method = "handleBurstFiringState", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lcom/tacz/guns/api/entity/IGunOperator;shoot(Ljava/util/function/Supplier;Ljava/util/function/Supplier;)Lcom/tacz/guns/api/entity/ShootResult;"))
    private ShootResult semcs$scopedShoot(IGunOperator operator,
                                          Supplier<Float> pitch, Supplier<Float> yaw) {
        // Pause firing while eating food or applying a bandage/medkit
        if (this.mob.isUsingItem()) {
            return ShootResult.COOL_DOWN;
        }
        // Friendly-fire line-of-sight hold: if an allied unit or friendly player
        // is standing in our firing line, hold the trigger and step laterally.
        if (this.target != null && semcs$allyInFireLine()) {
            return ShootResult.COOL_DOWN;
        }
        WeaponClass cls = WeaponClassSupport.getEffective(this.mob);
        // Blast standoff: a launcher round that lands within its own
        // explosion radius hurts the shooter (and squad). Hold fire while
        // the target is inside the danger zone - HEAVY's kite distance
        // back-pedals the unit out instead of eating its own blast.
        if ((cls == WeaponClass.HEAVY || cls == WeaponClass.JACK)
                && this.target != null && semcs$insideBlastRadius()) {
            return ShootResult.COOL_DOWN;
        }
        boolean sniper = cls == WeaponClass.SNIPER;
        if (sniper && operator.getSynAimingProgress() < 1.0F) {
            operator.aim(true);   // keep the flag asserted between ticks
            return ShootResult.COOL_DOWN;
        }
        ShootResult result = operator.shoot(pitch, yaw);
        // Camper shoot-and-scoot: after a couple of landed shots, arc to a
        // new firing position at the same range so he isn't a fixed turret.
        if (sniper && result == ShootResult.SUCCESS
                && ++this.semcs$shotsSinceMove >= 2) {
            this.semcs$shotsSinceMove = 0;
            semcs$tryDisplace();
        }
        // Start tracking the shell that just left the tube - observeFire()
        // scores its landing point on the next ticks.
        if (result == ShootResult.SUCCESS
                && (cls == WeaponClass.HEAVY || cls == WeaponClass.JACK)) {
            semcs$trackNewestShell();
        }
        return result;
    }

    // True when the target is inside the held gun's own blast radius (plus
    // a small margin) - i.e. firing would splash the shooter. Non-explosive
    // guns always report safe. Reads the same per-gun bullet data the lead
    // code uses.
    @Unique
    private boolean semcs$insideBlastRadius() {
        ItemStack gun = this.mob.getMainHandItem();
        IGun iGun = IGun.getIGunOrNull(gun);
        ResourceLocation gunId = iGun == null ? null : iGun.getGunId(gun);
        if (gunId == null) {
            return false;
        }
        BulletData bullet = TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getBulletData()).orElse(null);
        if (bullet == null || bullet.getExplosionData() == null
                || !bullet.getExplosionData().isExplode()) {
            return false;
        }
        double danger = bullet.getExplosionData().getRadius() + 2.0;
        return this.mob.distanceTo(this.target) < Math.min(danger, 20.0);
    }

    // True if a friendly unit or allied player intersects the firing ray
    // between this shooter and its target. Also nudges the shooter sideways
    // so it clears the friendly's back on the next ticks.
    @Unique
    private boolean semcs$allyInFireLine() {
        Vec3 start = this.mob.getEyePosition();
        Vec3 end = this.target.getEyePosition();
        var ownFaction = com.poseidon.sem_classsystem.faction.UnitClass.of(this.mob);
        boolean isFriendlyPmc = this.mob instanceof net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

        var allies = this.mob.level().getEntitiesOfClass(
                LivingEntity.class,
                this.mob.getBoundingBox().expandTowards(end.subtract(start)).inflate(0.6D),
                other -> other != this.mob && other != this.target && other.isAlive()
                        && (this.mob.isAlliedTo(other)
                        || (ownFaction != null && ownFaction == com.poseidon.sem_classsystem.faction.UnitClass.of(other))
                        || (isFriendlyPmc && other instanceof net.minecraft.world.entity.player.Player)));

        for (LivingEntity ally : allies) {
            if (ally.getBoundingBox().inflate(0.45D).clip(start, end).isPresent()) {
                double dx = end.x - start.x;
                double dz = end.z - start.z;
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len > 0.01D) {
                    double px = -dz / len;
                    double pz = dx / len;
                    double side = ((this.mob.getX() - ally.getX()) * px + (this.mob.getZ() - ally.getZ()) * pz) >= 0.0D ? 1.0D : -1.0D;
                    this.mob.getMoveControl().setWantedPosition(
                            this.mob.getX() + px * 1.8D * side,
                            this.mob.getY(),
                            this.mob.getZ() + pz * 1.8D * side,
                            1.1D);
                }
                return true;
            }
        }
        return false;
    }

    // ---- fire correction ----------------------------------------------------
    // After a successful launcher shot, find the shell entity we just fired
    // (owner = this unit, newest in a small box around us) and remember it.
    @Unique
    private void semcs$trackNewestShell() {
        EntityKineticBullet newest = null;
        // Owner match preferred; a shell 3 ticks old inside arm's reach is
        // ours even if TaCZ skipped setOwner on some addon gun's projectile.
        for (EntityKineticBullet b : this.mob.level().getEntitiesOfClass(
                EntityKineticBullet.class, this.mob.getBoundingBox().inflate(6.0),
                e -> e != this.semcs$trackedShell
                        && (e.getOwner() == this.mob || e.tickCount <= 3))) {
            if (newest == null || b.tickCount < newest.tickCount) {
                newest = b;
            }
        }
        if (newest != null) {
            this.semcs$trackedShell = newest;
        }
    }

    // Every tick: if the tracked shell is gone it has landed (impact, fuse
    // or despawn - its last position is close enough). Score it against the
    // target line: how far SHORT (behind the target from our side) or LONG
    // the impact was, and push the aim-height correction by a fraction of
    // the error. Brackets walk onto the target in 2-3 shots.
    @Inject(method = "tick", at = @At("HEAD"))
    private void semcs$observeFire(CallbackInfo ci) {
        if (this.target != this.semcs$adjustTarget) {
            this.semcs$adjustTarget = this.target;
            this.semcs$aimAdjustY = 0.0;
            this.semcs$trackedShell = null;
            return;
        }
        EntityKineticBullet shell = this.semcs$trackedShell;
        if (shell == null || shell.isAlive() || this.target == null) {
            return;
        }
        this.semcs$trackedShell = null;
        // Project impact onto the horizontal shooter->target axis:
        // positive error = the shell fell short of the target.
        double tx = this.target.getX() - this.mob.getX();
        double tz = this.target.getZ() - this.mob.getZ();
        double axisLen = Math.sqrt(tx * tx + tz * tz);
        if (axisLen < 1.0) {
            return;
        }
        Vec3 impact = shell.position();
        double along = ((impact.x - this.mob.getX()) * tx
                + (impact.z - this.mob.getZ()) * tz) / axisLen;
        double err = axisLen - along;
        WeaponClass cls = WeaponClassSupport.getEffective(this.mob);
        double gain = cls == WeaponClass.HEAVY ? 0.35 : 0.2;
        this.semcs$aimAdjustY = Math.max(-15.0,
                Math.min(15.0, this.semcs$aimAdjustY
                        + Math.max(-4.0, Math.min(4.0, err * gain))));
    }

    // Picks a new firing point on the same range-ring around the target,
    // rotated 60-90 degrees either way, and orders the move. Keeps the
    // engagement distance (so MoveToAttackRangeGoal doesn't fight it) but
    // forces the enemy to re-acquire him on a new angle.
    @Unique
    private void semcs$tryDisplace() {
        if (this.target == null || !this.target.isAlive()
                || !this.mob.getNavigation().isDone()) {
            return;
        }
        Vec3 flat = new Vec3(this.mob.getX() - this.target.getX(), 0,
                this.mob.getZ() - this.target.getZ());
        double dist = flat.length();
        if (dist < 1.0) {
            return;
        }
        flat = flat.normalize();
        double side = this.mob.getRandom().nextBoolean() ? 1.0 : -1.0;
        double angle = side * (Math.PI / 3.0 + this.mob.getRandom().nextDouble() * Math.PI / 6.0);
        double cos = Math.cos(angle), sin = Math.sin(angle);
        double nx = flat.x * cos - flat.z * sin;
        double nz = flat.x * sin + flat.z * cos;
        this.mob.getNavigation().moveTo(
                this.target.getX() + nx * dist,
                this.target.getY(),
                this.target.getZ() + nz * dist,
                1.15);
    }

    // ---- suppressor pressure ----------------------------------------------
    // Suppressor-class widening of SEM's suppression lives in
    // MixinBulletImpactSuppression (the deployed SEM emits suppression only
    // from bullet IMPACTS via PlayerSuppressionEventHandler - there is no
    // goal-level emit call to redirect).

    @Inject(method = "stop", at = @At("TAIL"))
    private void semcs$exitCrouch(CallbackInfo ci) {
        if (this.semcs$crouching) {
            this.mob.setShiftKeyDown(false);
            this.semcs$crouching = false;
        }
        if (this.semcs$prone) {
            this.mob.setPose(net.minecraft.world.entity.Pose.STANDING);
            this.semcs$prone = false;
        }
        if (this.semcs$aiming) {
            IGunOperator.fromLivingEntity(this.mob).aim(false);
            this.semcs$aiming = false;
        }
    }

    // ---- class weapon lock -------------------------------------------------
    // RETURN-inject on canUse(): after SEM decided the goal may run, we veto
    // it when the held gun belongs to a DIFFERENT weapon class. This is the
    // second layer of the lock - even if a wrong-class gun reaches the hand
    // (commands, NBT edits, another mod), the unit simply will not fire it.
    // remap stays default (true): canUse is a vanilla Goal method, the refmap
    // translates it to the obfuscated name in production.
    @Inject(method = "canUse", at = @At("RETURN"), cancellable = true)
    private void semcs$enforceClassWeapon(CallbackInfoReturnable<Boolean> cir) {
        // SEM already said no -> nothing to do
        if (!cir.getReturnValue()) {
            return;
        }
        // Retune burst/cooldown if the unit's class changed since the ctor
        // (e.g. the /semcs class command re-classed a living unit)
        semcs$applyTuning();
        // No class -> no restriction. JACK is the generalist - it fires
        // anything it can hold.
        // [needs]: a starving unit is too weak to fight - holds fire until
        // it finds food. (Melee stays allowed; desperation is desperation.)
        if (com.poseidon.sem_classsystem.needs.UnitNeeds.refusesToFight(this.mob)) {
            cir.setReturnValue(false);
            return;
        }
        WeaponClass cls = WeaponClassSupport.get(this.mob);
        if (cls == null || cls == WeaponClass.JACK) {
            return;
        }
        // Identify the gun in the main hand through the TaCZ API
        IGun iGun = IGun.getIGunOrNull(this.mob.getMainHandItem());
        ResourceLocation gunId = iGun != null ? iGun.getGunId(this.mob.getMainHandItem()) : null;
        WeaponClass gunClass = gunId == null ? null : ClassLoadouts.inferClass(gunId);
        // Known gun of another MAIN class -> goal cannot run (unit holds fire).
        // Subclass guns (pistols) are exempt - they are universal sidearms,
        // and getEffective() already retuned this goal for pistol cadence.
        if (gunClass != null && gunClass != cls && !gunClass.isSubclass()) {
            cir.setReturnValue(false);
        }
    }
}
