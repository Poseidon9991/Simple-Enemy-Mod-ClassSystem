package com.poseidon.sem_classsystem.block;

import com.poseidon.sem_classsystem.entity.TerroristUnitEntity;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.nekoyuni.SimpleEnemyMod.config.common.FactionsConfig;
import net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity;

import java.util.List;

// Tactical Comms / Radar Dish block entity:
// Periodically scans 64 blocks for hostile units/raiders to drive the block's
// Redstone alert state, and caches live hostile count for right-click queries.
public class CommsRadarBlockEntity extends BlockEntity {

    public static final double ALERT_RADIUS = 64.0D;
    public static final double INTEL_RADIUS = 256.0D;
    private static final int SCAN_INTERVAL = 20;

    private int nearbyHostiles = 0;

    public CommsRadarBlockEntity(BlockPos pos, BlockState state) {
        super(SemcsRegistries.COMMS_RADAR_BE.get(), pos, state);
    }

    public int nearbyHostiles() {
        return this.nearbyHostiles;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, CommsRadarBlockEntity radar) {
        if (level.isClientSide || (level.getGameTime() + pos.asLong()) % SCAN_INTERVAL != 0) {
            return;
        }
        AABB box = new AABB(pos).inflate(ALERT_RADIUS);
        List<Monster> hostiles = level.getEntitiesOfClass(Monster.class, box, e ->
                e.isAlive() && isHostileContact(e));
        radar.nearbyHostiles = hostiles.size();
        boolean alert = !hostiles.isEmpty();
        if (state.getValue(CommsRadarBlock.ALERT) != alert) {
            level.setBlock(pos, state.setValue(CommsRadarBlock.ALERT, alert), 3);
            level.updateNeighborsAt(pos, state.getBlock());
        }
    }

    public static boolean isHostileContact(net.minecraft.world.entity.Entity e) {
        if (e instanceof TerroristUnitEntity || e instanceof Raider) {
            return true;
        }
        if (e instanceof RUunitEntity) {
            return !Boolean.TRUE.equals(FactionsConfig.RU_UNITS_FRIENDLY.get());
        }
        if (e instanceof USunitEntity) {
            return !Boolean.TRUE.equals(FactionsConfig.US_UNITS_FRIENDLY.get());
        }
        return false;
    }
}
