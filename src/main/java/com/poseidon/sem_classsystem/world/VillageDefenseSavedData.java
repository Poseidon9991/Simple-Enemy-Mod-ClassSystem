// World package - which villages already received their VDF garrison
package com.poseidon.sem_classsystem.world;

import net.minecraft.core.BlockPos;                                    // packed bell positions
import net.minecraft.nbt.CompoundTag;                                  // save/load structure
import net.minecraft.nbt.ListTag;                                      // tag list of anchors
import net.minecraft.nbt.Tag;                                          // tag type constants
import net.minecraft.resources.ResourceKey;                            // dimension key
import net.minecraft.server.level.ServerLevel;                         // dataStorage owner
import net.minecraft.world.level.Level;                                // dimension key type
import net.minecraft.world.level.saveddata.SavedData;                  // persistence base

import java.util.HashMap;                                              // dim -> anchor map
import java.util.Map;                                                  // map types

// Which villages already got their militia - one record per bell anchor with
// the game time it was garrisoned. Lives on the overworld dataStorage (same
// convention as PlayerChunkClaims) so every dimension shares one file.
//
// The timestamp matters more than the flag: a burst of villagers joining
// the same tick must not spawn five garrisons, but a village whose militia
// was wiped should re-arm when a NEW villager arrives later - the living-
// VDF scan in VdfGarrisonEvents is what actually permits the re-garrison,
// this record just debounces the trigger.
public class VillageDefenseSavedData extends SavedData {

    private static final String DATA_NAME = "semcs_vdf_garrisons";      // file name inside data/
    private static final double SAME_VILLAGE_SQR = 48.0D * 48.0D;       // anchors this close count as one village

    // dimension -> bell position (long) -> garrisoned game time
    private final Map<String, Map<Long, Long>> anchors = new HashMap<>();

    public static VillageDefenseSavedData get(ServerLevel anyServerLevel) {
        ServerLevel overworld = anyServerLevel.getServer().overworld();  // always one shared file
        return overworld.getDataStorage().computeIfAbsent(
                VillageDefenseSavedData::load, VillageDefenseSavedData::new, DATA_NAME);
    }

    // True while an anchor near pos was marked within `graceTicks` - the
    // debounce window that stops a villager group spawn from multiplying
    // garrisons. Once the window passes the anchor stops blocking; the
    // living-VDF scan takes over as the real gate.
    public boolean recentlyGarrisoned(ResourceKey<Level> dimension, BlockPos pos,
                                      long gameTime, int graceTicks) {
        Map<Long, Long> dim = this.anchors.getOrDefault(dimension.location().toString(), Map.of());
        for (Map.Entry<Long, Long> anchor : dim.entrySet()) {
            if (anchor.getValue() + graceTicks < gameTime) {            // stale anchor - doesn't block anymore
                continue;
            }
            if (BlockPos.of(anchor.getKey()).distSqr(pos) <= SAME_VILLAGE_SQR) {
                return true;                                            // fresh garrison nearby
            }
        }
        return false;
    }

    public void markGarrisoned(ResourceKey<Level> dimension, BlockPos bell, long gameTime) {
        this.anchors.computeIfAbsent(dimension.location().toString(), d -> new HashMap<>())
                .put(bell.asLong(), gameTime);                          // remember when this bell got its militia
        setDirty();                                                     // schedule the save
    }

    private static VillageDefenseSavedData load(CompoundTag tag) {
        VillageDefenseSavedData data = new VillageDefenseSavedData();
        ListTag dims = tag.getList("dims", Tag.TAG_COMPOUND);
        for (Tag t : dims) {
            CompoundTag dimTag = (CompoundTag) t;
            Map<Long, Long> bells = new HashMap<>();
            for (Tag at : dimTag.getList("anchors", Tag.TAG_COMPOUND)) {
                CompoundTag anchorTag = (CompoundTag) at;
                bells.put(anchorTag.getLong("pos"), anchorTag.getLong("time"));
            }
            data.anchors.put(dimTag.getString("name"), bells);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag dims = new ListTag();
        for (Map.Entry<String, Map<Long, Long>> dimEntry : this.anchors.entrySet()) {
            CompoundTag dimTag = new CompoundTag();
            dimTag.putString("name", dimEntry.getKey());
            ListTag anchorList = new ListTag();
            for (Map.Entry<Long, Long> anchor : dimEntry.getValue().entrySet()) {
                CompoundTag anchorTag = new CompoundTag();
                anchorTag.putLong("pos", anchor.getKey());
                anchorTag.putLong("time", anchor.getValue());
                anchorList.add(anchorTag);
            }
            dimTag.put("anchors", anchorList);
            dims.add(dimTag);
        }
        tag.put("dims", dims);
        return tag;
    }
}
