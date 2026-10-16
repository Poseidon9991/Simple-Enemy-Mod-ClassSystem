// Client particle - drifting "Z" for sleeping units
package com.poseidon.sem_classsystem.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

// Translucent Z that rises and sways side-to-side while fading - reads
// better than the vanilla NOTE particle at night and from a distance.
public class ZzzParticle extends TextureSheetParticle {

    private final SpriteSet sprite;

    protected ZzzParticle(ClientLevel level, double x, double y, double z,
                          SpriteSet sprite) {
        super(level, x, y, z);
        this.sprite = sprite;
        this.lifetime = 35 + this.random.nextInt(15);
        this.gravity = -0.008F;   // slow rise
        this.quadSize = 0.09F + this.random.nextFloat() * 0.06F;
        this.hasPhysics = false;
        this.friction = 0.92F;
        this.xd = this.random.nextGaussian() * 0.004;
        this.zd = this.random.nextGaussian() * 0.004;
        this.yd = 0.015;
        this.setSpriteFromAge(sprite);
    }

    @Override
    public void tick() {
        super.tick();
        // gentle horizontal sway like rising smoke
        this.xd = Math.sin(this.age * 0.25) * 0.008;
        // fade in fast, fade out over the last half of life
        float half = this.lifetime / 2.0F;
        this.alpha = this.age < 5 ? this.age / 5.0F
                : Math.min(1.0F, (this.lifetime - this.age) / half);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
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
            return new ZzzParticle(level, x, y, z, this.sprite);
        }
    }
}
