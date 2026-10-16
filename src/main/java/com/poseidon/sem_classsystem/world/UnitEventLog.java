// World package - per-player squad event log for the offline report
package com.poseidon.sem_classsystem.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Records squad events worth reporting when a player logs back in -
// starvation deaths, owned-unit losses, night raids. Entries accumulate
// whether or not the player is online; PlayerLoggedInEvent drains them
// into a chat report and clears the buffer. Capped so a long absence
// can't grow the file without bound.
public class UnitEventLog extends SavedData {

    private static final String DATA_NAME = "semcs_unit_events";
    private static final int MAX_LINES_PER_PLAYER = 40;

    private final Map<UUID, List<String>> pending = new HashMap<>();
    // Persistent KIA board - never drained by the login report; the tablet's
    // intel app reads the tail of it. Stored alongside pending in the same
    // SavedData file under a "casualty:" prefixed key.
    private static final int MAX_CASUALTIES_PER_PLAYER = 20;
    private static final String CAS_PREFIX = "casualty:";

    public static UnitEventLog get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                UnitEventLog::load, UnitEventLog::new, DATA_NAME);
    }

    private static UnitEventLog load(CompoundTag tag) {
        UnitEventLog log = new UnitEventLog();
        for (String key : tag.getAllKeys()) {
            List<String> lines = new ArrayList<>();
            for (Tag t : tag.getList(key, Tag.TAG_STRING)) {
                lines.add(t.getAsString());
            }
            if (key.startsWith(CAS_PREFIX)) {
                log.casualties.put(UUID.fromString(key.substring(CAS_PREFIX.length())), lines);
            } else {
                log.pending.put(UUID.fromString(key), lines);
            }
        }
        return log;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        for (Map.Entry<UUID, List<String>> e : this.pending.entrySet()) {
            ListTag list = new ListTag();
            for (String line : e.getValue()) {
                list.add(StringTag.valueOf(line));
            }
            tag.put(e.getKey().toString(), list);
        }
        for (Map.Entry<UUID, List<String>> e : this.casualties.entrySet()) {
            ListTag list = new ListTag();
            for (String line : e.getValue()) {
                list.add(StringTag.valueOf(line));
            }
            tag.put(CAS_PREFIX + e.getKey().toString(), list);
        }
        return tag;
    }

    // Append a line to a player's pending report.
    public void add(UUID player, String line) {
        List<String> lines = this.pending.computeIfAbsent(player, k -> new ArrayList<>());
        if (lines.size() >= MAX_LINES_PER_PLAYER) {
            // Drop the oldest line - the newest events matter most.
            lines.remove(0);
        }
        lines.add(line);
        setDirty();
    }

    // Pull (and clear) everything waiting for this player.
    public List<String> drain(UUID player) {
        List<String> lines = this.pending.remove(player);
        if (lines != null) {
            setDirty();
        }
        return lines != null ? lines : List.of();
    }

    private final Map<UUID, List<String>> casualties = new HashMap<>();

    // Record a permanent loss on the casualty board (KIA / bled out).
    // Survives the login report drain - it is history, not a notification.
    public void addCasualty(UUID player, String line) {
        List<String> lines = this.casualties.computeIfAbsent(player, k -> new ArrayList<>());
        if (lines.size() >= MAX_CASUALTIES_PER_PLAYER) {
            lines.remove(0);
        }
        lines.add(line);
        setDirty();
    }

    // Newest-last list of casualty lines for the tablet intel board.
    public List<String> casualties(UUID player) {
        return this.casualties.getOrDefault(player, List.of());
    }
}
