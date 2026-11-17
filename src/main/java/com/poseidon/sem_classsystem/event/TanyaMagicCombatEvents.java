package com.poseidon.sem_classsystem.event;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.entity.VdfUnitEntity;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.server.AmmoHitBlockEvent;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import javax.annotation.Nullable;
import java.util.List;

// Server combat mechanics for the Imperial Aerial Mage ("Tanya") set:
//   1. Active Mana Barrier: intercepts TaCZ bullets and incoming attacks,
//      absorbing configurable damage in exchange for Type-95 mana + impact FX.
//   2. Mana-Infused Bullets (Spell Sniping): imbues fired TaCZ rounds with
//      higher muzzle velocity, boosted impact damage, cyan optical tracer trails,
//      and a non-griefing kinetic mana detonation on target/block hit.
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TanyaMagicCombatEvents {

    public static final String NBT_MANA_BULLET = "semcs_mana_bullet";
    private static final String NBT_MANA_PRIMARY_BLAST = "semcs_mana_blast";
    private static final String NBT_BARRIER_HANDLED = "semcs_barrier_handled";
    private static final String NBT_SPELL_SHOT_TICK = "semcs_spell_tick";
    private static final String NBT_SPELL_SHOT_SUCCEEDED = "semcs_spell_ok";

    private TanyaMagicCombatEvents() {
    }

    // ---- 1. Mana-Infused Bullet Spawn (Server) -------------------------------
    @SubscribeEvent
    public static void onBulletSpawn(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof EntityKineticBullet bullet)) {
            return;
        }
        if (!(bullet.getOwner() instanceof ServerPlayer shooter)) {
            return;
        }
        if (!TanyaFlightEvents.hasFullTanyaSet(shooter) || !TanyaFlightEvents.isSpellBulletsActive(shooter)) {
            return;
        }

        CompoundTag spData = shooter.getPersistentData();
        long now = event.getLevel().getGameTime();
        long lastTick = spData.getLong(NBT_SPELL_SHOT_TICK);
        boolean firstPelletInTick = (lastTick != now);

        // One trigger pull (including multi-pellet shotguns spawned in the same tick)
        // consumes mana once and arms only the primary pellet for AoE detonation.
        if (firstPelletInTick) {
            spData.putLong(NBT_SPELL_SHOT_TICK, now);
            int cost = ClassSystemConfig.tanyaSpellBulletManaCost();
            boolean paid = TanyaFlightEvents.consumeMana(shooter, cost, false);
            spData.putBoolean(NBT_SPELL_SHOT_SUCCEEDED, paid);
            if (!paid) {
                return;
            }

            if (event.getLevel() instanceof ServerLevel server) {
                Vec3 look = shooter.getLookAngle().normalize();
                Vec3 muzzle = shooter.getEyePosition().add(look.scale(1.1D));
                server.sendParticles(SemcsRegistries.MANA_SPARK_PARTICLE.get(), muzzle.x, muzzle.y, muzzle.z,
                        5, 0.08D, 0.08D, 0.08D, 0.04D);
                for (int step = 1; step <= 6; step++) {
                    Vec3 p = muzzle.add(look.scale(step * 1.4D));
                    server.sendParticles(TanyaFlightEvents.MANA_DUST, p.x, p.y, p.z,
                            1, 0.02D, 0.02D, 0.02D, 0.0D);
                }
                server.playSound(null, shooter.blockPosition(),
                        SemcsRegistries.TANYA_SPELL_SHOOT.get(), SoundSource.PLAYERS, 0.95F, 1.0F);
            }
        } else if (!spData.getBoolean(NBT_SPELL_SHOT_SUCCEEDED)) {
            return;
        }

        CompoundTag bTag = bullet.getPersistentData();
        bTag.putBoolean(NBT_MANA_BULLET, true);
        if (firstPelletInTick) {
            bTag.putBoolean(NBT_MANA_PRIMARY_BLAST, true);
        }
        bTag.putIntArray(EntityKineticBullet.TRACER_COLOR_OVERRIDER_KEY, new int[]{24, 252, 240});
        bTag.putFloat(EntityKineticBullet.TRACER_SIZE_OVERRIDER_KEY, 2.35F);

        double velMult = ClassSystemConfig.tanyaSpellBulletVelocityMult();
        if (velMult > 1.001D) {
            bullet.setDeltaMovement(bullet.getDeltaMovement().scale(velMult));
        }
    }

    // ---- 2. TaCZ Gun Hit: Spell Damage/Blast & Barrier Interception ----------
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityHurtByGunPre(EntityHurtByGunEvent.Pre event) {
        if (!event.getLogicalSide().isServer()) {
            return;
        }
        Entity bullet = event.getBullet();
        Entity victim = event.getHurtEntity();

        // A. Shooter Spell-Sniping bonus + kinetic mana burst
        if (bullet != null && bullet.getPersistentData().getBoolean(NBT_MANA_BULLET)) {
            float dmgMult = (float) ClassSystemConfig.tanyaSpellBulletDamageMult();
            if (dmgMult > 1.001F) {
                event.setBaseAmount(event.getBaseAmount() * dmgMult);
            }
            if (victim != null && victim.level() instanceof ServerLevel server) {
                if (victim instanceof LivingEntity living) {
                    living.addEffect(new MobEffectInstance(MobEffects.GLOWING, 60, 0, false, false));
                }
                if (bullet.getPersistentData().getBoolean(NBT_MANA_PRIMARY_BLAST)) {
                    Vec3 impact = victim.position().add(0.0D, victim.getBbHeight() * 0.55D, 0.0D);
                    float splashDmg = event.getBaseAmount() * 0.40F;
                    spawnManaDetonation(server, impact, event.getAttacker(), victim, splashDmg);
                }
            }
        }

        // B. Defender Active Mana Barrier intercepts incoming TaCZ round
        if (victim instanceof ServerPlayer defender && TanyaFlightEvents.isBarrierActive(defender)) {
            float raw = event.getBaseAmount();
            float red = (float) ClassSystemConfig.tanyaBarrierDamageReduction();
            float blocked = raw * red;
            event.setBaseAmount(Math.max(0.1F, raw - blocked));
            if (bullet != null) {
                bullet.getPersistentData().putBoolean(NBT_BARRIER_HANDLED, true);
            }
            Vec3 hitPos = (bullet != null) ? bullet.position() : defender.getEyePosition();
            TanyaFlightEvents.triggerBarrierImpact(defender, hitPos, blocked);
        }
    }

    // ---- 3. Block Impact: Mana Detonation for Spell Sniping ------------------
    @SubscribeEvent
    public static void onAmmoHitBlock(AmmoHitBlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel server)) {
            return;
        }
        EntityKineticBullet bullet = event.getAmmo();
        if (bullet == null
                || !bullet.getPersistentData().getBoolean(NBT_MANA_BULLET)
                || !bullet.getPersistentData().getBoolean(NBT_MANA_PRIMARY_BLAST)) {
            return;
        }
        Vec3 hitPos = event.getHitResult().getLocation();
        LivingEntity shooter = (bullet.getOwner() instanceof LivingEntity le) ? le : null;
        float splashDmg = Math.max(4.0F, bullet.getDamage(hitPos) * 0.50F);
        spawnManaDetonation(server, hitPos, shooter, null, splashDmg);
    }

    // ---- 4. Non-TaCZ Attacks: Active Mana Barrier Absorption -----------------
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingHurt(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide
                || !(event.getEntity() instanceof ServerPlayer defender)) {
            return;
        }
        if (!TanyaFlightEvents.isBarrierActive(defender)) {
            return;
        }
        DamageSource source = event.getSource();
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                || source.is(DamageTypeTags.IS_FALL)
                || source.is(DamageTypeTags.IS_DROWNING)
                || source.is(DamageTypeTags.IS_FREEZING)) {
            return;
        }
        Entity direct = source.getDirectEntity();
        if (direct != null && direct.getPersistentData().getBoolean(NBT_BARRIER_HANDLED)) {
            return; // Already absorbed in EntityHurtByGunEvent.Pre
        }

        float amount = event.getAmount();
        if (amount <= 0.1F) {
            return;
        }

        boolean rangedOrBlast = source.is(DamageTypeTags.IS_PROJECTILE)
                || source.is(DamageTypeTags.IS_EXPLOSION);
        float maxRed = (float) ClassSystemConfig.tanyaBarrierDamageReduction();
        float red = rangedOrBlast ? maxRed : Math.min(0.55F, maxRed * 0.75F);
        float blocked = amount * red;
        event.setAmount(Math.max(0.1F, amount - blocked));

        Vec3 hitPos = (direct != null) ? direct.position() : defender.position().add(0.0D, 1.1D, 0.0D);
        TanyaFlightEvents.triggerBarrierImpact(defender, hitPos, blocked);
    }

    private static void spawnManaDetonation(ServerLevel server, Vec3 center,
                                            @Nullable LivingEntity shooter,
                                            @Nullable Entity directTarget,
                                            float splashDamage) {
        double radius = ClassSystemConfig.tanyaSpellBulletBlastRadius();
        server.sendParticles(SemcsRegistries.MANA_BLAST_PARTICLE.get(), center.x, center.y, center.z,
                1, 0.0D, 0.0D, 0.0D, 0.0D);
        server.sendParticles(SemcsRegistries.MANA_SPARK_PARTICLE.get(), center.x, center.y, center.z,
                18, 0.38D, 0.38D, 0.38D, 0.14D);
        server.sendParticles(TanyaFlightEvents.MANA_DUST, center.x, center.y, center.z,
                14, 0.45D, 0.45D, 0.45D, 0.08D);

        server.playSound(null, center.x, center.y, center.z,
                SemcsRegistries.TANYA_SPELL_BLAST.get(), SoundSource.PLAYERS, 1.15F, 1.0F);

        if (radius <= 0.05D || splashDamage <= 0.1F) {
            return;
        }
        AABB box = new AABB(center, center).inflate(radius);
        DamageSource magicSrc = (shooter != null)
                ? server.damageSources().indirectMagic(shooter, shooter)
                : server.damageSources().magic();

        List<LivingEntity> nearby = server.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive()
                        && e != shooter
                        && e != directTarget
                        && !isFriendlyToShooter(shooter, e));
        double rSq = radius * radius;
        for (LivingEntity target : nearby) {
            double dSq = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D).distanceToSqr(center);
            if (dSq <= rSq) {
                float falloff = 1.0F - (float) (Math.sqrt(dSq) / (radius * 1.25D));
                float dmg = Math.max(0.5F, splashDamage * falloff);
                target.hurt(magicSrc, dmg);
                target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, false, false));
            }
        }
    }

    private static boolean isFriendlyToShooter(@Nullable LivingEntity shooter, LivingEntity target) {
        if (shooter == null) {
            return false;
        }
        if (shooter.isAlliedTo(target) || target.isAlliedTo(shooter)) {
            return true;
        }
        if (target instanceof PmcUnitEntity pmc && shooter.getUUID().equals(pmc.getOwnerUUID())) {
            return true;
        }
        return shooter instanceof Player && target instanceof VdfUnitEntity;
    }
}
