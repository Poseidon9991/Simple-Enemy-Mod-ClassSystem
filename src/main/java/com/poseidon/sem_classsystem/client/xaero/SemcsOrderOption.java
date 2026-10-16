// Client.xaero package - one entry in the world map's right-click menu
package com.poseidon.sem_classsystem.client.xaero;

import com.poseidon.sem_classsystem.client.UnitMarkerStore;
import com.poseidon.sem_classsystem.client.UiSounds;
import com.poseidon.sem_classsystem.network.PacketAssignDuty;
import com.poseidon.sem_classsystem.network.PacketClaimChunk;
import com.poseidon.sem_classsystem.network.SemcsNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.nekoyuni.SimpleEnemyMod.entity.ai.orders.OrderType;
import net.nekoyuni.SimpleEnemyMod.network.ModNetworking;
import net.nekoyuni.SimpleEnemyMod.network.packets.PacketIssueOrder;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// Squad orders + chunk claims in the map's right-click menu. SEM orders ride
// SEM's own PacketIssueOrder - it carries the whole OrderType set and already
// refuses units the sender does not own. Duty + claims go on this mod's
// channel; both are validated server-side (ownership + claim).
//
// A real class rather than anonymous inner classes in the mixin - inner
// classes declared in a mixin get merged into the target along with javac's
// synthetic accessors.
public class SemcsOrderOption extends RightClickOption {

    private static final int NO_HEIGHT = 32767;

    private enum Category {
        MOVEMENT(ChatFormatting.AQUA),
        STANCE(ChatFormatting.GREEN),
        DUTY(ChatFormatting.YELLOW),
        CLAIM(ChatFormatting.GOLD);

        final Style style;

        Category(ChatFormatting color) {
            this.style = Style.EMPTY.withColor(color);
        }
    }

    public enum Action {
        MOVE("move_here", true, Category.MOVEMENT),
        FOLLOW("follow_me", false, Category.MOVEMENT),
        HOLD("hold", false, Category.STANCE),
        FREE_FIRE("free_fire", false, Category.STANCE),
        CEASE_FIRE("cease_fire", false, Category.STANCE),
        ATTACK_THAT("attack_that", false, Category.STANCE),
        GUARD_CHUNK("guard_chunk", true, Category.DUTY),
        PATROL_CHUNK("patrol_chunk", true, Category.DUTY),
        PATROL_REGION("patrol_region", true, Category.DUTY),
        PLOT_ROUTE("plot_route", false, Category.DUTY),
        RETURN_POST("return_post", false, Category.DUTY),
        CLAIM_CHUNK("claim_chunk", true, Category.CLAIM),
        UNCLAIM_CHUNK("unclaim_chunk", true, Category.CLAIM);

        final String labelKey;
        final boolean positional;
        final Category category;

        Action(String label, boolean positional, Category category) {
            this.labelKey = "gui.sem_classsystem.map." + label;
            this.positional = positional;
            this.category = category;
        }
    }

    private final Action action;
    private final int x;
    private final int y;
    private final int z;
    private final ResourceKey<Level> dimension;
    private final int attackTargetId;

    public SemcsOrderOption(int index, IRightClickableElement target, Action action,
                            int x, int y, int z, ResourceKey<Level> dimension,
                            int selectedCount, int attackTargetId,
                            boolean clickedClaimed, boolean clickedMine) {
        super(action.labelKey, action.category.style, index, target);
        this.action = action;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension;
        this.attackTargetId = attackTargetId;

        boolean active = switch (action) {
            case MOVE, FOLLOW, HOLD, FREE_FIRE, CEASE_FIRE -> selectedCount > 0;
            case ATTACK_THAT -> selectedCount > 0 && attackTargetId >= 0;
            case GUARD_CHUNK, PATROL_CHUNK, PATROL_REGION -> selectedCount > 0 && clickedMine;
            case PLOT_ROUTE, RETURN_POST -> selectedCount > 0;
            case CLAIM_CHUNK -> !clickedClaimed;
            case UNCLAIM_CHUNK -> clickedMine;
        };
        setActive(active);
        setNameFormatArgs(selectedCount);
    }

    @Override
    public void onAction(Screen screen) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        String dim = this.dimension != null
                ? this.dimension.location().toString()
                : player.level().dimension().location().toString();
        int chunkX = this.x >> 4;
        int chunkZ = this.z >> 4;

