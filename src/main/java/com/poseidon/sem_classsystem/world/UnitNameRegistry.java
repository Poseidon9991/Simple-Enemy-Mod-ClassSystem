// World package - per-save unique name registry for recruited units
package com.poseidon.sem_classsystem.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

// Hands out unique callsign names to recruited PMC/BHQ units. Lives in the
// overworld's SavedData ("semcs_unit_names") so the claimed-name set
// survives relogs. When a named unit dies the name is released back into
// the pool for the next recruit.
public class UnitNameRegistry extends SavedData {

    private static final String DATA_NAME = "semcs_unit_names";
    private static final String TAG_USED = "used";

    private final Set<String> used = new HashSet<>();

    public static UnitNameRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                UnitNameRegistry::load, UnitNameRegistry::new, DATA_NAME);
    }

    private static UnitNameRegistry load(CompoundTag tag) {
        UnitNameRegistry registry = new UnitNameRegistry();
        for (Tag t : tag.getList(TAG_USED, Tag.TAG_STRING)) {
            registry.used.add(t.getAsString());
        }
        return registry;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (String name : this.used) {
            list.add(StringTag.valueOf(name));
        }
        tag.put(TAG_USED, list);
        return tag;
    }

    // Claims a unique name for a unit. If every pool name is taken the unit
    // gets a numbered callsign ("Jony-2") so uniqueness never breaks.
    public String claim(RandomSource random) {
        for (int attempt = 0; attempt < 24; attempt++) {
            String name = NAME_POOL[random.nextInt(NAME_POOL.length)];
            if (this.used.add(name)) {
                setDirty();
                return name;
            }
        }
        String base = NAME_POOL[random.nextInt(NAME_POOL.length)];
        for (int suffix = 2; ; suffix++) {
            String candidate = base + "-" + suffix;
            if (this.used.add(candidate)) {
                setDirty();
                return candidate;
            }
        }
    }

    // Frees a name so a future recruit can carry it again.
    public void release(@Nullable String name) {
        if (name != null && this.used.remove(name)) {
            setDirty();
        }
    }

    // Convenience: assign a fresh unique name to a unit (nameplate + flag).
    public static void assignUniqueName(LivingEntity unit, ServerLevel level) {
        if (unit.getCustomName() != null || unit.getPersistentData().getBoolean("semcs_named")) {
            return;
        }
        String name = get(level).claim(unit.getRandom());
        unit.setCustomName(net.minecraft.network.chat.Component.literal(name));
        unit.setCustomNameVisible(true);
        unit.getPersistentData().putBoolean("semcs_named", true);
    }

    // Soldier callsign pool - mix of NATO, Slavic, Khmer and contractor handles.
    private static final String[] NAME_POOL = {
            "Jony", "Marek", "Dmitri", "Sokha", "Vic", "Ramon", "Hans", "Petr",
            "Kola", "Andrei", "Boris", "Sarun", "Dara", "Vichea", "Rith", "Channa",
            "Maks", "Igor", "Sergei", "Oleg", "Vadim", "Yuri", "Anton", "Pavel",
            "Jack", "Deacon", "Reyes", "Cole", "Marcus", "Danny", "Sully", "Hawk",
            "Ghost", "Roach", "Price", "Soap", "Wolf", "Bear", "Ranger", "Doc",
            "Duke", "Tex", "Rhino", "Bull", "Fox", "Viper", "Raven", "Storm",
            "Boran", "Kun", "Vanna", "Pich", "Samnang", "Vireak", "Chea", "Narin",
            "Sokun", "Rathana", "Kosal", "Malis", "Tola", "Sopheak", "Mony", "Ravy",
            "Nikolai", "Grigori", "Leonid", "Fedor", "Artyom", "Vlad", "Sasha", "Dima",
            "Kovac", "Emil", "Stefan", "Tomas", "Luka", "Milan", "Janek", "Felix",
            "Oscar", "Rudolf", "Bruno", "Karl", "Otto", "Werner", "Gunter", "Dieter",
            "Miguel", "Carlos", "Javier", "Diego", "Pablo", "Enzo", "Mateo", "Rio",
            "Kenji", "Sato", "Ryu", "Akira", "Chen", "Wei", "Long", "Jin"
    };
}
