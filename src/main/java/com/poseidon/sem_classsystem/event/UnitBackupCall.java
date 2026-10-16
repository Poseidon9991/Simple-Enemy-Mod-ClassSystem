// Event package - a wounded unit calls nearby allies to its fight
package com.poseidon.sem_classsystem.event;

import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.faction.UnitClass;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

// "Call for backup": a unit knocked below 25% HP alerts same-side allies in
// earshot (24 blocks). Idle allies pick up the attacker as their target; the
// defending unit's owner gets an actionbar ping with the callsign so the
// wound is actually visible. A 100-tick cooldown keeps a burst of hits from
// re-shouting every tick.
//
// Friendly fire does not cascade: if the attacker is itself a same-side
// unit, nobody gets a target pointed at a squadmate.
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID)
public final class UnitBackupCall {

    private static final double HELP_RADIUS = 24.0D;
    private static final float HP_THRESHOLD = 0.25F;
    private static final int COOLDOWN_TICKS = 100;
    private static final String TAG_CALL = "semcs_backup_call";

    private UnitBackupCall() {
    }

    @SubscribeEvent
    public static void onUnitDamaged(LivingDamageEvent event) {
        if (!(event.getEntity() instanceof AbstractUnit unit)
                || unit.level().isClientSide) {
            return;
        }
        float hp = unit.getHealth();
        if (hp <= 0 || hp > unit.getMaxHealth() * HP_THRESHOLD) {
            return;
        }
        long now = unit.level().getGameTime();
        CompoundTag data = unit.getPersistentData();
        if (now - data.getLong(TAG_CALL) < COOLDOWN_TICKS) {
            return;
        }
        data.putLong(TAG_CALL, now);

        LivingEntity attacker = event.getSource().getEntity() instanceof LivingEntity l
                ? l : null;
        UnitClass faction = UnitClass.of(unit);
        UUID owner = unit instanceof PmcUnitEntity pmc ? pmc.getOwnerUUID() : null;

        // Same-side test: owned units match by owner, field units by faction.
        java.util.function.Predicate<AbstractUnit> sameSide = ally ->
                owner != null
                        ? ally instanceof PmcUnitEntity p
                        && owner.equals(p.getOwnerUUID())
                        : UnitClass.of(ally) == faction;

        int responding = 0;
        if (attacker != null
                && !(attacker instanceof AbstractUnit atk && sameSide.test(atk))) {
            for (AbstractUnit ally : unit.level().getEntitiesOfClass(
                    AbstractUnit.class,
                    unit.getBoundingBox().inflate(HELP_RADIUS),
                    e -> e.isAlive() && e != unit && e != attacker
                            && e.getTarget() == null && sameSide.test(e))) {
                ally.setTarget(attacker);
                ally.setLastHurtByMob(attacker);
                responding++;
            }
        }

        if (owner != null) {
            MinecraftServer server = unit.getServer();
            ServerPlayer player = server == null ? null
                    : server.getPlayerList().getPlayer(owner);
            if (player != null) {
                Component name = unit.getCustomName() != null
                        ? unit.getCustomName()
                        : unit.getName();
                player.displayClientMessage(Component.literal("§c" +
                        name.getString() + " is pinned down!"
                        + (responding > 0
                        ? " §e" + responding + " allies responding." : ""))
                        .withStyle(ChatFormatting.RED), true);
            }
        }
    }
}
