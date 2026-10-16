// wthit (WAILA) integration: hovering a classed unit shows faction + class
// in the overlay - replaces the removed nameplate with on-demand info.
// Class data lives in server NBT, so a server IDataProvider syncs it and a
// client component provider renders it.
package com.poseidon.sem_classsystem.compat.wthit;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import com.poseidon.sem_classsystem.veterancy.UnitVeterancy;
import mcp.mobius.waila.api.IEntityAccessor;
import mcp.mobius.waila.api.IEntityComponentProvider;
import mcp.mobius.waila.api.IDataProvider;
import mcp.mobius.waila.api.IDataWriter;
import mcp.mobius.waila.api.IPluginConfig;
import mcp.mobius.waila.api.IRegistrar;
import mcp.mobius.waila.api.IServerAccessor;
import mcp.mobius.waila.api.ITooltip;
import mcp.mobius.waila.api.IWailaPlugin;
import mcp.mobius.waila.api.WailaPlugin;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;

// NOTE: only `id` is declared - wthit's ForgePluginLoader casts the
// annotation's `required` member to String[] but Forge scan data stores
// array members as ArrayList -> ClassCastException at startup. `required`
// is redundant anyway (wthit itself does the scanning) and `side`
// defaults to BOTH.
@WailaPlugin(id = "sem_classsystem:units")
public class SemcsWailaPlugin implements IWailaPlugin {

    private static final String TAG_CLASS = "semcs_class";
    private static final String TAG_FACTION = "semcs_faction";
    private static final String TAG_NAME = "semcs_name";
    private static final String TAG_XP = "semcs_xp";

    @Override
    public void register(IRegistrar registrar) {
        registrar.entityData(new UnitDataProvider(), AbstractUnit.class);
        registrar.body(new UnitComponentProvider(), AbstractUnit.class);
    }

    // Server side: stamps the unit's class + faction into the synced NBT blob.
    private static final class UnitDataProvider implements IDataProvider<AbstractUnit> {
        @Override
        public void appendData(IDataWriter data, IServerAccessor<AbstractUnit> accessor,
                               IPluginConfig config) {
            AbstractUnit unit = accessor.getTarget();
            WeaponClass cls = WeaponClassSupport.get(unit);
            if (cls == null) {
                return;
            }
            var faction = com.poseidon.sem_classsystem.faction.UnitClass.of(unit);
            CompoundTag raw = data.raw();
            raw.putString(TAG_CLASS, cls.name());
            if (faction != null) {
                raw.putString(TAG_FACTION, faction.name().split("_")[0]);   // "RU","PMC","BHQ"
            }
            // Recruit callsign + veterancy record for the overlay line
            if (unit.getCustomName() != null) {
                raw.putString(TAG_NAME, unit.getCustomName().getString());
            }
            raw.putInt(TAG_XP, UnitVeterancy.xp(unit));
        }
    }

    // Client side: renders "PMC Camper" - class name in its accent colour.
    private static final class UnitComponentProvider implements IEntityComponentProvider {
        @Override
        public void appendBody(ITooltip tooltip, IEntityAccessor accessor,
                               IPluginConfig config) {
            CompoundTag raw = accessor.getData().raw();
            String clsName = raw.getString(TAG_CLASS);
            if (clsName.isEmpty()) {
                return;
            }
            WeaponClass cls;
            try {
                cls = WeaponClass.valueOf(clsName);
            } catch (IllegalArgumentException e) {
                return;   // class renamed/removed between versions
            }
            String faction = raw.getString(TAG_FACTION);
            String name = raw.getString(TAG_NAME);
            net.minecraft.network.chat.MutableComponent line = Component.empty();
            if (!name.isEmpty()) {
                line.append(Component.literal(name + " ").withStyle(ChatFormatting.YELLOW));
            }
            line.append(Component.literal(faction.isEmpty() ? "" : faction + " ")
                            .withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(ClassSystemConfig.classNickname(cls))
                            .withStyle(style -> style.withColor(SemcsRegistries.classAccent(cls))));
            tooltip.addLine(line);
            int xp = raw.getInt(TAG_XP);
            if (xp > 0) {
                tooltip.addLine(Component.literal("Veterancy " + xp + " XP")
                        .withStyle(ChatFormatting.DARK_GREEN));
            }
        }
    }
}
