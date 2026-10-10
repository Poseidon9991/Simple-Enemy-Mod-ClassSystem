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
import com.tacz.guns.resource.pojo.data.gun.BulletData;
import net.minecraft.resources.ResourceLocation;
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
        if (cls == null || cls == this.semcs$tunedClass) {
            return;   // no class yet, or already tuned for this class
        }
        this.semcs$tunedClass = cls;

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
        double shootMult = ClassSystemConfig.classShootDistance(cls);
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
        // Crouched firing is steadier - the class's own bonus while sneaking
        double crouch = cls != null && this.mob.isShiftKeyDown()
                ? ClassSystemConfig.classCrouchSpread(cls) : 1.0;

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
        if (entity != this.target
                || WeaponClassSupport.getEffective(this.mob) != WeaponClass.SNIPER) {
            return pos;   // only Campers lead, only their combat target
        }
        ItemStack gun = this.mob.getMainHandItem();
        IGun iGun = IGun.getIGunOrNull(gun);
        ResourceLocation gunId = iGun == null ? null : iGun.getGunId(gun);
        if (gunId == null) {
            return pos;
        }
        BulletData bullet = TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getBulletData()).orElse(null);
        if (bullet == null || bullet.getSpeed() <= 0) {
            return pos;
        }
        // gun data: speed is m/s -> /20 for blocks per tick;
        // gravity is subtracted from delta-Y per tick (blocks/tick^2)
        double dist = this.mob.getEyePosition().distanceTo(
                pos.add(0, entity.getBbHeight() / 2.0, 0));
        double t = Math.min(dist / (bullet.getSpeed() / 20.0), 8.0);
        Vec3 delta = entity.getDeltaMovement();     // blocks per tick
        // Perfect lead is inhuman - players strafing get partially led
        // (dodgeable by changing direction) while monsters get the full
        // prediction for the monster-hunter bonus.
        double leadScale = entity instanceof Monster ? 1.0 : 0.6;
        // don't lead grounded entities' idle gravity drift - only airborne
        // motion (jumps/falls) moves the aim point vertically
        double leadY = entity.onGround() ? 0.0 : delta.y * t * leadScale;
        double drop = Math.min(0.5 * bullet.getGravity() * t * t, 3.0);
        return pos.add(delta.x * t * leadScale, leadY + drop, delta.z * t * leadScale);
    }

    // ---- crouch firing stance ----------------------------------------------
    // Goal lifecycle methods are vanilla (remap stays default). start() puts
    // crouching classes into sneak - the shared entity flag syncs to the
    // client, where MixinUnitModelCrouch turns it into a kneel pose. stop()
    // releases only the flag WE set.
    @Inject(method = "start", at = @At("TAIL"))
    private void semcs$enterCrouch(CallbackInfo ci) {
        WeaponClass cls = WeaponClassSupport.getEffective(this.mob);
        if (cls != null && ClassSystemConfig.classCrouchWhileFiring(cls)) {
            this.mob.setShiftKeyDown(true);
            this.semcs$crouching = true;
        }
    }

    // ---- camper scoped aim -------------------------------------------------
    // TaCZ picks bullet dispersion from the shooter's InaccuracyType, and the
    // AIM tier only applies when aiming progress reaches 1.0. SEM never calls
    // operator.aim() - its one attempt sits commented out next to the shoot
    // call - so units were firing snipers with hip-fire (STAND/MOVE) spread,
    // which for snipers is huge. Assert aim(true) every tick while the goal
    // runs so progress ramps to fully scoped; the shoot redirect holds fire
    // until then. Released in stop().
    // tick is a vanilla Goal method - remap stays default for the refmap.
    @Inject(method = "tick", at = @At("HEAD"))
    private void semcs$assertAim(CallbackInfo ci) {
        if (WeaponClassSupport.getEffective(this.mob) == WeaponClass.SNIPER) {
            IGunOperator.fromLivingEntity(this.mob).aim(true);
            this.semcs$aiming = true;
        }
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
        if (WeaponClassSupport.getEffective(this.mob) == WeaponClass.SNIPER
                && operator.getSynAimingProgress() < 1.0F) {
            operator.aim(true);   // keep the flag asserted between ticks
            return ShootResult.COOL_DOWN;
        }
        return operator.shoot(pitch, yaw);
    }

    @Inject(method = "stop", at = @At("TAIL"))
    private void semcs$exitCrouch(CallbackInfo ci) {
        if (this.semcs$crouching) {
            this.mob.setShiftKeyDown(false);
            this.semcs$crouching = false;
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
        // No class -> no restriction
        WeaponClass cls = WeaponClassSupport.get(this.mob);
        if (cls == null) {
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
