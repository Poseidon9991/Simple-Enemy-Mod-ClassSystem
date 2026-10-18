// Event package - militia gear only changes hands over a dead militia member
package com.poseidon.sem_classsystem.event;

import com.poseidon.sem_classsystem.SemClassSystem;                    // MODID for the subscriber annotation
import com.poseidon.sem_classsystem.config.ClassSystemConfig;          // [vdf] armorDropChance knob
import com.poseidon.sem_classsystem.entity.VdfUnitEntity;              // the militia that drops it
import com.poseidon.sem_classsystem.registry.SemcsRegistries;          // vdf_* armor items
import net.minecraft.world.entity.item.ItemEntity;                     // what we add to the drop list
import net.minecraft.world.entity.player.Player;                       // trophy gate - player kills only
import net.minecraft.world.item.ItemStack;                             // the dropped piece
import net.minecraftforge.event.entity.living.LivingDropsEvent;        // fires while a dying entity builds its drops
import net.minecraftforge.eventbus.api.SubscribeEvent;                 // handler annotation
import net.minecraftforge.fml.common.Mod;                              // subscriber annotation

// Armor drops are event-driven, not a loot table - the [vdf] armorDropChance
// config knob can't be read from datapack JSON. Four independent rolls, each
// at armorDropChance (default 3%), and only on player kills: the gear is a
// trophy for taking the militia down, not something zombies farm for you.
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class VdfDropEvents {

    private VdfDropEvents() {
    }

    @SubscribeEvent
    public static void onVdfDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof VdfUnitEntity militia)       // only our militia bleeds militia gear
                || !(event.getSource().getEntity() instanceof Player)) { // trophy rule - player kills only
            return;
        }
        double chance = ClassSystemConfig.vdfArmorDropChance();          // [vdf] armorDropChance, default 0.03
        if (chance <= 0.0) {
            return;                                                      // drops disabled
        }
        roll(militia, event, SemcsRegistries.VDF_HELMET.get(), chance); // each piece rolls alone
        roll(militia, event, SemcsRegistries.VDF_VEST.get(), chance);
        roll(militia, event, SemcsRegistries.VDF_PANTS.get(), chance);
        roll(militia, event, SemcsRegistries.VDF_BOOTS.get(), chance);
    }

    private static void roll(VdfUnitEntity militia, LivingDropsEvent event,
                             net.minecraft.world.item.Item piece, double chance) {
        if (militia.getRandom().nextFloat() < chance) {
            event.getDrops().add(new ItemEntity(militia.level(),
                    militia.getX(), militia.getY(), militia.getZ(),
                    new ItemStack(piece)));
        }
    }
}
