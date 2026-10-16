// Client particle - bullet impact / muzzle spray spark for SEM units
package com.poseidon.sem_classsystem.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

// Short-lived hot spark: renders on the LIT sheet so it shows at full
// brightness (no shader needed), falls with gravity, dies fast.
public class SparkParticle extends TextureSheetParticle {

    private final SpriteSet sprite;

    protected SparkParticle(ClientLevel level, double x, double y, double z,
                            double xd, double yd, double zd, SpriteSet sprite) {
        super(level, x, y, z, xd, yd, zd);
        this.sprite = sprite;
        this.lifetime = 4 + this.random.nextInt(5);
        this.gravity = 0.6F;
        this.quadSize = 0.05F + this.random.nextFloat() * 0.05F;
        this.hasPhysics = true;
        this.friction = 0.75F;
        this.rCol = 1.0F;
        this.gCol = 0.85F;
        this.bCol = 0.45F;
        this.setSpriteFromAge(sprite);
    }

    @Override
    public void tick() {
        super.tick();
        this.setSpriteFromAge(this.sprite);
        this.alpha = 1.0F - (float) this.age / (float) this.lifetime;
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
            return new SparkParticle(level, x, y, z, xd, yd, zd, this.sprite);
        }
    }
}
