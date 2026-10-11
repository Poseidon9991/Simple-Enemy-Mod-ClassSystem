// Faction package - helpers that work with the class field our mixin
// injects onto every AbstractUnit
package com.poseidon.sem_classsystem.faction;

import com.poseidon.sem_classsystem.bridge.IClassifiedUnit;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
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

    // Only SEM's own three concrete unit types take part in the class system.
    // Addon subclasses (e.g. sewv's medics/engineers/commanders) keep their
    // own weapons and fire rules - assigning them a random class could
    // fire-gate their custom guns, so they stay classless on purpose.
    public static boolean isClassifiable(Entity entity) {
        Class<?> c = entity.getClass();
        return c == net.nekoyuni.SimpleEnemyMod.entity.unit.RUunitEntity.class
                || c == net.nekoyuni.SimpleEnemyMod.entity.unit.USunitEntity.class
                || c == net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity.class
                || c == com.poseidon.sem_classsystem.entity.BhqUnitEntity.class;
    }

    // Per-tick cache for getEffective: the held-gun scan (item stack -> IGun
    // -> gun id -> loadout table lookup) is cheap but not free, and combat
    // mixins call this several times per tick per fighting unit (aim, both
    // spread calls, shoot, suppression). The hand-slot stack instance is
    // stable within a tick, so (same stack, same game time) => reuse.
    private record EffCache(ItemStack held, WeaponClass cls, long gameTime) {}
    private static final java.util.WeakHashMap<Entity, EffCache> EFFECTIVE_CACHE =
            new java.util.WeakHashMap<>();

    // The class that actually drives combat tuning RIGHT NOW: if the unit
    // is holding a gun that belongs to a known class, that gun's class wins
    // (this is how subclasses work - hand any soldier a pistol and it fights
    // like a Sidearm while keeping its real class). Unmapped guns fall back
    // to the assigned class; classless units stay classless either way.
    @Nullable
    public static WeaponClass getEffective(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return get(entity);
        }
        ItemStack held = living.getMainHandItem();
        long now = entity.level().getGameTime();
        EffCache cached = EFFECTIVE_CACHE.get(entity);
        if (cached != null && cached.held() == held && cached.gameTime() == now) {
            return cached.cls();
        }
        WeaponClass cls = computeEffective(entity, living, held);
        EFFECTIVE_CACHE.put(entity, new EffCache(held, cls, now));
        return cls;
    }

    @Nullable
    private static WeaponClass computeEffective(Entity entity, LivingEntity living,
                                                ItemStack held) {
        WeaponClass cls = get(entity);
        if (cls == null) {
            return null;
        }
        IGun iGun = IGun.getIGunOrNull(held);
        if (iGun != null) {
            WeaponClass gunClass = ClassLoadouts.inferClass(iGun.getGunId(held));
            // JACK is the exception to "gun class wins": it keeps its own
            // middling tuning on every weapon - that IS its identity.
            // Subclass guns (pistols) still overlay, same as everyone.
            if (gunClass != null && (cls != WeaponClass.JACK || gunClass.isSubclass())) {
                return gunClass;
            }
        }
        return cls;
    }

    // Vertical "FOV" tolerance for range checks: SEM gates engage/hold on
    // raw 3D distance, so a target standing a few blocks up a roof or hill
    // reads as much farther away and the unit never engages. Give 12 free
    // blocks of elevation difference - only excess height still counts.
    // Shared by the fire-goal and approach-goal mixins so both agree.
    private static final double VERTICAL_TOLERANCE = 12.0;

    public static double elevationTolerantDistanceSqr(Entity a, Entity b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        double dy = Math.abs(a.getY() - b.getY()) - VERTICAL_TOLERANCE;
        return dx * dx + dz * dz + (dy > 0.0 ? dy * dy : 0.0);
    }

    // Get the unit's class, rolling and storing a random one if unset.
    // Uniform pick over the assignable (non-subclass) classes.
    @Nullable
    public static WeaponClass getOrAssign(Entity entity, RandomSource random) {
        if (!(entity instanceof IClassifiedUnit classified) || !isClassifiable(entity)) {
            return null;
        }
        WeaponClass cls = classified.semcs$getWeaponClass();
        if (cls == null) {
            WeaponClass[] all = WeaponClass.mainClasses();
            cls = all[random.nextInt(all.length)];
            classified.semcs$setWeaponClass(cls);
        }
        return cls;
    }

    // Restore the class saved in NBT (world load). Returns false if absent.
    public static boolean restoreFromTag(Entity entity, net.minecraft.nbt.CompoundTag tag) {
        if (!(entity instanceof IClassifiedUnit classified) || !isClassifiable(entity)
                || !tag.contains(NBT_KEY)) {
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

    // No floating nameplate at all - the class lives only in the inventory
    // GUI title ("PMC Unit Equipment - Camper"). Strips nameplates WE
    // generated ("RU Unit", "RU Unit - Breacher") from existing units too,
    // while leaving player-given nametag names alone.
    public static void applyDisplayName(AbstractUnit unit) {
        WeaponClass cls = get(unit);
        UnitClass faction = UnitClass.of(unit);
        if (cls == null || faction == null) {
            return;   // no class -> nothing of ours to strip
        }
        Component name = unit.getCustomName();
        if (name == null) {
            return;
        }
        String label = faction.name().split("_")[0] + " Unit";  // "RU Unit", "BHQ Unit"
        String text = name.getString();
        if (text.equals(label) || text.startsWith(label + " - ")) {
            unit.setCustomName(null);
            unit.setCustomNameVisible(false);
        }
    }

    // ---- jack-of-all-trades stance ------------------------------------------
    // Jack fights at whatever range suits the gun in its hands instead of
    // one fixed band - a shotgun Jack closes in, a marksman Jack stands off.
    // The mixins call these when the effective class is JACK.

    // TaCZ weapon type of the held gun ("rifle", "rpg", ...), null for
    // non-guns / unresolvable ids.
    @Nullable
    public static String heldGunType(LivingEntity living) {
        ItemStack held = living.getMainHandItem();
        IGun iGun = IGun.getIGunOrNull(held);
        if (iGun == null) {
            return null;
        }
        ResourceLocation gunId = iGun.getGunId(held);
        return gunId == null ? null
                : TimelessAPI.getCommonGunIndex(gunId)
                        .map(com.tacz.guns.resource.index.CommonGunIndex::getType)
                        .orElse(null);
    }

    // Preferred fighting distance per held weapon type - the Rifleman value
    // is the default for unmapped/exotic types.
    public static double jackEngageDistance(@Nullable String gunType) {
        if (gunType == null) return 45.0;
        return switch (gunType) {
            case "shotgun" -> 14.0;
            case "smg" -> 25.0;
            case "sniper" -> 90.0;
            case "mg" -> 50.0;
            case "rpg" -> 42.0;
            case "pistol" -> 25.0;
            default -> 45.0;    // rifle & friends
        };
    }

    // Max-fire-distance multiplier per held weapon type (scales SEM's ~90).
    public static double jackShootDistance(@Nullable String gunType) {
        if (gunType == null) return 0.8;
        return switch (gunType) {
            case "shotgun" -> 0.35;
            case "smg" -> 0.55;
            case "sniper" -> 1.15;
            case "mg" -> 1.0;
            case "rpg" -> 0.8;
            case "pistol" -> 0.5;
            default -> 0.8;
        };
    }

    // Approach speed per held weapon type - rush in with CQB guns, plant
    // with crew-served ones.
    public static double jackApproachSpeed(@Nullable String gunType) {
        if (gunType == null) return 1.0;
        return switch (gunType) {
            case "shotgun" -> 1.15;
            case "mg", "rpg" -> 0.9;
            default -> 1.0;
        };
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
    // Subclasses are skipped - you pick a class here, sidearms come from
    // the gun you hand the unit afterwards.
    // Returns the new preference (null = random) for the feedback message.
    @Nullable
    public static WeaponClass cycleRecruitPreference(net.minecraft.world.entity.player.Player player) {
        WeaponClass current = getRecruitPreference(player);
        WeaponClass[] all = WeaponClass.mainClasses();
        WeaponClass next;
        if (current == null) {
            next = all[0];
        } else {
            int idx = -1;
            for (int i = 0; i < all.length; i++) {
                if (all[i] == current) {
                    idx = i;
                    break;
                }
            }
            next = idx >= 0 && idx + 1 < all.length ? all[idx + 1] : null;
        }
        setRecruitPreference(player, next);
        return next;
    }

    // Pretty label for the actionbar / command feedback.
    public static String recruitPreferenceLabel(net.minecraft.world.entity.player.Player player) {
        WeaponClass cls = getRecruitPreference(player);
        return cls == null ? "Random" : ClassSystemConfig.classNickname(cls);
    }
}
