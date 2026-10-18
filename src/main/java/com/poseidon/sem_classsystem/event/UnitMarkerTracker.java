// Event package - streams map markers + chunk claims to each player
package com.poseidon.sem_classsystem.event;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.entity.ai.UnitChunkDutyGoal;
import com.poseidon.sem_classsystem.faction.UnitClass;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.poseidon.sem_classsystem.map.UnitMarker;
import com.poseidon.sem_classsystem.network.PacketSyncClaims;
import com.poseidon.sem_classsystem.network.PacketSyncUnitMarkers;
import com.poseidon.sem_classsystem.network.SemcsNetwork;
import com.poseidon.sem_classsystem.veterancy.UnitVeterancy;
import com.poseidon.sem_classsystem.world.PlayerChunkClaims;
import com.poseidon.sem_classsystem.world.UnitEventLog;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IAmmoBox;
import com.tacz.guns.api.item.IGun;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Every SYNC_TICKS the server hands each player a full snapshot of:
//   - every unit they OWN (any distance - your squad is always on the map)
//   - every other classifiable unit within SPOT_RANGE ("spotted" contacts)
// plus the claim table for the player's dimension. Full-replacement sync -
// see PacketSyncUnitMarkers for why.
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID)
public final class UnitMarkerTracker {

    private static final int SYNC_TICKS = 20;
    private static final double SPOT_RANGE = 160.0D;
    private static final double SPOT_RANGE_SQR = SPOT_RANGE * SPOT_RANGE;

    // Per-player last synced state hashes for claims and KIA intel so we only
    // send those packets when the data or dimension actually changes.
    private static final Map<UUID, Integer> LAST_CLAIMS_HASH = new java.util.HashMap<>();
    private static final Map<UUID, Integer> LAST_INTEL_HASH = new java.util.HashMap<>();

