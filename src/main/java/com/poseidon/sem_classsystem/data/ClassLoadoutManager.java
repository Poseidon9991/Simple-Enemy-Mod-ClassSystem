// Data package - datapack-driven gun tables for the class system
package com.poseidon.sem_classsystem.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.faction.ClassLoadouts;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.nekoyuni.SimpleEnemyMod.data.UnitLoadout;
import com.tacz.guns.api.TimelessAPI;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The class-system twin of SEM's UnitLoadoutManager: a datapack reload
// listener that reads data/<namespace>/class_loadouts/<faction>.json.
//
// Our own jar ships defaults under data/sem_classsystem/class_loadouts/, and
// any datapack or gunpack can add or replace them the same way. Files merge
// across namespaces; a pack reusing our namespace replaces our file outright.
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ClassLoadoutManager extends SimpleJsonResourceReloadListener {

    // Gson instance handed to the vanilla listener machinery
    private static final Gson GSON = new GsonBuilder().create();
    // Datapack folder we watch: data/*/class_loadouts/*.json
    private static final String FOLDER_PATH = "class_loadouts";

    // factionId -> weapon class -> loadout pool (empty until a world loads)
    private static Map<String, Map<WeaponClass, List<UnitLoadout>>> LOADOUTS = Collections.emptyMap();
    // factionId -> spawn-grant medical item ids ("heal_items" array).
    private static Map<String, List<ResourceLocation>> HEAL_ITEMS = Collections.emptyMap();
    // gunId -> owning class, flattened from LOADOUTS at load time.
    // inferClass runs inside the per-tick canUse evaluation of every armed
    // unit - keep it O(1) instead of re-scanning all tables every call.
    private static Map<ResourceLocation, WeaponClass> GUN_TO_CLASS = Collections.emptyMap();
    // Gun ids already warned about - pick() logs each missing gun once.
    private static final java.util.Set<ResourceLocation> WARNED_GUNS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public ClassLoadoutManager() {
        super(GSON, FOLDER_PATH);
    }

    // Forge tells us whenever datapack reload listeners are being built -
    // we add ours so it runs on world load and /reload.
    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ClassLoadoutManager());
    }

    // Vanilla hands us every json under class_loadouts/ as id -> JsonElement.
    // fileId path looks like "class_loadouts/ru_units" - last part is the faction.
    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        Map<String, Map<WeaponClass, List<UnitLoadout>>> table = new HashMap<>();
        Map<String, List<ResourceLocation>> healTable = new HashMap<>();

        for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet()) {
            String path = file.getKey().getPath();                    // "class_loadouts/ru_units"
            String factionId = path.substring(path.lastIndexOf('/') + 1);

            try {
                JsonObject root = file.getValue().getAsJsonObject();

                // Optional "heal_items": ["modid:item", ...] - spawn-grant
                // medical supplies for this faction's units.
                if (root.has("heal_items") && root.get("heal_items").isJsonArray()) {
                    List<ResourceLocation> items = new ArrayList<>();
                    for (JsonElement el : root.getAsJsonArray("heal_items")) {
                        items.add(ResourceLocation.parse(el.getAsString()));
                    }
                    healTable.put(factionId, items);
                }

                if (!root.has("classes") || !root.get("classes").isJsonObject()) {
                    SemClassSystem.LOGGER.warn("class_loadouts file {} is missing the 'classes' object - skipped",
                            file.getKey());
                    continue;
                }

                // classes -> { "smg": { "uzi": {...}, ... }, ... }
                for (Map.Entry<String, JsonElement> classEntry : root.getAsJsonObject("classes").entrySet()) {
                    WeaponClass cls = WeaponClass.byConfigKey(classEntry.getKey());
                    if (cls == null) {
                        SemClassSystem.LOGGER.warn("class_loadouts file {} has unknown class key '{}' - skipped",
                                file.getKey(), classEntry.getKey());
                        continue;
                    }

                    for (Map.Entry<String, JsonElement> gunEntry : classEntry.getValue()
                            .getAsJsonObject().entrySet()) {
                        UnitLoadout loadout = parseLoadout(gunEntry.getValue().getAsJsonObject());
                        if (loadout == null) {
                            SemClassSystem.LOGGER.warn("loadout '{}' in {} is missing gun_id / ammo_count / fire_mode - skipped",
                                    gunEntry.getKey(), file.getKey());
                            continue;
                        }
                        // Gun validity is checked at pick() time via TaCZ's
                        // gun index - NOT here. TaCZ guns are NBT ids on one
                        // shared item, so they never appear in Forge's item
                        // registry, and their index may not be populated yet
                        // during this same datapack reload pass.
                        table.computeIfAbsent(factionId, f -> new EnumMap<>(WeaponClass.class))
                             .computeIfAbsent(cls, c -> new ArrayList<>())
                             .add(loadout);
                    }
                }
            } catch (Exception e) {
                SemClassSystem.LOGGER.warn("class_loadouts file {} failed to parse: {}",
                        file.getKey(), e.getMessage());
            }
        }

        LOADOUTS = table;
        HEAL_ITEMS = healTable;
        Map<ResourceLocation, WeaponClass> gunIndex = new HashMap<>();
        for (Map<WeaponClass, List<UnitLoadout>> byClass : table.values()) {
            for (Map.Entry<WeaponClass, List<UnitLoadout>> e : byClass.entrySet()) {
                for (UnitLoadout l : e.getValue()) {
                    gunIndex.putIfAbsent(l.gunId, e.getKey());
                }
            }
        }
        GUN_TO_CLASS = gunIndex;
        // Tables changed - cached inferClass answers are now stale.
        ClassLoadouts.invalidateInferenceCache();
        int total = table.values().stream().mapToInt(m -> m.values().stream().mapToInt(List::size).sum()).sum();
        SemClassSystem.LOGGER.info("Loaded {} class loadouts for {} factions", total, table.size());
    }

    // One loadout object: gun_id + ammo_count + fire_mode required,
    // scope_id / muzzle_id / grip_id optional - same schema as SEM's own files.
    @Nullable
    private static UnitLoadout parseLoadout(JsonObject json) {
        if (!json.has("gun_id") || !json.has("ammo_count") || !json.has("fire_mode")) {
            return null;
        }
        return new UnitLoadout(
                ResourceLocation.parse(json.get("gun_id").getAsString()),
                json.get("ammo_count").getAsInt(),
                json.get("fire_mode").getAsString(),
                optLoc(json, "scope_id"),
                optLoc(json, "muzzle_id"),
                optLoc(json, "grip_id"));
    }

    // Optional ResourceLocation field - null when the key is absent
    @Nullable
    private static ResourceLocation optLoc(JsonObject json, String key) {
        return json.has(key) ? ResourceLocation.parse(json.get(key).getAsString()) : null;
    }

    // Uniform pick from a faction+class pool; null when nothing valid.
    // Entries whose gun id TaCZ cannot resolve are skipped here instead of
    // at load time, so addon gunpacks join the pool only while installed and
    // a typo'd id can never arm a unit with air. Runs at spawn, after all
    // gunpack data is live.
    @Nullable
    public static UnitLoadout pick(String factionId, WeaponClass cls,
                                   net.minecraft.util.RandomSource random) {
        List<UnitLoadout> pool = LOADOUTS.getOrDefault(factionId, Map.of()).get(cls);
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        List<UnitLoadout> valid = null;
        for (UnitLoadout l : pool) {
            if (TimelessAPI.getCommonGunIndex(l.gunId).isPresent()) {
                (valid == null ? (valid = new ArrayList<>(pool.size())) : valid).add(l);
            } else if (WARNED_GUNS.add(l.gunId)) {
                SemClassSystem.LOGGER.warn("class loadout gun '{}' is not installed - skipping while spawning",
                        l.gunId);
            }
        }
        if (valid == null || valid.isEmpty()) {
            return null;
        }
        return valid.get(random.nextInt(valid.size()));
    }

    // Which class claims this gun id? Scans every faction's pools so a gun
    // listed anywhere counts as that class's weapon for the lock + inference.
    @Nullable
    public static WeaponClass classOf(ResourceLocation gunId) {
        return GUN_TO_CLASS.get(gunId);
    }

    // Random medical item id from a faction's heal_items pool; null when
    // the faction has no pool or every entry is a missing mod id.
    @Nullable
    public static ResourceLocation pickHealItem(String factionId,
                                                net.minecraft.util.RandomSource random) {
        List<ResourceLocation> pool = HEAL_ITEMS.get(factionId);
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        // Items CAN be validated here - unlike guns, ordinary items live in
        // Forge's registry which is fully populated at datapack time.
        List<ResourceLocation> valid = new ArrayList<>(pool.size());
        for (ResourceLocation id : pool) {
            if (net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(id)) {
                valid.add(id);
            } else if (WARNED_GUNS.add(id)) {
                SemClassSystem.LOGGER.warn("heal item '{}' is not installed - skipping while spawning", id);
            }
        }
        if (valid.isEmpty()) {
            return null;
        }
        return valid.get(random.nextInt(valid.size()));
    }
}
