// Event package - gunfire visuals for SEM units (zero-dependency particles)
package com.poseidon.sem_classsystem.event;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.common.GunShootEvent;
import com.tacz.guns.api.event.server.AmmoHitBlockEvent;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

// TaCZ only draws muzzle flashes for the local player's own gun, so unit
// fire reads silent/flat in firefights. These subscribers piggyback the
// same gun events TaCZ posts and spawn our custom particles (registry/
// SemcsRegistries) for ANY AbstractUnit shooter - PMC, BHQ, RU, US alike.
// Player shots are untouched: TaCZ already renders their own VFX.
// Everything here is server-side sendParticles, so clients (and dedicated
// servers) stay clean - particles sync through vanilla packets.
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID)
public final class UnitEffectsEvents {

    private UnitEffectsEvents() {
    }

    // One spark shower per bullet-vs-block impact, biased off the hit face.
    @SubscribeEvent
    public static void onAmmoHitBlock(AmmoHitBlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getAmmo().getOwner() instanceof AbstractUnit)) {
            return;
        }
        Vec3 pos = event.getHitResult().getLocation();
        Direction face = event.getHitResult().getDirection();
        level.sendParticles(SemcsRegistries.SPARK_PARTICLE.get(),
                pos.x, pos.y, pos.z,
                6 + level.getRandom().nextInt(4),
                0.12D + face.getStepX() * 0.4D,
                0.12D + face.getStepY() * 0.4D,
                0.12D + face.getStepZ() * 0.4D,
                0.45D);
    }

    // Flash + a couple of sparks at the muzzle when a unit fires a shot.
    @SubscribeEvent
    public static void onGunShoot(GunShootEvent event) {
        if (event.getLogicalSide() != LogicalSide.SERVER
                || !(event.getShooter() instanceof AbstractUnit unit)
                || !(unit.level() instanceof ServerLevel level)) {
            return;
        }
        // Approximate muzzle: eye height minus a drop, pushed forward along
        // the look vector (unit guns sit low, roughly chest high).
        Vec3 muzzle = unit.getEyePosition()
                .add(unit.getLookAngle().scale(0.65D))
                .add(0.0D, -0.25D, 0.0D);
        level.sendParticles(SemcsRegistries.MUZZLE_FLASH_PARTICLE.get(),
                muzzle.x, muzzle.y, muzzle.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(SemcsRegistries.SPARK_PARTICLE.get(),
                muzzle.x, muzzle.y, muzzle.z, 2, 0.03D, 0.03D, 0.03D, 0.3D);
    }

    // Bullet-vs-flesh: a tight puff so hits on entities read, matching the
    // block-impact sparks. Headshots spray a bit harder.
    @SubscribeEvent
    public static void onEntityHurtByGun(EntityHurtByGunEvent.Post event) {
        if (event.getLogicalSide() != LogicalSide.SERVER
                || !(event.getAttacker() instanceof AbstractUnit)
                || !(event.getHurtEntity() instanceof LivingEntity victim)
                || !(victim.level() instanceof ServerLevel level)) {
            return;
        }
        int count = event.isHeadShot() ? 8 : 4;
        level.sendParticles(SemcsRegistries.SPARK_PARTICLE.get(),
                victim.getX(), victim.getY() + victim.getBbHeight() * 0.6D, victim.getZ(),
                count, 0.15D, 0.15D, 0.15D, 0.35D);
    }
}
