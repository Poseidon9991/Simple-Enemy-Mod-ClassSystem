// Client particle - pixel-art cyan Type-95 mana spark
package com.poseidon.sem_classsystem.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

// Fullbright pixel spark rendered on PARTICLE_SHEET_LIT so every texel stays
// sharp like vanilla Minecraft particles during barrier blocks, flight, and spell shots.
public class ManaSparkParticle extends TextureSheetParticle {

    private final SpriteSet sprite;

    protected ManaSparkParticle(ClientLevel level, double x, double y, double z,
                                double xd, double yd, double zd, SpriteSet sprite) {
        super(level, x, y, z, xd, yd, zd);
        this.sprite = sprite;
        this.lifetime = 6 + this.random.nextInt(5);
        this.gravity = 0.08F;
        this.quadSize = 0.09F + this.random.nextFloat() * 0.07F;
        this.hasPhysics = false;
        this.friction = 0.86F;
        this.rCol = 0.92F;
        this.gCol = 1.0F;
        this.bCol = 1.0F;
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
            return new ManaSparkParticle(level, x, y, z, xd, yd, zd, this.sprite);
        }
    }
}