        switch (this.action) {
            case CLAIM_CHUNK -> {
                SemcsNetwork.CHANNEL.sendToServer(
                        new PacketClaimChunk(dim, chunkX, chunkZ, true));
                UiSounds.confirm();
                return;
            }
            case UNCLAIM_CHUNK -> {
                SemcsNetwork.CHANNEL.sendToServer(
                        new PacketClaimChunk(dim, chunkX, chunkZ, false));
                UiSounds.confirm();
                return;
            }
            case GUARD_CHUNK, PATROL_CHUNK, PATROL_REGION -> {
                Set<Integer> ids = UnitMarkerStore.selectedUnitIds();
                if (ids.isEmpty()) {
                    return;
                }
                if (!dim.equals(player.level().dimension().location().toString())) {
                    hint("message.sem_classsystem.map.wrong_dimension");
                    return;
                }
                SemcsNetwork.CHANNEL.sendToServer(new PacketAssignDuty(
                        new ArrayList<>(ids), dim, chunkX, chunkZ,
                        switch (this.action) {
                            case PATROL_CHUNK -> PacketAssignDuty.MODE_PATROL;
                            case PATROL_REGION -> PacketAssignDuty.MODE_PERIMETER;
                            default -> PacketAssignDuty.MODE_GUARD;
                        }));
                UnitMarkerStore.clearSelection();
                UiSounds.confirm();
                return;
            }
            case RETURN_POST -> {
                Set<Integer> ids = UnitMarkerStore.selectedUnitIds();
                if (!ids.isEmpty()) {
                    SemcsNetwork.CHANNEL.sendToServer(
                            new com.poseidon.sem_classsystem.network.PacketReturnToPost(
                                    new ArrayList<>(ids)));
                    UiSounds.confirm();
                }
                return;
            }
            case PLOT_ROUTE -> {
                // Arms the map's route plotting mode; the selection is
                // captured now so it cannot silently change mid-plot.
                if (RoutePlot.arm(dim)) {
                    UiSounds.click();
                    hint("message.sem_classsystem.map.route_plotting");
                }
                return;
            }
            default -> {
            }
        }

        // SEM orders below
        Set<Integer> ids = UnitMarkerStore.selectedUnitIds();
        if (ids.isEmpty()) {
            return;
        }
        if (this.action.positional
                && !dim.equals(player.level().dimension().location().toString())) {
            hint("message.sem_classsystem.map.wrong_dimension");
            return;
        }

        if (this.action == Action.ATTACK_THAT) {
            if (this.attackTargetId < 0) {
                return;
            }
            for (int id : ids) {
                ModNetworking.CHANNEL.sendToServer(new PacketIssueOrder(
                        id, OrderType.ATTACK_THAT_TARGET, Vec3.ZERO, 0, this.attackTargetId));
            }
            UiSounds.confirm();
            hint("message.sem_classsystem.map.attack_that", ids.size());
            return;
        }

        Vec3 destination = this.action.positional ? destination(player) : Vec3.ZERO;
        OrderType order = switch (this.action) {
            case MOVE -> OrderType.MOVE_TO_POSITION;
            case FOLLOW -> OrderType.FOLLOW_COMMANDER;
            case HOLD -> OrderType.HOLD_POSITION;
            case FREE_FIRE -> OrderType.FREE_FIRE;
            case CEASE_FIRE -> OrderType.CEASE_FIRE;
            default -> throw new IllegalStateException(this.action + " is not a SEM order");
        };
        for (int id : ids) {
            ModNetworking.CHANNEL.sendToServer(
                    new PacketIssueOrder(id, order, destination, 0, -1));
        }
        UiSounds.confirm();
        if (this.action == Action.MOVE) {
            UnitMarkerStore.clearSelection();
        }
    }

    private Vec3 destination(LocalPlayer player) {
        double destY = this.y == NO_HEIGHT ? player.getY() : this.y + 1;
        return new Vec3(this.x + 0.5, destY, this.z + 0.5);
    }

    // Every entry for the position the player right-clicked.
    public static List<RightClickOption> allFor(int firstIndex, IRightClickableElement target,
                                                int x, int y, int z,
                                                ResourceKey<Level> dimension,
                                                int selectedCount, int attackTargetId,
                                                boolean clickedClaimed, boolean clickedMine) {
        List<RightClickOption> options = new ArrayList<>();
        for (Action action : Action.values()) {
            if (action == Action.ATTACK_THAT && attackTargetId < 0) {
                continue;
            }
            options.add(new SemcsOrderOption(firstIndex + options.size(), target, action,
                    x, y, z, dimension, selectedCount, attackTargetId,
                    clickedClaimed, clickedMine));
        }
        return options;
    }

    private static void hint(String key, Object... args) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(
                    Component.translatable(key, args).withStyle(ChatFormatting.GREEN), true);
        }
    }
}
