// Event package - scheduled night ambush raids on players
package com.poseidon.sem_classsystem.event;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.block.CommsRadarBlockEntity;
import com.poseidon.sem_classsystem.block.GuardPostBlockEntity;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.network.PacketRaidPing;
import com.poseidon.sem_classsystem.network.SemcsNetwork;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import com.poseidon.sem_classsystem.world.PlayerChunkClaims;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.nekoyuni.SimpleEnemyMod.config.common.FactionsConfig;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.registry.ModEntities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

// Night raid director: each night every player rolls the configured chance
// once; on success a hostile patrol (RU, US, or Terrorist Insurgents) is
// scheduled at a random minute of the night and spawns 40-60 blocks out in
// any biome, converging on the player's position. Tunable under [night_raids].
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID)
public final class NightRaidEvents {

    public static final String RAID_TARGET_KEY = "semcs_raid_target";
    public static final String RAID_UNTIL_KEY = "semcs_raid_until";

    private static final String LAST_CHECK_DAY = "semcs_raid_check_day";
    private static final String RAID_AT = "semcs_raid_at";        // dayTime of the strike
    private static final String RAID_CX = "semcs_raid_cx";
    private static final String RAID_CZ = "semcs_raid_cz";
    private static final String LAST_RAID_DAY = "semcs_last_raid_day";
    private static final String WARNED_DAY = "semcs_raid_warn_day";

    private static final long NIGHT_START = 13000;
    private static final long NIGHT_END = 23000;
    private static final long NO_RAID = -1;

