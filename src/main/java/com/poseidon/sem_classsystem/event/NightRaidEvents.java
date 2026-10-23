// Event package - scheduled night ambush raids on players
package com.poseidon.sem_classsystem.event;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.network.PacketRaidPing;
import com.poseidon.sem_classsystem.network.SemcsNetwork;
import com.poseidon.sem_classsystem.block.GuardPostBlockEntity;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.registry.ModEntities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

// Night raid director: each night every player rolls the configured chance
// once; on success a hostile patrol (RU or US, per raid) is scheduled at a
// random minute of the night and spawns 40-60 blocks out, converging on the
// player's position. Tunable under [night_raids] in sem_classsystem-common.toml.
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID)
public final class NightRaidEvents {

    private static final String LAST_CHECK_DAY = "semcs_raid_check_day";
    private static final String RAID_AT = "semcs_raid_at";        // dayTime of the strike
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
                            && level.getRandom().nextFloat() < ClassSystemConfig.nightRaidChance()) {
                        long offset = 400 + level.getRandom().nextInt((int) (NIGHT_END - NIGHT_START - 400));
                        data.putLong(RAID_AT, NIGHT_START + offset);
                    }
                }
                // Early warning ~a minute out: a manned Guard Post inside
                // the player's claims overhears the inbound patrol. Once
                // per night per player; a post with no bound unit is
                // furniture, not a listener.
                int warnTicks = ClassSystemConfig.nightRaidEarlyWarningTicks();
                if (warnTicks > 0 && raidAt > NO_RAID
                        && dayTime >= raidAt - warnTicks && dayTime < raidAt
                        && data.getLong(WARNED_DAY) != today
                        && !player.isSpectator() && !player.isCreative()
                        && watchPostOnline(level, player)) {
                    data.putLong(WARNED_DAY, today);
                    warnPlayer(level, player, raidAt - dayTime);
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

    // Softer horn + ETA text; the map ping centers on the player because
    // the threat is on them - the exact spawn bearing isn't rolled until
    // the strike fires.
    private static void warnPlayer(ServerLevel level, ServerPlayer player, long ticksLeft) {
        player.displayClientMessage(Component.literal(
                "§eGuard post reports hostile chatter - raid inbound in ~"
                        + (ticksLeft / 20) + "s!"), true);
        level.playSound(null, player.blockPosition(), SoundEvents.RAID_HORN.get(),
                SoundSource.HOSTILE, 0.4F, 1.1F);
        SemcsNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PacketRaidPing(level.dimension().location().toString(),
                        player.getX(), player.getZ()));
    }

    private static void spawnRaid(ServerLevel level, ServerPlayer player) {
        boolean russian = level.getRandom().nextBoolean();
        EntityType<? extends AbstractUnit> type = russian
                ? ModEntities.RUUNIT.get() : ModEntities.USUNIT.get();
        int min = ClassSystemConfig.nightRaidSquadSizeMin();
        int max = Math.max(min, ClassSystemConfig.nightRaidSquadSizeMax());
        int size = min + level.getRandom().nextInt(max - min + 1);
        double dist = ClassSystemConfig.nightRaidSpawnDistance();
        double angle = level.getRandom().nextDouble() * Math.PI * 2.0D;
        double cx = player.getX() + Math.cos(angle) * dist;
        double cz = player.getZ() + Math.sin(angle) * dist;

        int spawned = 0;
        for (int i = 0; i < size; i++) {
            AbstractUnit unit = type.create(level);
            if (unit == null) {
                continue;
            }
            double ox = (level.getRandom().nextDouble() - 0.5D) * 8.0D;
            double oz = (level.getRandom().nextDouble() - 0.5D) * 8.0D;
            int sx = (int) Math.floor(cx + ox);
            int sz = (int) Math.floor(cz + oz);
            int sy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz);
            unit.moveTo(sx + 0.5D, sy, sz + 0.5D,
                    level.getRandom().nextFloat() * 360.0F, 0.0F);
            unit.finalizeSpawn(level, level.getCurrentDifficultyAt(new BlockPos(sx, sy, sz)),
                    MobSpawnType.EVENT, null, null);
            unit.setTarget(player);
            unit.getPersistentData().putBoolean("semcs_night_raider", true);
            level.addFreshEntity(unit);
            spawned++;
        }

        if (spawned > 0) {
            player.displayClientMessage(Component.literal(
                    "§cYou hear radio chatter and boots in the dark - " +
                            (russian ? "an RU" : "a US") + " patrol is closing in!"), true);
            level.playSound(null, player.blockPosition(), SoundEvents.RAID_HORN.get(),
                    SoundSource.HOSTILE, 0.9F, 0.7F);
            // Map ping at the spawn centre so the raid shows on the world map
            SemcsNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new PacketRaidPing(level.dimension().location().toString(), cx, cz));
        }
    }
}
