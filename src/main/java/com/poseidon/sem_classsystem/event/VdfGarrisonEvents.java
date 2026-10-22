// Event package - turns villager arrivals into one-time village garrisons
package com.poseidon.sem_classsystem.event;

import com.poseidon.sem_classsystem.SemClassSystem;                    // MODID for the subscriber annotation
import com.poseidon.sem_classsystem.config.ClassSystemConfig;          // [vdf] enabled + garrisonSize
import com.poseidon.sem_classsystem.entity.VdfUnitEntity;              // the militia we spawn
import com.poseidon.sem_classsystem.registry.SemcsRegistries;          // the VDF entity type
import com.poseidon.sem_classsystem.world.VillageDefenseSavedData;     // garrison debounce record
import net.minecraft.core.BlockPos;                                    // bell + spawn positions
import net.minecraft.server.level.ServerLevel;                         // POI index + entity spawning
import net.minecraft.world.DifficultyInstance;                         // finalizeSpawn argument
import net.minecraft.world.entity.MobSpawnType;                        // NATURAL spawn classification
import net.minecraft.world.entity.ai.village.poi.PoiManager;           // village bell lookup
import net.minecraft.world.entity.ai.village.poi.PoiTypes;             // MEETING = village bell
import net.minecraft.world.entity.npc.Villager;                        // the trigger entity
import net.minecraft.world.phys.AABB;                                  // living-VDF scan box
import net.minecraftforge.event.entity.EntityJoinLevelEvent;           // fires when any entity enters a level
import net.minecraftforge.eventbus.api.SubscribeEvent;                 // handler annotation
import net.minecraftforge.fml.common.Mod;                              // subscriber annotation

import java.util.Map;
import java.util.Optional;                                             // bell lookup result
import java.util.concurrent.ConcurrentHashMap;