    private UnitMarkerTracker() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
                || event.getServer().getTickCount() % SYNC_TICKS != 0) {
            return;
        }
        PlayerChunkClaims claims = PlayerChunkClaims.get(event.getServer().overworld());

        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (!(player.level() instanceof ServerLevel level)) {
                continue;
            }
            List<UnitMarker> markers = collect(level, player);
            SemcsNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new PacketSyncUnitMarkers(markers));

            String dim = level.dimension().location().toString();
            Map<UUID, Set<Long>> dimClaims = claims.allIn(dim);
            int claimsHash = 31 * dim.hashCode() + dimClaims.hashCode();
            Integer prevClaimsHash = LAST_CLAIMS_HASH.put(player.getUUID(), claimsHash);
            if (prevClaimsHash == null || prevClaimsHash != claimsHash) {
                List<PacketSyncClaims.ClaimEntry> entries = new ArrayList<>();
                for (Map.Entry<UUID, Set<Long>> owner : dimClaims.entrySet()) {
                    for (Long pos : owner.getValue()) {
                        entries.add(new PacketSyncClaims.ClaimEntry(
                                owner.getKey(), ChunkPos.getX(pos), ChunkPos.getZ(pos)));
                    }
                }
                SemcsNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new PacketSyncClaims(dim, entries));
            }

            // Casualty board for the tablet INTEL app - newest 12 lines.
            List<String> kas = UnitEventLog.get(event.getServer().overworld())
                    .casualties(player.getUUID());
            if (!kas.isEmpty()) {
                List<String> tail = kas.subList(Math.max(0, kas.size() - 12), kas.size());
                int intelHash = tail.hashCode();
                Integer prevIntelHash = LAST_INTEL_HASH.put(player.getUUID(), intelHash);
                if (prevIntelHash == null || prevIntelHash != intelHash) {
                    SemcsNetwork.CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new com.poseidon.sem_classsystem.network.PacketSyncIntel(tail));
                }
            }
        }
    }

    private static List<UnitMarker> collect(ServerLevel level, ServerPlayer viewer) {
        List<UnitMarker> out = new ArrayList<>();
        // Current dimension only - your own units map at any distance, and
        // strangers (plus VDF village militia) must be "spotted" within SPOT_RANGE.
        for (Entity entity : level.getAllEntities()) {
            boolean isVdf = entity instanceof com.poseidon.sem_classsystem.entity.VdfUnitEntity;
            if (!(entity instanceof AbstractUnit unit)
                    || (!isVdf && !WeaponClassSupport.isClassifiable(unit))) {
                continue;
            }
            UnitMarker.Allegiance allegiance;
            if (isVdf) {
                allegiance = UnitMarker.Allegiance.FRIENDLY;
            } else if (unit instanceof PmcUnitEntity pmc && pmc.getOwnerUUID() != null) {
                allegiance = pmc.isOwnedBy(viewer)
                        ? UnitMarker.Allegiance.OWN : UnitMarker.Allegiance.FRIENDLY;
            } else {
                allegiance = UnitMarker.Allegiance.HOSTILE;
            }
            if (allegiance != UnitMarker.Allegiance.OWN
                    && unit.distanceToSqr(viewer) > SPOT_RANGE_SQR) {
                continue;
            }
            UnitClass faction = UnitClass.of(unit);
            WeaponClass cls = WeaponClassSupport.get(unit);
            Component custom = unit.getCustomName();
            // Duty state is PMC-only; the route waypoints themselves go
            // out solely to the owner - nobody else needs your patrol path.
            int duty = 0;
            int dutyCx = 0;
            int dutyCz = 0;
            long[] route = new long[0];
            if (unit instanceof PmcUnitEntity pmc) {
                duty = UnitChunkDutyGoal.dutyOf(pmc);
                if (duty != 0) {
                    dutyCx = UnitChunkDutyGoal.dutyChunkX(pmc);
                    dutyCz = UnitChunkDutyGoal.dutyChunkZ(pmc);
                }
                if (duty == UnitChunkDutyGoal.DUTY_ROUTE
                        && allegiance == UnitMarker.Allegiance.OWN) {
                    route = UnitChunkDutyGoal.dutyRoute(pmc);
                }
            }
            // Squad supply snapshot (tablet UI) - owner only, so nobody
            // can read another player's unit logistics off the wire.
            int ammoRounds = 0;
            boolean ammoInfinite = false, hasFood = false, hasMedical = false;
            int hunger = -1, energy = -1, morale = -1;
            boolean sleeping = unit.isSleeping();
            if (allegiance == UnitMarker.Allegiance.OWN) {
                int[] supplies = scanSupplies(unit);
                ammoRounds = supplies[0];
                ammoInfinite = supplies[1] != 0;
                hasFood = supplies[2] != 0;
                hasMedical = supplies[3] != 0;
                if (unit instanceof PmcUnitEntity pmc
                        && com.poseidon.sem_classsystem.needs.UnitNeeds.appliesTo(pmc)) {
                    hunger = com.poseidon.sem_classsystem.needs.UnitNeeds.hunger(pmc);
                    energy = com.poseidon.sem_classsystem.needs.UnitNeeds.energy(pmc);
                    morale = com.poseidon.sem_classsystem.needs.UnitNeeds.morale(pmc);
                }
            }
            out.add(new UnitMarker(
                    unit.getId(),
                    unit.getX(), unit.getY(), unit.getZ(),
                    level.dimension().location().toString(),
                    factionRgb(faction),
                    cls == null ? -1 : cls.ordinal(),
                    allegiance,
                    unit.getHealth() / Math.max(1.0F, unit.getMaxHealth()),
                    custom == null ? "" : custom.getString(),
                    unit instanceof PmcUnitEntity pmc ? pmc.getOrder().ordinal() : -1,
                    duty, dutyCx, dutyCz, route,
                    UnitVeterancy.xp(unit) > 0 ? UnitVeterancy.rankTitle(unit) : "",
                    unit.getHealth(), unit.getMaxHealth(),
                    faction == null ? -1 : faction.ordinal(),
                    ammoRounds, ammoInfinite, hasFood, hasMedical,
                    hunger, energy, morale, sleeping,
                    unit instanceof PmcUnitEntity pmc
                            && com.poseidon.sem_classsystem.entity.DownedState.isDowned(pmc),
                    unit instanceof PmcUnitEntity pmc
                            && allegiance == UnitMarker.Allegiance.OWN
                            ? com.poseidon.sem_classsystem.entity.Squads.squad(pmc) : ""));
        }
        return out;
    }

    // Medical keywords - same families UnitSelfHealGoal/UnitMedicHealGoal
    // treat as consumable healing, so the tablet's "MED" chip honestly
    // reflects what the unit would actually use.
    private static final String[] MED_KEYWORDS = {
            "bandage", "plaster", "gauze", "dressing", "salve", "ointment",
            "pill", "painkiller", "ibuprofen", "analgin", "splint", "tourniquet",
            "esmarch", "hemostat", "calok", "medkit", "med_kit", "first_aid",
            "firstaid", "ifak", "afak", "medical", "surg", "defib", "syringe",
            "stim", "adrenaline", "morphine", "blood_bag", "bloodbag",
            "grizzly", "salewa", "heal"
    };

    // {ammoRounds, ammoInfinite, hasFood, hasMedical} from the unit's own
    // ITEM_HANDLER (slot 0 gun included - its loaded mag counts as ammo).
    private static int[] scanSupplies(AbstractUnit unit) {
        int rounds = 0;
        int infinite = 0, food = 0, med = 0;
        var cap = unit.getCapability(ForgeCapabilities.ITEM_HANDLER);
        if (!cap.isPresent()) {
            return new int[]{0, 0, 0, 0};
        }
        IItemHandler inv = cap.orElse(null);
        for (int slot = 0; slot < inv.getSlots(); slot++) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.getItem() instanceof IAmmoBox box) {
                if (box.isCreative(stack) || box.isAllTypeCreative(stack)) {
                    infinite = 1;
                } else {
                    rounds += box.getAmmoCount(stack);
                }
            } else if (IAmmo.getIAmmoOrNull(stack) != null) {
                rounds += stack.getCount();
            } else {
                IGun gun = IGun.getIGunOrNull(stack);
                if (gun != null) {
                    rounds += gun.getCurrentAmmoCount(stack);
                }
            }
            if (food == 0 && stack.isEdible()) {
                food = 1;
            }
            if (med == 0 && isMedicalItem(stack)) {
                med = 1;
            }
        }
        return new int[]{rounds, infinite, food, med};
    }

    public static boolean isMedicalItem(ItemStack stack) {
        if (stack.getItem() instanceof PotionItem) {
            for (MobEffectInstance eff : PotionUtils.getMobEffects(stack)) {
                if (eff.getEffect() == MobEffects.HEAL
                        || eff.getEffect() == MobEffects.REGENERATION) {
                    return true;
                }
            }
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) {
            return false;
        }
        String id = key.toString().toLowerCase(Locale.ROOT);
        for (String kw : MED_KEYWORDS) {
            if (id.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private static int factionRgb(UnitClass faction) {
        if (faction == null) {
            return 0x8C96A6;
        }
        return switch (faction) {
            case RU_UNIT -> 0xC0403A;      // soviet red
            case US_UNIT -> 0x4A7BC0;      // NATO blue
            case PMC_UNIT -> 0x9C8B6B;     // contractor tan
            case BHQ_UNIT -> 0x4BC06A;     // royal green
            case VDF_UNIT -> 0x7A5C2E;     // militia earth
        };
    }
}