    private NightRaidEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ClassSystemConfig.nightRaidsEnabled()) {
            return;
        }
        ServerLevel level = event.getServer().overworld();
        long dayTime = level.getDayTime() % 24000;
        long today = level.getDayTime() / 24000;

        for (ServerPlayer player : level.players()) {
            CompoundTag data = player.getPersistentData();
            long raidAt = data.getLong(RAID_AT);

            if (dayTime >= NIGHT_START && dayTime <= NIGHT_END) {
                // Roll the night's ambush once, at dusk
                if (data.getLong(LAST_CHECK_DAY) != today) {
                    data.putLong(LAST_CHECK_DAY, today);
                    if (!player.isSpectator() && !player.isCreative()
                            && hasHostileFaction()
                            && level.getRandom().nextFloat() < ClassSystemConfig.nightRaidChance()) {
                        long offset = 400 + level.getRandom().nextInt((int) (NIGHT_END - NIGHT_START - 400));
                        data.putLong(RAID_AT, NIGHT_START + offset);
                        double dist = ClassSystemConfig.nightRaidSpawnDistance();
                        double angle = level.getRandom().nextDouble() * Math.PI * 2.0D;
                        data.putDouble(RAID_CX, player.getX() + Math.cos(angle) * dist);
                        data.putDouble(RAID_CZ, player.getZ() + Math.sin(angle) * dist);
                    }
                }
                // Early warning: everyone hears something coming - a manned
                // Guard Post inside the player's claims overhears the patrol
                // early (full configured lead), while anyone else only picks
                // up the noise shortly before contact. Once per night per
                // player; a post with no bound unit is furniture, not a
                // listener.
                int warnTicks = ClassSystemConfig.nightRaidEarlyWarningTicks();
                if (warnTicks > 0 && raidAt > NO_RAID
                        && data.getLong(WARNED_DAY) != today
                        && !player.isSpectator() && !player.isCreative()) {
                    boolean radarOnline = commsRadarOnline(level, player);
                    boolean postOnline = radarOnline || watchPostOnline(level, player);
                    long lead = postOnline ? warnTicks : Math.min(warnTicks, 300L);
                    if (dayTime >= raidAt - lead && dayTime < raidAt) {
                        data.putLong(WARNED_DAY, today);
                        warnPlayer(level, player, raidAt - dayTime, postOnline, radarOnline);
                    }
                }
                // Strike when the scheduled minute arrives
                if (raidAt > NO_RAID && dayTime >= raidAt
                        && data.getLong(LAST_RAID_DAY) != today
                        && !player.isSpectator() && !player.isCreative()) {
                    data.putLong(RAID_AT, NO_RAID);
                    data.putLong(LAST_RAID_DAY, today);
                    spawnRaid(level, player);
                }
            } else if (raidAt > NO_RAID) {
                data.putLong(RAID_AT, NO_RAID);   // daybreak cancels a pending raid
            }
        }
    }

    // The early-warning net: a Guard Post with at least one bound unit
    // inside the player's claimed chunks in the raid dimension. Only
    // already-loaded chunks are scanned - same rule as stockpile searches.
    private static boolean watchPostOnline(ServerLevel level, ServerPlayer player) {
        String dim = level.dimension().location().toString();
        java.util.Set<Long> claims = PlayerChunkClaims.get(level.getServer().overworld())
                .allIn(dim).get(player.getUUID());
        if (claims == null || claims.isEmpty()) {
            return false;
        }
        for (long chunkLong : claims) {
            int cx = ChunkPos.getX(chunkLong);
            int cz = ChunkPos.getZ(chunkLong);
            if (!(level.getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk chunk)) {
                continue;
            }
            for (java.util.Map.Entry<BlockPos, BlockEntity> be : chunk.getBlockEntities().entrySet()) {
                if (be.getValue() instanceof GuardPostBlockEntity post && !post.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean commsRadarOnline(ServerLevel level, ServerPlayer player) {
        String dim = level.dimension().location().toString();
        java.util.Set<Long> claims = PlayerChunkClaims.get(level.getServer().overworld())
                .allIn(dim).get(player.getUUID());
        if (claims == null || claims.isEmpty()) {
            return false;
        }
        for (long chunkLong : claims) {
            int cx = ChunkPos.getX(chunkLong);
            int cz = ChunkPos.getZ(chunkLong);
            if (!(level.getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk chunk)) {
                continue;
            }
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                if (be instanceof CommsRadarBlockEntity) {
                    return true;
                }
            }
        }
        return false;
    }

    // SEM faction diplomacy + Terrorist raids: a faction configured as friendly
    // (simpleenemymod [factions] *_UNITS_FRIENDLY) never fields a raid squad,
    // while Terrorist Insurgents are universally hostile and can raid in any biome.
    private static boolean hasHostileFaction() {
        return !Boolean.TRUE.equals(FactionsConfig.RU_UNITS_FRIENDLY.get())
                || !Boolean.TRUE.equals(FactionsConfig.US_UNITS_FRIENDLY.get())
                || ClassSystemConfig.terroristRaidsEnabled();
    }

    // Picks the raiding faction among all currently hostile raid-eligible factions.
    @org.jetbrains.annotations.Nullable
    private static EntityType<? extends AbstractUnit> raidFaction(
            net.minecraft.util.RandomSource random) {
        List<EntityType<? extends AbstractUnit>> eligible = new ArrayList<>(3);
        if (!Boolean.TRUE.equals(FactionsConfig.RU_UNITS_FRIENDLY.get())) {
            eligible.add(ModEntities.RUUNIT.get());
        }
        if (!Boolean.TRUE.equals(FactionsConfig.US_UNITS_FRIENDLY.get())) {
            eligible.add(ModEntities.USUNIT.get());
        }
        if (ClassSystemConfig.terroristRaidsEnabled()) {
            eligible.add(SemcsRegistries.TERRORIST_UNIT.get());
        }
        if (eligible.isEmpty()) {
            return null;
        }
        return eligible.get(random.nextInt(eligible.size()));
    }

    // Softer horn + ETA text; the map ping centers on the player because
    // the threat is on them - the exact spawn bearing isn't rolled until
    // the strike fires. A manned Guard Post reports it as intercepted
    // chatter; everyone else just hears the approach.
    private static void warnPlayer(ServerLevel level, ServerPlayer player,
                                   long ticksLeft, boolean postOnline, boolean radarOnline) {
        CompoundTag data = player.getPersistentData();
        double pingX = player.getX();
        double pingZ = player.getZ();
        if (radarOnline && data.contains(RAID_CX) && data.contains(RAID_CZ)) {
            pingX = data.getDouble(RAID_CX);
            pingZ = data.getDouble(RAID_CZ);
            double dx = pingX - player.getX();
            double dz = pingZ - player.getZ();
            int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
            String bearing = compassBearing(dx, dz);
            player.displayClientMessage(Component.translatable(
                    "message.sem_classsystem.raid_warning_radar", bearing, dist, ticksLeft / 20), true);
        } else {
            player.displayClientMessage(postOnline
                    ? Component.translatable("message.sem_classsystem.raid_warning_post", ticksLeft / 20)
                    : Component.translatable("message.sem_classsystem.raid_warning_generic"), true);
        }
        level.playSound(null, player.blockPosition(), SoundEvents.RAID_HORN.get(),
                SoundSource.HOSTILE, postOnline ? 0.4F : 0.25F, 1.1F);
        SemcsNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketRaidPing(level.dimension().location().toString(), pingX, pingZ));
    }

    private static String compassBearing(double dx, double dz) {
        double deg = Math.toDegrees(Math.atan2(dx, -dz));
        if (deg < 0.0D) {
            deg += 360.0D;
        }
        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return dirs[(int) Math.round(deg / 45.0D) % 8];
    }

    private static void spawnRaid(ServerLevel level, ServerPlayer player) {
        EntityType<? extends AbstractUnit> type = raidFaction(level.getRandom());
        if (type == null) {
            return;                                     // every faction is friendly now - stand down
        }
        boolean russian = type == ModEntities.RUUNIT.get();
        boolean terrorist = type == SemcsRegistries.TERRORIST_UNIT.get();
        int min = ClassSystemConfig.nightRaidSquadSizeMin();
        int max = Math.max(min, ClassSystemConfig.nightRaidSquadSizeMax());
        int size = min + level.getRandom().nextInt(max - min + 1);
        CompoundTag data = player.getPersistentData();
        double cx;
        double cz;
        if (data.contains(RAID_CX) && data.contains(RAID_CZ)) {
            cx = data.getDouble(RAID_CX);
            cz = data.getDouble(RAID_CZ);
            data.remove(RAID_CX);
            data.remove(RAID_CZ);
        } else {
            double dist = ClassSystemConfig.nightRaidSpawnDistance();
            double angle = level.getRandom().nextDouble() * Math.PI * 2.0D;
            cx = player.getX() + Math.cos(angle) * dist;
            cz = player.getZ() + Math.sin(angle) * dist;
        }

        int spawned = 0;
        for (int i = 0; i < size; i++) {
            AbstractUnit unit = type.create(level);
            if (unit == null) {
                continue;
            }
            BlockPos spawnPos = findRaidSpawnSpot(level, cx, cz);
            if (spawnPos == null) {
                continue;
            }
            unit.moveTo(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D,
                    level.getRandom().nextFloat() * 360.0F, 0.0F);
            unit.finalizeSpawn(level, level.getCurrentDifficultyAt(spawnPos),
                    MobSpawnType.EVENT, null, null);
            var follow = unit.getAttribute(Attributes.FOLLOW_RANGE);
            if (follow != null && follow.getBaseValue() < 80.0D) {
                follow.setBaseValue(80.0D);
            }
            unit.getPersistentData().putUUID(RAID_TARGET_KEY, player.getUUID());
            unit.getPersistentData().putLong(RAID_UNTIL_KEY, level.getGameTime() + 12000L);
            unit.setTarget(player);
            unit.getEntityData().set(AbstractUnit.IS_IN_COMBAT, true);
            unit.getNavigation().moveTo(player, 1.15D);
            level.addFreshEntity(unit);
            spawned++;
        }

        if (spawned > 0) {
            Component alertMsg = terrorist
                    ? Component.translatable("message.sem_classsystem.raid_spawn_insurgent")
                    : Component.translatable("message.sem_classsystem.raid_spawn_patrol", russian ? "RU" : "US");
            player.displayClientMessage(alertMsg, true);
            level.playSound(null, player.blockPosition(), SoundEvents.RAID_HORN.get(),
                    SoundSource.HOSTILE, 0.9F, 0.7F);
            // Map ping at the spawn centre so the raid shows on the world map
            SemcsNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new PacketRaidPing(level.dimension().location().toString(), cx, cz));
        }
    }

    @org.jetbrains.annotations.Nullable
    private static BlockPos findRaidSpawnSpot(ServerLevel level, double cx, double cz) {
        BlockPos fallback = null;
        for (int tries = 0; tries < 8; tries++) {
            double ox = (level.getRandom().nextDouble() - 0.5D) * 10.0D;
            double oz = (level.getRandom().nextDouble() - 0.5D) * 10.0D;
            int sx = (int) Math.floor(cx + ox);
            int sz = (int) Math.floor(cz + oz);
            int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz);
            BlockPos pos = new BlockPos(sx, sy, sz);
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            if (fallback == null) {
                fallback = pos;
            }
            if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                    && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                    && level.getFluidState(pos).isEmpty()
                    && level.getFluidState(pos.below()).isEmpty()) {
                return pos;
            }
        }
        return fallback;
    }
}
