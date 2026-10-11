// Commands package - the /semcs command tree for spawning + re-classing units
package com.poseidon.sem_classsystem.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.poseidon.sem_classsystem.SemClassSystem;
import com.poseidon.sem_classsystem.bridge.IClassifiedUnit;
import com.poseidon.sem_classsystem.faction.UnitClassAttributes;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import net.nekoyuni.SimpleEnemyMod.registry.ModEntities;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collection;

// /semcs - player + admin commands for the class system:
//   /semcs recruit_class [class|random]   set or show your Recruit Table pick
//   /semcs spawn <ru|us|pmc> <class>      spawn a unit of a chosen class (op)
//   /semcs class <targets> <class>        re-class existing units (op)
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SemClassCommands {

    private SemClassCommands() {
    }

    // Class keys offered in tab-completion
    // Assignable main classes only - pistol is a subclass (sidearm), it is
    // picked up by handing the unit a pistol gun, not by assignment.
    private static final String[] CLASS_KEYS = {"smg", "rifle", "shotgun", "sniper", "heavy_mg"};
    private static final String[] FACTION_KEYS = {"ru", "us", "pmc", "bhq"};

    private static final SuggestionProvider<CommandSourceStack> CLASS_SUGGEST =
            (ctx, builder) -> SharedSuggestionProvider.suggest(CLASS_KEYS, builder);
    private static final SuggestionProvider<CommandSourceStack> FACTION_SUGGEST =
            (ctx, builder) -> SharedSuggestionProvider.suggest(FACTION_KEYS, builder);
    private static final SuggestionProvider<CommandSourceStack> RECRUIT_SUGGEST =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    new String[]{"random", "smg", "rifle", "shotgun", "sniper", "heavy_mg"}, builder);

    // Forge fires this when the server builds its command tree
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();

        d.register(Commands.literal("semcs")

                // ---- /semcs recruit_class [class|random] ----
                .then(Commands.literal("recruit_class")
                        .executes(SemClassCommands::showRecruitClass)
                        .then(Commands.argument("class", StringArgumentType.word())
                                .suggests(RECRUIT_SUGGEST)
                                .executes(SemClassCommands::setRecruitClass)))

                // ---- /semcs spawn <ru|us|pmc> <class> (needs op) ----
                .then(Commands.literal("spawn")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("faction", StringArgumentType.word())
                                .suggests(FACTION_SUGGEST)
                                .then(Commands.argument("class", StringArgumentType.word())
                                        .suggests(CLASS_SUGGEST)
                                        .executes(SemClassCommands::spawnUnit))))

                // ---- /semcs class <targets> <class> (needs op) ----
                .then(Commands.literal("class")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("class", StringArgumentType.word())
                                        .suggests(CLASS_SUGGEST)
                                        .executes(SemClassCommands::reclassUnits)))));
    }

    // ---- recruit_class ------------------------------------------------

    // No argument: report the current preference
    private static int showRecruitClass(CommandContext<CommandSourceStack> ctx) {
        Player player = playerOrNull(ctx);
        if (player == null) return 0;
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Recruit class: " + WeaponClassSupport.recruitPreferenceLabel(player)), false);
        return 1;
    }

    // With argument: store the preference in the player's persistent NBT
    private static int setRecruitClass(CommandContext<CommandSourceStack> ctx) {
        Player player = playerOrNull(ctx);
        if (player == null) return 0;

        String arg = StringArgumentType.getString(ctx, "class");
        if (arg.equalsIgnoreCase("random")) {
            WeaponClassSupport.setRecruitPreference(player, null);
            ctx.getSource().sendSuccess(() -> Component.literal("Recruit class: Random"), true);
            return 1;
        }
        WeaponClass cls = WeaponClass.byConfigKey(arg);
        if (cls == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown class: " + arg));
            return 0;
        }
        WeaponClassSupport.setRecruitPreference(player, cls);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Recruit class: " + WeaponClassSupport.recruitPreferenceLabel(player)), true);
        return 1;
    }

    // ---- spawn --------------------------------------------------------

    // /semcs spawn <faction> <class> - create the unit at the sender's position
    private static int spawnUnit(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String factionArg = StringArgumentType.getString(ctx, "faction").toLowerCase();
        String classArg = StringArgumentType.getString(ctx, "class");

        WeaponClass cls = WeaponClass.byConfigKey(classArg);
        if (cls == null) {
            src.sendFailure(Component.literal("Unknown class: " + classArg));
            return 0;
        }

        // Resolve faction key -> SEM entity type
        net.minecraft.world.entity.EntityType<?> type = switch (factionArg) {
            case "ru" -> ModEntities.RUUNIT.get();
            case "us" -> ModEntities.USUNIT.get();
            case "pmc" -> ModEntities.PMCUNIT.get();
            case "bhq" -> SemcsRegistries.BHQ_UNIT.get();
            default -> null;
        };
        if (type == null) {
            src.sendFailure(Component.literal("Unknown faction: " + factionArg + " (use ru, us, pmc or bhq)"));
            return 0;
        }

        ServerLevel level = src.getLevel();
        Entity entity = type.create(level);
        if (!(entity instanceof AbstractUnit unit)) {
            src.sendFailure(Component.literal("Could not create unit"));
            return 0;
        }

        unit.moveTo(src.getPosition().x, src.getPosition().y, src.getPosition().z,
                src.getRotation().y, 0.0F);
        // Pre-set the class so the spawn logic never rolls a random one
        if (unit instanceof IClassifiedUnit classified) {
            classified.semcs$setWeaponClass(cls);
        }
        unit.finalizeSpawn(level, level.getCurrentDifficultyAt(unit.blockPosition()),
                MobSpawnType.COMMAND, null, null);
        level.addFreshEntity(unit);

        src.sendSuccess(() -> Component.literal(
                "Spawned " + unit.getName().getString()), true);
        return 1;
    }

    // ---- class --------------------------------------------------------

    // /semcs class <targets> <class> - re-class living units, re-equip + rename
    private static int reclassUnits(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        String classArg = StringArgumentType.getString(ctx, "class");

        WeaponClass cls = WeaponClass.byConfigKey(classArg);
        if (cls == null) {
            src.sendFailure(Component.literal("Unknown class: " + classArg));
            return 0;
        }

        Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "targets");
        int changed = 0;
        for (Entity entity : targets) {
            // isClassifiable: only SEM's own unit types - addon subclasses
            // (sewv medics/engineers/...) keep their own gear and rules
            if (entity instanceof AbstractUnit unit && unit instanceof IClassifiedUnit classified
                    && WeaponClassSupport.isClassifiable(entity)) {
                classified.semcs$setWeaponClass(cls);          // new class
                unit.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY); // drop old gun
                unit.equipRandomGun();                          // pick from new class pool
                UnitClassAttributes.apply(unit, false);         // refresh faction stats
                WeaponClassSupport.applyDisplayName(unit);      // refresh name tag
                changed++;
            }
        }
        if (changed == 0) {
            src.sendFailure(Component.literal("No SEM units in the selected entities"));
            return 0;
        }
        int finalChanged = changed;
        WeaponClass finalCls = cls;
        src.sendSuccess(() -> Component.literal(
                "Re-classed " + finalChanged + " unit(s) as " + finalCls.defaultNickname()), true);
        return changed;
    }

    // CommandSourceStack -> Player, or null (console/command blocks get a message)
    private static Player playerOrNull(CommandContext<CommandSourceStack> ctx) {
        Entity e = ctx.getSource().getEntity();
        if (e instanceof Player p) {
            return p;
        }
        ctx.getSource().sendFailure(Component.literal("This command must be run by a player"));
        return null;
    }
}
