package com.poseidon.sem_classsystem.event;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.network.PacketSyncTanyaState;
import com.poseidon.sem_classsystem.network.SemcsNetwork;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.joml.Vector3f;

// Full-set Type-95 Imperial Aerial Mage ("Tanya") mana & ability controller:
//   - Timed creative-style flight ([tanya_armor] flightMinutes)
//   - Active Mana Barrier (hexagonal shield toggled via B)
//   - Mana-Infused Spell Sniping bullets (toggled via N, enabled by default)
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TanyaFlightEvents {

    private static final String NBT_MANAGED_FLY = "semcs_tanya_mayfly";
    private static final String NBT_FLIGHT_TICKS = "semcs_tanya_flight_ticks";
    private static final String NBT_WAS_FULL = "semcs_tanya_was_full";
    private static final String NBT_BARRIER_ACTIVE = "semcs_tanya_barrier";
    private static final String NBT_SPELL_BULLETS = "semcs_tanya_spell_bullets";
    private static final String NBT_SPELL_INIT = "semcs_tanya_spell_init";
    public static final DustParticleOptions MANA_DUST =
            new DustParticleOptions(new Vector3f(0.08F, 0.96F, 0.92F), 0.75F);

    private TanyaFlightEvents() {
    }

    public static boolean hasFullTanyaSet(Player player) {
        return player.getItemBySlot(EquipmentSlot.HEAD).is(SemcsRegistries.TANYA_HELMET.get())
                && player.getItemBySlot(EquipmentSlot.CHEST).is(SemcsRegistries.TANYA_VEST.get())
                && player.getItemBySlot(EquipmentSlot.LEGS).is(SemcsRegistries.TANYA_PANTS.get())
                && player.getItemBySlot(EquipmentSlot.FEET).is(SemcsRegistries.TANYA_BOOTS.get());
    }

    public static int maxManaTicks() {
        return Math.max(200, (int) Math.round(ClassSystemConfig.tanyaFlightMinutes() * 60.0D * 20.0D));
    }

    public static int getManaTicks(Player player) {
        int maxTicks = maxManaTicks();
        CompoundTag pdata = player.getPersistentData();
        return pdata.contains(NBT_FLIGHT_TICKS)
                ? Math.min(maxTicks, Math.max(0, pdata.getInt(NBT_FLIGHT_TICKS)))
                : maxTicks;
    }

    public static boolean isBarrierActive(Player player) {
        if (!hasFullTanyaSet(player)) {
            return false;
        }
        if (player.isCreative() || player.isSpectator()) {
            return player.getPersistentData().getBoolean(NBT_BARRIER_ACTIVE);
        }
        return player.getPersistentData().getBoolean(NBT_BARRIER_ACTIVE) && getManaTicks(player) > 0;
    }

    public static boolean isSpellBulletsActive(Player player) {
        if (!hasFullTanyaSet(player)) {
            return false;
        }
        CompoundTag pdata = player.getPersistentData();
        if (!pdata.getBoolean(NBT_SPELL_INIT)) {
            pdata.putBoolean(NBT_SPELL_INIT, true);
            pdata.putBoolean(NBT_SPELL_BULLETS, true);
        }
        return pdata.getBoolean(NBT_SPELL_BULLETS);
    }

    public static boolean consumeMana(ServerPlayer player, int amount, boolean hitFlash) {
        if (amount <= 0 || player.isCreative() || player.isSpectator()) {
            if (hitFlash) {
                syncState(player, true);
            }
            return true;
        }
        int maxTicks = maxManaTicks();
        int current = getManaTicks(player);
        if (current < amount) {
            return false;
        }
        int next = current - amount;
        player.getPersistentData().putInt(NBT_FLIGHT_TICKS, next);
        if (next == 0 && player.getPersistentData().getBoolean(NBT_BARRIER_ACTIVE)) {
            shatterBarrierOnDepletion(player);
        } else {
            syncState(player, hitFlash);
        }
        return true;
    }

    public static void triggerBarrierImpact(ServerPlayer defender, Vec3 impactPos, float blockedDamage) {
        int burstCost = Math.max(12, Math.round(blockedDamage * 10.0F));
        CompoundTag pdata = defender.getPersistentData();
        if (!defender.isCreative() && !defender.isSpectator()) {
            int next = Math.max(0, getManaTicks(defender) - burstCost);
            pdata.putInt(NBT_FLIGHT_TICKS, next);
            if (next == 0) {
                shatterBarrierOnDepletion(defender);
                return;
            }
        }
        if (defender.level() instanceof ServerLevel server) {
            server.sendParticles(SemcsRegistries.MANA_SPARK_PARTICLE.get(),
                    impactPos.x, impactPos.y, impactPos.z, 10, 0.25D, 0.25D, 0.25D, 0.08D);
            server.sendParticles(MANA_DUST,
                    impactPos.x, impactPos.y, impactPos.z, 8, 0.30D, 0.30D, 0.30D, 0.04D);
            server.playSound(null, defender.blockPosition(),
                    SemcsRegistries.TANYA_BARRIER_HIT.get(), SoundSource.PLAYERS, 0.95F, 1.0F);
        }
        syncState(defender, true);
    }

    public static void toggleBarrier(ServerPlayer player) {
        if (!hasFullTanyaSet(player)) {
            player.displayClientMessage(Component.translatable("message.sem_classsystem.tanya_need_full_set")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        CompoundTag pdata = player.getPersistentData();
        boolean next = !pdata.getBoolean(NBT_BARRIER_ACTIVE);
        if (next && !player.isCreative() && !player.isSpectator() && getManaTicks(player) < 40) {
            player.displayClientMessage(Component.translatable("message.sem_classsystem.tanya_mana_low")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        pdata.putBoolean(NBT_BARRIER_ACTIVE, next);
        player.level().playSound(null, player.blockPosition(),
                next ? SemcsRegistries.TANYA_BARRIER_DEPLOY.get() : SoundEvents.BEACON_DEACTIVATE,
                SoundSource.PLAYERS, 0.75F, next ? 1.0F : 1.15F);
        player.displayClientMessage(Component.translatable(next
                        ? "message.sem_classsystem.tanya_barrier_on"
                        : "message.sem_classsystem.tanya_barrier_off")
                .withStyle(next ? ChatFormatting.AQUA : ChatFormatting.GRAY), true);
        syncState(player, false);
    }

    public static void toggleSpellBullets(ServerPlayer player) {
        if (!hasFullTanyaSet(player)) {
            player.displayClientMessage(Component.translatable("message.sem_classsystem.tanya_need_full_set")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        boolean next = !isSpellBulletsActive(player);
        player.getPersistentData().putBoolean(NBT_SPELL_BULLETS, next);
        player.level().playSound(null, player.blockPosition(),
                next ? SemcsRegistries.TANYA_SPELL_SHOOT.get() : SoundEvents.BEACON_DEACTIVATE,
                SoundSource.PLAYERS, 0.65F, next ? 1.25F : 0.85F);
        player.displayClientMessage(Component.translatable(next
                        ? "message.sem_classsystem.tanya_spell_on"
                        : "message.sem_classsystem.tanya_spell_off")
                .withStyle(next ? ChatFormatting.AQUA : ChatFormatting.GRAY), true);
        syncState(player, false);
    }

    private static void shatterBarrierOnDepletion(ServerPlayer player) {
        player.getPersistentData().putBoolean(NBT_BARRIER_ACTIVE, false);
        if (player.level() instanceof ServerLevel server) {
            server.sendParticles(MANA_DUST, player.getX(), player.getY() + 1.0D, player.getZ(),
                    24, 0.65D, 0.65D, 0.65D, 0.08D);
            server.playSound(null, player.blockPosition(),
                    SemcsRegistries.TANYA_BARRIER_SHATTER.get(), SoundSource.PLAYERS, 0.95F, 1.0F);
        }
        player.displayClientMessage(Component.translatable("message.sem_classsystem.tanya_barrier_broken")
                .withStyle(ChatFormatting.RED), true);
        syncState(player, true);
    }

    public static void syncState(ServerPlayer player, boolean hitFlash) {
        PacketSyncTanyaState pkt = new PacketSyncTanyaState(
                player.getId(),
                isBarrierActive(player),
                isSpellBulletsActive(player),
                getManaTicks(player),
                maxManaTicks(),
                hitFlash
        );
        SemcsNetwork.CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player), pkt);
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp && hasFullTanyaSet(sp)) {
            syncState(sp, false);
        }
    }

    @SubscribeEvent
    public static void onPlayerStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof ServerPlayer target
                && event.getEntity() instanceof ServerPlayer viewer
                && hasFullTanyaSet(target)) {
            SemcsNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> viewer), new PacketSyncTanyaState(
                    target.getId(),
                    isBarrierActive(target),
                    isSpellBulletsActive(target),
                    getManaTicks(target),
                    maxManaTicks(),
                    false
            ));
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Player player = event.player;
        if (player.level().isClientSide || !(player instanceof ServerPlayer sp)) {
            return;
        }

        CompoundTag pdata = player.getPersistentData();
        int maxTicks = maxManaTicks();

        // Never override Creative or Spectator flight permissions, but still keep abilities/sync clean
        if (player.isCreative() || player.isSpectator()) {
            pdata.remove(NBT_MANAGED_FLY);
            pdata.putInt(NBT_FLIGHT_TICKS, maxTicks);
            if (hasFullTanyaSet(player) && player.tickCount % 20 == 0) {
                syncState(sp, false);
            }
            return;
        }

        int ticksLeft = getManaTicks(player);
        boolean fullSet = hasFullTanyaSet(player);

        if (!fullSet) {
            boolean dirty = pdata.getBoolean(NBT_WAS_FULL) || pdata.getBoolean(NBT_BARRIER_ACTIVE);
            pdata.putBoolean(NBT_WAS_FULL, false);
            pdata.putBoolean(NBT_BARRIER_ACTIVE, false);
            if (pdata.getBoolean(NBT_MANAGED_FLY)) {
                pdata.putBoolean(NBT_MANAGED_FLY, false);
                boolean wasFlying = player.getAbilities().flying;
                player.getAbilities().mayfly = false;
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
                if (wasFlying && !player.onGround()) {
                    player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 160, 0, false, false));
                }
            }
            if (ticksLeft < maxTicks) {
                int step = Math.max(1, ClassSystemConfig.tanyaFlightRechargePerTick());
                pdata.putInt(NBT_FLIGHT_TICKS, Math.min(maxTicks, ticksLeft + step));
            }
            if (dirty) {
                syncState(sp, false);
            }
            return;
        }

        // First tick equipping the full set: initialize spell bullets on & notify
        if (!pdata.getBoolean(NBT_WAS_FULL)) {
            pdata.putBoolean(NBT_WAS_FULL, true);
            isSpellBulletsActive(player);
            player.displayClientMessage(Component.translatable(
                    "message.sem_classsystem.tanya_flight_ready", formatDuration(ticksLeft)), true);
            player.level().playSound(null, player.blockPosition(),
                    SemcsRegistries.TANYA_FLIGHT_ACTIVATE.get(), SoundSource.PLAYERS, 0.75F, 1.0F);
            syncState(sp, false);
        }

        boolean draining = false;

        if (player.getAbilities().flying) {
            ticksLeft = Math.max(0, ticksLeft - 1);
            draining = true;

            if (player.level() instanceof ServerLevel server && player.tickCount % 2 == 0) {
                server.sendParticles(MANA_DUST,
                        player.getX(), player.getY() + 0.25D, player.getZ(),
                        2, 0.22D, 0.12D, 0.22D, 0.01D);
                if (player.tickCount % 6 == 0) {
                    server.sendParticles(ParticleTypes.GLOW,
                            player.getX(), player.getY() + 0.75D, player.getZ(),
                            1, 0.2D, 0.15D, 0.2D, 0.01D);
                }
            }

            if (ticksLeft == 0) {
                pdata.putInt(NBT_FLIGHT_TICKS, 0);
                pdata.putBoolean(NBT_MANAGED_FLY, false);
                pdata.putBoolean(NBT_BARRIER_ACTIVE, false);
                player.getAbilities().mayfly = false;
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
                player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 160, 0, false, false));
                player.level().playSound(null, player.blockPosition(),
                        SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.5F, 1.2F);
                player.displayClientMessage(Component.translatable(
                        "message.sem_classsystem.tanya_flight_depleted"), true);
                syncState(sp, true);
                return;
            }
        }

        if (pdata.getBoolean(NBT_BARRIER_ACTIVE)) {
            int barrierDrain = ClassSystemConfig.tanyaBarrierDrainPerTick();
            if (barrierDrain > 0) {
                ticksLeft = Math.max(0, ticksLeft - barrierDrain);
                draining = true;
            }
            if (ticksLeft == 0) {
                pdata.putInt(NBT_FLIGHT_TICKS, 0);
                shatterBarrierOnDepletion(sp);
                return;
            }
        }

        if (draining) {
            pdata.putInt(NBT_FLIGHT_TICKS, ticksLeft);
        } else if (player.onGround() && ticksLeft < maxTicks) {
            // Recharging only while landed and neither flying nor holding barrier up
            int step = Math.max(1, ClassSystemConfig.tanyaFlightRechargePerTick());
            int before = ticksLeft;
            ticksLeft = Math.min(maxTicks, ticksLeft + step);
            pdata.putInt(NBT_FLIGHT_TICKS, ticksLeft);
            if (before < maxTicks && ticksLeft == maxTicks) {
                player.displayClientMessage(Component.translatable(
                        "message.sem_classsystem.tanya_flight_recharged"), true);
                player.level().playSound(null, player.blockPosition(),
                        SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.45F, 1.35F);
            }
        }

        if (!player.getAbilities().flying) {
            int takeoffMin = Math.min(100, maxTicks); // 5s hysteresis threshold before re-enabling
            boolean canEnable = ticksLeft >= takeoffMin;
            boolean alreadyManaged = pdata.getBoolean(NBT_MANAGED_FLY);
            if (canEnable && !player.getAbilities().mayfly) {
                player.getAbilities().mayfly = true;
                pdata.putBoolean(NBT_MANAGED_FLY, true);
                player.onUpdateAbilities();
            } else if (!canEnable && alreadyManaged) {
                pdata.putBoolean(NBT_MANAGED_FLY, false);
                player.getAbilities().mayfly = false;
                player.onUpdateAbilities();
            }
        }

        if (player.tickCount % 10 == 0) {
            syncState(sp, false);
        }
    }

    private static String formatDuration(int ticks) {
        int totalSec = Math.max(0, (ticks + 19) / 20);
        int mins = totalSec / 60;
        int secs = totalSec % 60;
        if (mins > 0) {
            return String.format("%dm %02ds", mins, secs);
        }
        return secs + "s";
    }
}