// One-time garrison rule: when a villager joins a level near a village bell
// and no living VDF stands guard nearby, spawn the whole garrison at once.
// The SavedData anchor debounces villager group-joins; when the militia is
// wiped the NEXT new villager re-arms the village - "dead stays dead until
// new villagers spawn", per the spec.
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class VdfGarrisonEvents {

    private static final double GUARD_RADIUS = 96.0D;                   // "someone already guards this village" radius
    private static final int DEBOUNCE_TICKS = 200;                      // same-join debounce window (~10 s)
    private static final int BELL_SEARCH = 96;                          // how far from the villager to look for a bell
    private static final int SPAWN_TRIES = 8;                           // placement attempts per militia member

    // In-memory throttle: when a village chunk loads, 15-20 villagers fire
    // EntityJoinLevelEvent in the same tick. Caching the per-chunk and per-bell
    // check timestamp avoids repeating PoiManager + 192-block AABB scans for
    // every villager after the initial SavedData debounce window expires.
    private static final Map<Long, Long> CHUNK_SCAN_TICK = new ConcurrentHashMap<>();
    private static final Map<Long, Long> BELL_GUARDED_UNTIL = new ConcurrentHashMap<>();
    private static long lastSweepTick = 0L;

    private VdfGarrisonEvents() {
    }

    @SubscribeEvent
    public static void onVillagerJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()                             // spawning is server-side only
                || !(event.getEntity() instanceof Villager villager)    // only villager arrivals trigger garrisons
                || !ClassSystemConfig.vdfEnabled()                      // [vdf] enabled = off -> whole system sleeps
                || !(event.getLevel() instanceof ServerLevel level)) {  // need the server level for POI + spawning
            return;
        }

        long now = level.getGameTime();
        long chunkKey = villager.chunkPosition().toLong()
                ^ ((long) level.dimension().location().hashCode() << 32);
        Long lastChunkTick = CHUNK_SCAN_TICK.get(chunkKey);
        if (lastChunkTick != null && now - lastChunkTick < 20L) {
            return;                                                     // another villager in this chunk just checked
        }
        CHUNK_SCAN_TICK.put(chunkKey, now);

        // Both maps only ever grow otherwise - every explored chunk and
        // bell leaves an entry behind on long-running servers. Entries past
        // their debounce window are dead weight, so sweep occasionally.
        if (now - lastSweepTick > 6000L) {
            lastSweepTick = now;
            CHUNK_SCAN_TICK.values().removeIf(t -> now - t > DEBOUNCE_TICKS);
            BELL_GUARDED_UNTIL.values().removeIf(t -> t < now);
        }

        Optional<BlockPos> bell = level.getPoiManager().findClosest(    // the village's meeting point
                holder -> holder.is(PoiTypes.MEETING),
                villager.blockPosition(), BELL_SEARCH,
                PoiManager.Occupancy.ANY);
        if (bell.isEmpty()) {
            return;                                                     // wild villager - no village to defend
        }

        long bellKey = bell.get().asLong() ^ ((long) level.dimension().location().hashCode() << 32);
        Long guardedUntil = BELL_GUARDED_UNTIL.get(bellKey);
        if (guardedUntil != null && now < guardedUntil) {
            return;                                                     // recently verified guarded in memory
        }

        VillageDefenseSavedData data = VillageDefenseSavedData.get(level);
        if (data.recentlyGarrisoned(level.dimension(), bell.get(),
                now, DEBOUNCE_TICKS)) {
            BELL_GUARDED_UNTIL.put(bellKey, now + DEBOUNCE_TICKS);
            return;                                                     // garrison just spawned - group joins debounced
        }

        AABB guardBox = new AABB(bell.get()).inflate(GUARD_RADIUS);     // "is anyone still guarding" scan area
        if (!level.getEntitiesOfClass(VdfUnitEntity.class, guardBox,
                VdfUnitEntity::isAlive).isEmpty()) {
            BELL_GUARDED_UNTIL.put(bellKey, now + DEBOUNCE_TICKS);
            return;                                                     // garrison alive and posted - nothing to do
        }

        BELL_GUARDED_UNTIL.put(bellKey, now + DEBOUNCE_TICKS);
        data.markGarrisoned(level.dimension(), bell.get(), now);
        spawnGarrison(level, bell.get());                               // roll out the militia
    }

    // Places garrisonSize militia around the bell, each collision-checked
    // (same feet+head rule as the canopy fix) and anchored to the village.
    private static void spawnGarrison(ServerLevel level, BlockPos bell) {
        int count = ClassSystemConfig.vdfGarrisonSize();                // [vdf] garrisonSize
        DifficultyInstance difficulty = level.getCurrentDifficultyAt(bell);

        for (int i = 0; i < count; i++) {
            for (int tries = 0; tries < SPAWN_TRIES; tries++) {
                BlockPos spot = bell.offset(                            // scatter ±8 blocks around the bell
                        level.random.nextInt(17) - 8, 0,
                        level.random.nextInt(17) - 8);
                spot = level.getHeightmapPos(                           // land on the surface
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        spot);
                if (!canStand(level, spot)) {
                    continue;                                           // blocked space - try the next offset
                }

                VdfUnitEntity unit = SemcsRegistries.VDF_UNIT.get().create(level);
                if (unit == null) {
                    return;                                             // type missing - bail quietly
                }
                unit.moveTo(spot.getX() + 0.5, spot.getY(),
                        spot.getZ() + 0.5, level.random.nextFloat() * 360.0F, 0.0F);
                unit.setVillageCenter(bell);                            // anchor before goals run
                unit.finalizeSpawn(level, difficulty, MobSpawnType.NATURAL, null, null);
                level.addFreshEntity(unit);
                break;                                                  // this member placed - next member
            }
        }
    }

    // Feet + head must be collision-free - the MixinSpawnHelper canopy rule.
    private static boolean canStand(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty();
    }

    // Village Bell Call-to-Arms: ringing a bell rallies all VDF militia within
    // 64 blocks to converge on the bell, grants a short speed + resistance
    // adrenaline surge, and locks idle militia onto any hostile in the village.
    @SubscribeEvent
    public static void onBellRung(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()
                || !(event.getLevel() instanceof ServerLevel level)
                || !event.getLevel().getBlockState(event.getPos()).is(net.minecraft.world.level.block.Blocks.BELL)) {
            return;
        }
        BlockPos bellPos = event.getPos();
        AABB rallyBox = new AABB(bellPos).inflate(64.0D);
        java.util.List<VdfUnitEntity> militia = level.getEntitiesOfClass(
                VdfUnitEntity.class, rallyBox, VdfUnitEntity::isAlive);
        if (militia.isEmpty()) {
            return;
        }
        java.util.List<net.minecraft.world.entity.LivingEntity> threats = level.getEntitiesOfClass(
                net.minecraft.world.entity.LivingEntity.class, rallyBox,
                e -> e.isAlive() && (e instanceof net.minecraft.world.entity.raid.Raider
                        || (e instanceof net.minecraft.world.entity.monster.Monster
                            && !(e instanceof net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit))
                        || net.nekoyuni.SimpleEnemyMod.entity.unit.util.UnitFactionTags.isRuUnit(e)
                        || net.nekoyuni.SimpleEnemyMod.entity.unit.util.UnitFactionTags.isUsUnit(e)));

        for (VdfUnitEntity unit : militia) {
            unit.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED, 200, 0, false, false));
            unit.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 200, 0, false, false));
            if (unit.getTarget() == null && !threats.isEmpty()) {
                unit.setTarget(threats.get(0));
            } else if (unit.getTarget() == null) {
                unit.getNavigation().moveTo(
                        bellPos.getX() + 0.5, bellPos.getY(), bellPos.getZ() + 0.5, 1.2D);
            }
        }
    }
}
