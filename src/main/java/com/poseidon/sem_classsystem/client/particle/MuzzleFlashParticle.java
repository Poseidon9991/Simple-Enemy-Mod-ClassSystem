// Client particle - one-frame muzzle flash quad at a unit's muzzle
package com.poseidon.sem_classsystem.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

// Fullbright starburst that lives 2-3 ticks: reads as the gun flash TaCZ
// doesn't draw for non-player shooters.
public class MuzzleFlashParticle extends TextureSheetParticle {

    protected MuzzleFlashParticle(ClientLevel level, double x, double y, double z,
                                  SpriteSet sprite) {
        super(level, x, y, z);
        this.lifetime = 2 + this.random.nextInt(2);
        this.gravity = 0.0F;
        this.quadSize = 0.30F + this.random.nextFloat() * 0.15F;
        this.hasPhysics = false;
        this.setSpriteFromAge(sprite);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_LIT;
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprite;

        public Provider(SpriteSet sprite) {
            this.sprite = sprite;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double xd, double yd, double zd) {
            return new MuzzleFlashParticle(level, x, y, z, this.sprite);
        }
    }
}
