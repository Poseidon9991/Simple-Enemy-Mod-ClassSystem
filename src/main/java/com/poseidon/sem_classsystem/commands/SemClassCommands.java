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
import com.poseidon.sem_classsystem.needs.UnitNeeds;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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

import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Stream;

// /semcs - player + admin commands for the class system:
//   /semcs recruit_class [class|random]   set or show your Recruit Table pick
//   /semcs spawn <ru|us|pmc> <class>      spawn a unit of a chosen class (op)
//   /semcs class <targets> <class>        re-class existing units (op)
//   /semcs shift <targets> <auto|day|night>  sleep roster for owned units
@Mod.EventBusSubscriber(modid = SemClassSystem.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SemClassCommands {

    private SemClassCommands() {
    }

    // Class keys offered in tab-completion, derived from mainClasses() so
    // new assignable classes join automatically. Pistol is a subclass
    // (sidearm): it is picked up by handing the unit a pistol gun, not by
    // assignment.
    private static final String[] CLASS_KEYS = Arrays.stream(WeaponClass.mainClasses())
            .map(WeaponClass::configKey).toArray(String[]::new);
    private static final String[] FACTION_KEYS = {"ru", "us", "pmc", "bhq"};

    private static final SuggestionProvider<CommandSourceStack> CLASS_SUGGEST =
            (ctx, builder) -> SharedSuggestionProvider.suggest(CLASS_KEYS, builder);
    private static final SuggestionProvider<CommandSourceStack> FACTION_SUGGEST =
            (ctx, builder) -> SharedSuggestionProvider.suggest(FACTION_KEYS, builder);
    private static final SuggestionProvider<CommandSourceStack> RECRUIT_SUGGEST =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    Stream.concat(Stream.of("random"), Arrays.stream(CLASS_KEYS))
                            .toArray(String[]::new), builder);
    private static final SuggestionProvider<CommandSourceStack> SHIFT_SUGGEST =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    new String[]{"auto", "day", "night"}, builder);

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
                                        .executes(SemClassCommands::reclassUnits))))

                // ---- /semcs shift <targets> <auto|day|night> ----
                // Sleep roster for [needs] units - own units only, no op
                // needed: it is a squad-management action like the whistle.
                .then(Commands.literal("shift")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("shift", StringArgumentType.word())
                                        .suggests(SHIFT_SUGGEST)
                                        .executes(SemClassCommands::setShift))))

                // ---- /semcs dismiss <targets> [confirm] ----
                // Release ownership: the unit goes back to being a wild PMC
                // (SEM's unowned PMCs act as regular NPCs - hostile rules
                // apply, so they can turn on you). Frees the recruit cap.
                // Dismissing more than DISMISS_CONFIRM_AT units needs the
                // literal "confirm" word - /semcs dismiss @e confirm.
                .then(Commands.literal("dismiss")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(ctx -> dismissUnits(ctx, false))
                                .then(Commands.literal("confirm")
                                        .executes(ctx -> dismissUnits(ctx, true)))))

                // ---- /semcs group <targets> <name|clear> ----
                // Fireteams: tag owned units with a squad name (A, B, recon,
                // whatever) - shows on the tablet roster and targets
                // order_group / dismiss_group.
                .then(Commands.literal("group")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(SemClassCommands::assignGroup))))

                // ---- /semcs order_group <squad> <order> ----
                .then(Commands.literal("order_group")
                        .then(Commands.argument("squad", StringArgumentType.word())
                                .then(Commands.argument("order", StringArgumentType.word())
                                        .suggests(ORDER_SUGGEST)
                                        .executes(SemClassCommands::orderGroup))))

                // ---- /semcs dismiss_group <squad> [confirm] ----
                .then(Commands.literal("dismiss_group")
                        .then(Commands.argument("squad", StringArgumentType.word())
                                .executes(ctx -> dismissGroup(ctx, false))
                                .then(Commands.literal("confirm")
                                        .executes(ctx -> dismissGroup(ctx, true))))));
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
        WeaponClass cls = assignableClass(arg);
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

        WeaponClass cls = assignableClass(classArg);
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

        WeaponClass cls = assignableClass(classArg);
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

    // ---- shift --------------------------------------------------------

    // /semcs shift <targets> <auto|day|night> - roster owned units onto a
    // sleep shift. auto = sleep whenever exhausted; day = sleep at night;
    // night = sleep during the day.
    private static int setShift(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        Player player = playerOrNull(ctx);
        if (player == null) return 0;

        int shift = switch (StringArgumentType.getString(ctx, "shift").toLowerCase()) {
            case "day" -> UnitNeeds.SHIFT_DAY;
            case "night" -> UnitNeeds.SHIFT_NIGHT;
            case "auto" -> UnitNeeds.SHIFT_AUTO;
            default -> -1;
        };
        if (shift < 0) {
            src.sendFailure(Component.literal("Unknown shift (use auto, day or night)"));
            return 0;
        }

        Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "targets");
        int changed = 0;
        for (Entity entity : targets) {
            if (entity instanceof net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity pmc
                    && player.getUUID().equals(pmc.getOwnerUUID())) {
                UnitNeeds.setShift(pmc, shift);
                changed++;
            }
        }
        if (changed == 0) {
            src.sendFailure(Component.literal("No owned units in the selected entities"));
            return 0;
        }
        int finalChanged = changed;
        int finalShift = shift;
        src.sendSuccess(() -> Component.literal(
                "Shift set to " + UnitNeeds.shiftLabel(finalShift)
                        + " for " + finalChanged + " unit(s)"), true);
        return changed;
    }

    // ---- dismiss -------------------------------------------------------

    // Above this many units at once, dismiss needs the trailing "confirm"
    // literal - releasing a whole squad of now-hostile PMCs on a misclick
    // is a bad day.
    private static final int DISMISS_CONFIRM_AT = 5;

    // /semcs dismiss <targets> [confirm] - drops ownership so the recruit
    // cap frees up. WARN the player: an ownerless PMC is a regular SEM
    // unit again and can go hostile - dismiss from a distance.
    private static int dismissUnits(CommandContext<CommandSourceStack> ctx, boolean confirmed)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        Player player = playerOrNull(ctx);
        if (player == null) return 0;

        java.util.List<net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity> owned = new java.util.ArrayList<>();
        for (Entity entity : EntityArgument.getEntities(ctx, "targets")) {
            if (entity instanceof net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity pmc
                    && player.getUUID().equals(pmc.getOwnerUUID())) {
                owned.add(pmc);
            }
        }
        if (owned.isEmpty()) {
            src.sendFailure(Component.literal("No owned units in the selected entities"));
            return 0;
        }
        if (!confirmed && owned.size() > DISMISS_CONFIRM_AT) {
            src.sendFailure(Component.literal("Refusing to dismiss " + owned.size()
                    + " units at once - they turn wild and hostile. "
                    + "Re-run with 'confirm': /semcs dismiss @e confirm"));
            return 0;
        }
        for (var pmc : owned) {
            pmc.setOwner(null);
            // Park them where they stand instead of resuming wild patrol
            pmc.setOrder(net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType.HOLD_POSITION);
        }
        src.sendSuccess(() -> Component.literal(
                "§eDismissed " + owned.size() + " unit(s) - they are wild PMCs now and can turn hostile"), true);
        return owned.size();
    }

    // ---- squads ---------------------------------------------------------

    private static final com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> ORDER_SUGGEST =
            (ctx, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                    java.util.List.of("follow", "hold", "wedge", "column", "free", "cease"), b);

    // /semcs group <targets> <name|clear> - tags owned units with a squad.
    private static int assignGroup(CommandContext<CommandSourceStack> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        Player player = playerOrNull(ctx);
        if (player == null) return 0;
        String name = StringArgumentType.getString(ctx, "name");

        int tagged = 0;
        for (Entity entity : EntityArgument.getEntities(ctx, "targets")) {
            if (entity instanceof net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity pmc
                    && player.getUUID().equals(pmc.getOwnerUUID())) {
                com.poseidon.sem_classsystem.entity.Squads.setSquad(pmc, name);
                tagged++;
            }
        }
        if (tagged == 0) {
            src.sendFailure(Component.literal("No owned units in the selected entities"));
            return 0;
        }
        boolean cleared = name.equalsIgnoreCase("clear") || name.equalsIgnoreCase("none");
        int finalTagged = tagged;
        src.sendSuccess(() -> Component.literal(cleared
                ? "§eCleared squad tag on " + finalTagged + " unit(s)"
                : "§aAssigned " + finalTagged + " unit(s) to squad '" + name + "'"), true);
        return tagged;
    }

    // All owned units in the given level matching the caller's squad tag.
    private static java.util.List<net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity>
    squadUnits(Player player, String squad) {
        java.util.List<net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity> out = new java.util.ArrayList<>();
        for (net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity e : player.level().getEntitiesOfClass(
                net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity.class,
                player.getBoundingBox().inflate(512.0))) {
            if (player.getUUID().equals(e.getOwnerUUID())
                    && squad.equalsIgnoreCase(
                            com.poseidon.sem_classsystem.entity.Squads.squad(e))) {
                out.add(e);
            }
        }
        return out;
    }

    // /semcs order_group <squad> <order> - broadcast one SEM order to the
    // whole fireteam.
    private static int orderGroup(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Player player = playerOrNull(ctx);
        if (player == null) return 0;
        String squad = StringArgumentType.getString(ctx, "squad");
        net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType order =
                switch (StringArgumentType.getString(ctx, "order").toLowerCase(java.util.Locale.ROOT)) {
                    case "follow" -> net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType.FOLLOW_COMMANDER;
                    case "hold" -> net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType.HOLD_POSITION;
                    case "wedge" -> net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType.FORM_WEDGE;
                    case "column" -> net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType.FORM_COLUMN;
                    case "free" -> net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType.FREE_FIRE;
                    case "cease" -> net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType.CEASE_FIRE;
                    default -> null;
                };
        if (order == null) {
            src.sendFailure(Component.literal(
                    "Unknown order - use follow/hold/wedge/column/free/cease"));
            return 0;
        }
        var units = squadUnits(player, squad);
        if (units.isEmpty()) {
            src.sendFailure(Component.literal("No owned units in squad '" + squad + "' here"));
            return 0;
        }
        for (var unit : units) {
            unit.setOrder(order);
        }
        net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType finalOrder = order;
        src.sendSuccess(() -> Component.literal("§aSquad '" + squad + "' -> "
                + finalOrder.name().replace('_', ' ') + " (" + units.size() + " unit(s))"), true);
        return units.size();
    }

    // /semcs dismiss_group <squad> [confirm] - release the whole fireteam.
    private static int dismissGroup(CommandContext<CommandSourceStack> ctx, boolean confirmed) {
        CommandSourceStack src = ctx.getSource();
        Player player = playerOrNull(ctx);
        if (player == null) return 0;
        String squad = StringArgumentType.getString(ctx, "squad");
        var units = squadUnits(player, squad);
        if (units.isEmpty()) {
            src.sendFailure(Component.literal("No owned units in squad '" + squad + "' here"));
            return 0;
        }
        if (!confirmed && units.size() > DISMISS_CONFIRM_AT) {
            src.sendFailure(Component.literal("Refusing to dismiss " + units.size()
                    + " units at once - re-run with 'confirm'"));
            return 0;
        }
        for (var pmc : units) {
            pmc.setOwner(null);
            pmc.setOrder(net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType.HOLD_POSITION);
        }
        src.sendSuccess(() -> Component.literal("§eDismissed squad '" + squad
                + "' - " + units.size() + " unit(s)"), true);
        return units.size();
    }

    // byConfigKey accepts every constant including subclasses - commands may
    // only assign main classes, so "pistol" is rejected here even though it
    // parses.
    private static WeaponClass assignableClass(String key) {
        WeaponClass cls = WeaponClass.byConfigKey(key);
        return cls != null && !cls.isSubclass() ? cls : null;
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
