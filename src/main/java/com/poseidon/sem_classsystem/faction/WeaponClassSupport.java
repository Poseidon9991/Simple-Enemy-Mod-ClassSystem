// Faction package - helpers that work with the class field our mixin
// injects onto every AbstractUnit
package com.poseidon.sem_classsystem.faction;

import com.poseidon.sem_classsystem.bridge.IClassifiedUnit;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

import javax.annotation.Nullable;

public final class WeaponClassSupport {

    // Utility class - never instantiated
    private WeaponClassSupport() {
    }

    // NBT key used to persist the class across world save/load
    public static final String NBT_KEY = "semcs_weapon_class";

    // Read the class off any entity. null if it is not a patched SEM unit
    // or no class was assigned yet.
    @Nullable
    public static WeaponClass get(Entity entity) {
        if (entity instanceof IClassifiedUnit classified) {
            return classified.semcs$getWeaponClass();
        }
        return null;
    }

    // Get the unit's class, rolling and storing a random one if unset.
    // Uniform pick: each of the 5 classes is equally likely.
    @Nullable
    public static WeaponClass getOrAssign(Entity entity, RandomSource random) {
        if (!(entity instanceof IClassifiedUnit classified)) {
            return null;
        }
        WeaponClass cls = classified.semcs$getWeaponClass();
        if (cls == null) {
            WeaponClass[] all = WeaponClass.values();
            cls = all[random.nextInt(all.length)];
            classified.semcs$setWeaponClass(cls);
        }
        return cls;
    }

    // Restore the class saved in NBT (world load). Returns false if absent.
    public static boolean restoreFromTag(Entity entity, net.minecraft.nbt.CompoundTag tag) {
        if (!(entity instanceof IClassifiedUnit classified) || !tag.contains(NBT_KEY)) {
            return false;
        }
        classified.semcs$setWeaponClass(WeaponClass.byName(tag.getString(NBT_KEY)));
        return true;
    }

    // Write the class into NBT (world save). Null class -> nothing written.
    public static void saveToTag(Entity entity, net.minecraft.nbt.CompoundTag tag) {
        WeaponClass cls = get(entity);
        if (cls != null) {
            tag.putString(NBT_KEY, cls.name());
        }
    }

    // Builds and applies the floating name: "<FACTION> Unit - <Nickname>".
    // Nickname comes from config so it can be renamed per world.
    public static void applyDisplayName(AbstractUnit unit) {
        WeaponClass cls = get(unit);
        UnitClass faction = UnitClass.of(unit);
        if (cls == null || faction == null) {
            return;   // no class -> keep vanilla name
        }
        String label = faction.name().split("_")[0];       // "RU", "US", "PMC"
        String nickname = ClassSystemConfig.classNickname(cls);
        unit.setCustomName(Component.literal(label + " Unit - " + nickname));
        unit.setCustomNameVisible(true);                   // nameplate always visible
    }

    // ---- recruit preference ------------------------------------------------
    // The class a player wants the next Recruit Table soldier to be.
    // Stored in the player's persistent NBT so it survives death + relog.
    public static final String RECRUIT_KEY = "semcs_recruit_class";

    // Read the player's chosen recruit class. null = "random" (default).
    @Nullable
    public static WeaponClass getRecruitPreference(net.minecraft.world.entity.player.Player player) {
        String key = player.getPersistentData().getString(RECRUIT_KEY);
        return key.isEmpty() ? null : WeaponClass.byConfigKey(key);
    }

    // Write the preference; null clears it back to "random".
    public static void setRecruitPreference(net.minecraft.world.entity.player.Player player,
                                            @Nullable WeaponClass cls) {
        player.getPersistentData().putString(RECRUIT_KEY,
                cls == null ? "" : cls.configKey());
    }

    // Sneak-click on the recruit table: step to the next choice.
    // Order: random -> smg -> rifle -> shotgun -> sniper -> heavy_mg -> random.
    // Returns the new preference (null = random) for the feedback message.
    @Nullable
    public static WeaponClass cycleRecruitPreference(net.minecraft.world.entity.player.Player player) {
        WeaponClass current = getRecruitPreference(player);
        WeaponClass next = current == null
                ? WeaponClass.values()[0]
                : (current.ordinal() + 1 < WeaponClass.values().length)
                        ? WeaponClass.values()[current.ordinal() + 1]
                        : null;
        setRecruitPreference(player, next);
        return next;
    }

    // Pretty label for the actionbar / command feedback.
    public static String recruitPreferenceLabel(net.minecraft.world.entity.player.Player player) {
        WeaponClass cls = getRecruitPreference(player);
        return cls == null ? "Random" : ClassSystemConfig.classNickname(cls);
    }
}
