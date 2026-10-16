// Mixin.client package - map command half: selection, orders, claim overlay
package com.poseidon.sem_classsystem.mixin.client;

import com.poseidon.sem_classsystem.client.UnitMarkerStore;
import com.poseidon.sem_classsystem.client.UiSounds;
import com.poseidon.sem_classsystem.client.xaero.LineOrder;
import com.poseidon.sem_classsystem.client.xaero.RoutePlot;
import com.poseidon.sem_classsystem.client.xaero.SemcsOrderOption;
import com.poseidon.sem_classsystem.map.UnitMarker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.MapProcessor;
import xaero.map.element.HoveredMapElementHolder;
import xaero.map.gui.GuiMap;
import xaero.map.gui.dropdown.rightclick.GuiRightClickMenu;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Left-click a unit marker to select it (OWN) or designate it (HOSTILE),
// middle-drag to box-select your squad, right-click the map for orders and
// chunk claims. Claimed chunks paint as translucent squares.
//
// Selection rides on mapClicked (Xaero resolves the hovered element into
// `viewed` first). The order entries ride on getRightClickOptions, which
// rebuilds the list per click with the clicked block in rightClickX/Y/Z.
//
// Lives in sem_classsystem.xaero.mixins.json (required:false +
// XaeroSemcsMixinPlugin): Xaero is optional, and the main config is
// required:true - a missing map mod must not become a startup crash.
@Mixin(value = GuiMap.class, remap = false)
public abstract class MixinSemcsGuiMap extends Screen {

    protected MixinSemcsGuiMap(Component title) {
        super(title);
    }

    @Shadow
    private HoveredMapElementHolder<?, ?> viewed;

    @Shadow
    private int rightClickX;

    @Shadow
    private int rightClickY;

    @Shadow
    private int rightClickZ;

    @Shadow
    private ResourceKey<Level> rightClickDim;

    @Shadow
    private double cameraX;

    @Shadow
    private double cameraZ;

    @Shadow
    private double scale;

    @Shadow
    private double screenScale;

    // Xaero's open menus - while any is up a click belongs to it, not to us.
    @Shadow
    private GuiRightClickMenu rightClickMenu;

    @Shadow
    public boolean waypointMenu;

    @Shadow
    public boolean playersMenu;

    // The block under the cursor and the map backend (for the viewed
    // dimension) - drag orders anchor to world coords so they survive pans.
    @Shadow
    private int mouseBlockPosX;

    @Shadow
    private int mouseBlockPosY;

    @Shadow
    private int mouseBlockPosZ;

    @Shadow
    private MapProcessor mapProcessor;

    // Xaero's "no surface height known" sentinel - unexplored tile/cave mode.
    @Unique
    private static final int SEMCS$NO_HEIGHT = 32767;

    // Middle-drag box select: press anchors a screen-space corner, release
    // catches every own marker inside. Instance state - dies with the screen.
    @Unique
    private boolean semcs$boxSelecting;

    @Unique
    private double semcs$boxAx;

    @Unique
    private double semcs$boxAy;

    @Unique
    private static final double SEMCS$BOX_MIN_PX = 6.0;

    // Left-drag line order with >=2 own units selected: A/B in world coords.
    @Unique
    private boolean semcs$orderDragging;

    @Unique
    private int semcs$dragAx;

    @Unique
    private int semcs$dragAy;

    @Unique
    private int semcs$dragAz;

    // Marker under the press, so a barely-moved drag toggles it as a click.
    @Unique
    private UnitMarker semcs$pressMarker;

    // Below this (blocks squared) an order-drag is a click, not a line.
    @Unique
    private static final double SEMCS$MIN_DRAG_SQ = 16.0;

    // Route plotting buttons: added once, only visible while RoutePlot is
    // armed - the mode starts from the right-click menu, long after init ran.
    @Unique
    private Button semcs$routeConfirm;

    @Unique
    private Button semcs$routeCancel;

    @Inject(method = "init", at = @At("RETURN"), remap = true)
    private void semcs$addPlotButtons(CallbackInfo ci) {
        int y = this.height - 28;
        this.semcs$routeConfirm = this.addRenderableWidget(Button.builder(
                Component.translatable("gui.sem_classsystem.map.route_confirm"),
                b -> this.semcs$confirmRoute())
                .bounds(this.width / 2 - 104, y, 100, 20).build());
        this.semcs$routeCancel = this.addRenderableWidget(Button.builder(
                Component.translatable("gui.sem_classsystem.map.route_cancel"),
                b -> {
                    RoutePlot.cancel();
                    this.semcs$hint("message.sem_classsystem.map.route_cancelled");
                })
                .bounds(this.width / 2 + 4, y, 100, 20).build());
        this.semcs$routeConfirm.visible = false;
        this.semcs$routeCancel.visible = false;
    }

    @Unique
    private void semcs$confirmRoute() {
        if (!RoutePlot.canConfirm()) {
            this.semcs$hint("message.sem_classsystem.map.route_need_nodes");
            return;
        }
        RoutePlot.confirm();
    }

    // Left-click on a unit marker toggles it; a click on open map with a
    // selection clears it. Click-swallowing keeps Xaero from panning on the
    // same press.
    @Inject(method = "mapClicked", at = @At("HEAD"), cancellable = true)
    private void semcs$mapClicked(int button, int x, int y, CallbackInfo ci) {
        // Plot clicks are handled in mouseClicked - mapClicked must not let
        // selection or the right-click path fire on top of the plot.
        if (RoutePlot.armed()) {
            ci.cancel();
            return;
        }
        if (button != 0) {
            return;
        }
        if (this.viewed != null
                && this.viewed.getElement() instanceof UnitMarker marker) {
            if (UnitMarkerStore.toggleSelected(marker)) {
                UiSounds.click();
                ci.cancel();
            }
            return;
        }
        if (!UnitMarkerStore.selectedUnitIds().isEmpty()) {
            UnitMarkerStore.clearSelection();
            ci.cancel();
        }
    }

    // Middle-press begins the box. Swallowed so Xaero does not treat MMB as
    // something else; a barely-moved release selects nothing.
    //
    // With >=2 own units selected a LEFT press starts a line-order drag
    // instead - swallowed here so Xaero never begins a camera pan, and judged
    // on release: a real drag dispatches, a barely-moved one is a plain click.
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, remap = true)
    private void semcs$onMapPress(double mouseX, double mouseY, int button,
                                  CallbackInfoReturnable<Boolean> cir) {
        // While route plotting is armed the map's clicks are ours: a widget
        // press still belongs to the widget, but the map itself lays nodes.
        if (RoutePlot.armed()) {
            if (this.getChildAt(mouseX, mouseY).isPresent()) {
                return;
            }
            if (button == 0) {
                RoutePlot.add(new BlockPos(this.mouseBlockPosX,
                        this.semcs$nodeY(), this.mouseBlockPosZ));
            } else if (button == 1) {
                RoutePlot.removeLast();
            }
            cir.setReturnValue(true);
            return;
        }
        if (this.semcs$dropdownOpen() || this.getChildAt(mouseX, mouseY).isPresent()) {
            return;
        }
        if (button == 2) {
            this.semcs$boxSelecting = true;
            this.semcs$boxAx = mouseX;
            this.semcs$boxAy = mouseY;
            cir.setReturnValue(true);
            return;
        }
        if (button != 0) {
            return;
        }
        // Marker pick in screen space - our own hit test, independent of
        // Xaero's `viewed` hover resolution so a click ALWAYS lands even when
        // other elements (player tracker, waypoints) sit on top of the icon.
        UnitMarker pressed = this.semcs$markerAt(mouseX, mouseY);
        if (UnitMarkerStore.selectedUnitIds().size() >= 2) {
            this.semcs$orderDragging = true;
            this.semcs$dragAx = this.mouseBlockPosX;
            this.semcs$dragAz = this.mouseBlockPosZ;
            this.semcs$dragAy = this.semcs$nodeY();
            this.semcs$pressMarker = pressed;
            cir.setReturnValue(true);
            return;
        }
        if (pressed != null) {
            // Swallowing the press means GuiMap never sets leftMouseButton.
            // isDown, so its mouseReleased never reaches mapClicked - no
            // second toggle from the `viewed` path.
            UnitMarkerStore.toggleSelected(pressed);
            UiSounds.click();
            cir.setReturnValue(true);
        }
    }

    // Nearest unit marker within pick radius of the cursor (screen pixels).
    // Icons draw at HIT_BOX ~9px under optionalScale; 14px is a forgiving
    // click target that still picks the closest icon when they overlap.
    @Unique
    private static final double SEMCS$PICK_PX = 14.0;

    @Unique
    private UnitMarker semcs$markerAt(double mouseX, double mouseY) {
        UnitMarker best = null;
        double bestDist = SEMCS$PICK_PX * SEMCS$PICK_PX;
        ResourceKey<Level> viewed = this.semcs$viewedDim();
        String dim = viewed == null ? "" : viewed.location().toString();
        for (UnitMarker marker : UnitMarkerStore.markers()) {
            if (!marker.dimension().equals(dim)) {
                continue;
            }
            int[] at = this.semcs$toScreenXZ(marker.x(), marker.z());
            double dx = at[0] - mouseX;
            double dy = at[1] - mouseY;
            double d2 = dx * dx + dy * dy;
            if (d2 <= bestDist) {
                bestDist = d2;
                best = marker;
            }
        }
        return best;
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true, remap = true)
    private void semcs$onMapRelease(double mouseX, double mouseY, int button,
                                    CallbackInfoReturnable<Boolean> cir) {
        // A menu opened mid-gesture owns this release - drop our half of it.
        if (this.semcs$dropdownOpen()) {
            this.semcs$boxSelecting = false;
            this.semcs$orderDragging = false;
            this.semcs$pressMarker = null;
            return;
        }
        if (button == 0 && this.semcs$orderDragging) {
            this.semcs$orderDragging = false;
            UnitMarker pressed = this.semcs$pressMarker;
            this.semcs$pressMarker = null;

            Vec3 a = new Vec3(this.semcs$dragAx + 0.5, this.semcs$dragAy,
                    this.semcs$dragAz + 0.5);
            Vec3 b = new Vec3(this.mouseBlockPosX + 0.5, this.semcs$nodeY(),
                    this.mouseBlockPosZ + 0.5);
            double dx = b.x - a.x, dz = b.z - a.z;
            if (dx * dx + dz * dz < SEMCS$MIN_DRAG_SQ) {
                // A click, not a drag: toggle the marker under the press, or
                // clear the selection on open map.
                if (pressed != null) {
                    UnitMarkerStore.toggleSelected(pressed);
                    UiSounds.click();
                } else {
                    UnitMarkerStore.clearSelection();
                }
            } else {
                this.semcs$dispatchLine(a, b);
            }
            cir.setReturnValue(true);
            return;
        }
        if (button != 2 || !this.semcs$boxSelecting) {
            return;
        }
        this.semcs$boxSelecting = false;

        double dx = mouseX - this.semcs$boxAx;
        double dy = mouseY - this.semcs$boxAy;
        if (dx * dx + dy * dy < SEMCS$BOX_MIN_PX * SEMCS$BOX_MIN_PX) {
            cir.setReturnValue(true);
            return;
        }
        double x0 = Math.min(this.semcs$boxAx, mouseX);
        double y0 = Math.min(this.semcs$boxAy, mouseY);
        double x1 = Math.max(this.semcs$boxAx, mouseX);
        double y1 = Math.max(this.semcs$boxAy, mouseY);
        for (UnitMarker marker : UnitMarkerStore.markers()) {
            int[] at = this.semcs$toScreenXZ(marker.x(), marker.z());
            if (at[0] >= x0 && at[0] <= x1 && at[1] >= y0 && at[1] <= y1) {
                UnitMarkerStore.addSelected(marker);
            }
        }
        cir.setReturnValue(true);
    }

    // Append unit orders + claim entries to the map's right-click menu. The
    // clicked block sits in rightClickX/Y/Z and its dimension in rightClickDim.
    @Inject(method = "getRightClickOptions", at = @At("RETURN"))
    private void semcs$orderOptions(CallbackInfoReturnable<ArrayList<RightClickOption>> cir) {
        ArrayList<RightClickOption> options = cir.getReturnValue();
        if (options == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        int chunkX = this.rightClickX >> 4;
        int chunkZ = this.rightClickZ >> 4;
        UUID me = mc.player.getUUID();
        UUID owner = UnitMarkerStore.claimOwner(chunkX, chunkZ);
        boolean claimed = owner != null;
        boolean mine = me.equals(owner);

        options.addAll(SemcsOrderOption.allFor(
                options.size(), (GuiMap) (Object) this,
                this.rightClickX, this.rightClickY, this.rightClickZ, this.rightClickDim,
                UnitMarkerStore.selectedUnitIds().size(),
                UnitMarkerStore.selectedHostileTargetId(),
                claimed, mine));
    }

    // ENTER confirms the plot, ESC drops it - never while a text box
    // (Xaero's coordinate hop field) is holding focus.
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, remap = true)
    private void semcs$onKey(int keyCode, int scanCode, int modifiers,
                             CallbackInfoReturnable<Boolean> cir) {
        if (!RoutePlot.armed() || this.getFocused() instanceof EditBox) {
            return;
        }
        if (keyCode == 257) {           // ENTER
            this.semcs$confirmRoute();
            cir.setReturnValue(true);
        } else if (keyCode == 256) {    // ESC - cancel plot, keep the map open
            RoutePlot.cancel();
            this.semcs$hint("message.sem_classsystem.map.route_cancelled");
            cir.setReturnValue(true);
        }
    }

    // Closing the map drops the selection - it belongs to the screen session.
    @Inject(method = "removed", at = @At("HEAD"), remap = true)
    private void semcs$onClosed(CallbackInfo ci) {
        UnitMarkerStore.clearSelection();
        this.semcs$boxSelecting = false;
        this.semcs$orderDragging = false;
        this.semcs$pressMarker = null;
        RoutePlot.cancel();
    }

    // Paint claimed chunks + the in-flight selection box over the map.
    @Inject(method = "render", at = @At("TAIL"), remap = true)
    private void semcs$drawClaims(GuiGraphics guiGraphics, int mouseX, int mouseY,
                                  float partialTicks, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        UUID me = mc.player.getUUID();

        for (Map.Entry<UUID, Set<Long>> entry : UnitMarkerStore.claims().entrySet()) {
            boolean mine = me.equals(entry.getKey());
            int frame = mine ? 0xFF55FF55 : 0xFFFFAA00;
            int fill = mine ? 0x3355FF55 : 0x33FFAA00;
            for (Long pos : entry.getValue()) {
                int[] a = this.semcs$toScreenXZ(ChunkPos.getX(pos) << 4,
                        ChunkPos.getZ(pos) << 4);
                int[] b = this.semcs$toScreenXZ((ChunkPos.getX(pos) << 4) + 16,
                        (ChunkPos.getZ(pos) << 4) + 16);
                int x0 = Math.min(a[0], b[0]);
                int y0 = Math.min(a[1], b[1]);
                int x1 = Math.max(a[0], b[0]);
                int y1 = Math.max(a[1], b[1]);
                if (x1 <= x0 || y1 <= y0
                        || x1 < 0 || y1 < 0 || x0 > this.width || y0 > this.height) {
                    continue;
                }
                guiGraphics.fill(x0, y0, x1, y1, fill);
                guiGraphics.fill(x0, y0, x1, y0 + 1, frame);
                guiGraphics.fill(x0, y1 - 1, x1, y1, frame);
                guiGraphics.fill(x0, y0, x0 + 1, y1, frame);
                guiGraphics.fill(x1 - 1, y0, x1, y1, frame);
            }
        }

        if (this.semcs$boxSelecting) {
            int x0 = (int) Math.min(this.semcs$boxAx, mouseX);
            int y0 = (int) Math.min(this.semcs$boxAy, mouseY);
            int x1 = (int) Math.max(this.semcs$boxAx, mouseX);
            int y1 = (int) Math.max(this.semcs$boxAy, mouseY);
            guiGraphics.fill(x0, y0, x1, y1, 0x2255FF55);
            guiGraphics.fill(x0, y0, x1, y0 + 1, 0xAA55FF55);
            guiGraphics.fill(x0, y1 - 1, x1, y1, 0xAA55FF55);
            guiGraphics.fill(x0, y0, x0 + 1, y1, 0xAA55FF55);
            guiGraphics.fill(x1 - 1, y0, x1, y1, 0xAA55FF55);
        }

        // The line being laid down right now, with a pip at each unit's slot.
        if (this.semcs$orderDragging && UnitMarkerStore.selectedUnitIds().size() >= 2) {
            this.semcs$drawDragPreview(guiGraphics);
        }

        this.semcs$drawDutyLines(guiGraphics);

        // Route plotting overlay: the polyline, numbered nodes, and the two
        // buttons. Disarm when the map's viewed dimension stops matching the
        // one the route was started in - waypoints are dimension-bound.
        if (this.semcs$routeConfirm != null) {
            this.semcs$routeConfirm.visible = RoutePlot.armed();
        }
        if (this.semcs$routeCancel != null) {
            this.semcs$routeCancel.visible = RoutePlot.armed();
        }
        if (RoutePlot.armed()) {
            ResourceKey<Level> viewed = this.semcs$viewedDim();
            if (viewed == null || !viewed.location().toString()
                    .equals(RoutePlot.dimension())) {
                RoutePlot.cancel();
            } else {
                this.semcs$drawRoutePlot(guiGraphics);
            }
        }

        this.semcs$drawRaidPing(guiGraphics);
    }

    // Night raid ping: a pulsing red marker over the spawn point when it is
    // on-screen, a red edge chevron pointing at it when it is not. Only on
    // the map's own dimension.
    @Unique
    private void semcs$drawRaidPing(GuiGraphics guiGraphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        ResourceKey<Level> viewed = this.semcs$viewedDim();
        String dim = viewed == null ? "" : viewed.location().toString();
        double[] ping = UnitMarkerStore.raidPing(dim, mc.level.getGameTime());
        if (ping == null) {
            return;
        }
        int pulse = 140 + (int) (90.0 * (0.5 + 0.5
                * Math.sin(System.currentTimeMillis() / 1000.0 * Math.PI * 2.0)));
        int color = (pulse << 24) | 0xFF3333;
        int dist = (int) Math.round(Math.hypot(
                ping[0] - mc.player.getX(), ping[1] - mc.player.getZ()));
        String label = Component.translatable("semcs.raid_ping").getString()
                + " " + dist + "m";
        int[] at = this.semcs$toScreenXZ(ping[0], ping[1]);

        if (at[0] >= 12 && at[0] <= this.width - 12
                && at[1] >= 12 && at[1] <= this.height - 12) {
            guiGraphics.fill(at[0] - 5, at[1] - 5, at[0] + 5, at[1] + 5,
                    (color & 0x00FFFFFF) | 0x66000000);
            LineOrder.node(guiGraphics, at[0], at[1], color);
            guiGraphics.drawCenteredString(this.font, label,
                    at[0], at[1] + 8, 0xFFFF5555);
            return;
        }
        // Off-screen: pin a chevron to the screen edge along the bearing.
        double dx = at[0] - this.width / 2.0;
        double dy = at[1] - this.height / 2.0;
        double len = Math.hypot(dx, dy);
        if (len < 1.0) {
            return;
        }
        double ux = dx / len, uy = dy / len;
        double margin = 20.0;
        double t = Math.min(
                Math.abs(ux) > 0.001 ? (this.width / 2.0 - margin) / Math.abs(ux)
                        : Double.MAX_VALUE,
                Math.abs(uy) > 0.001 ? (this.height / 2.0 - margin) / Math.abs(uy)
                        : Double.MAX_VALUE);
        int tipX = (int) Math.round(this.width / 2.0 + ux * t);
        int tipY = (int) Math.round(this.height / 2.0 + uy * t);
        double px = -uy, py = ux;
        int solid = 0xFFFF5555;
        this.semcs$leg(guiGraphics, tipX, tipY,
                (int) Math.round(tipX - ux * 10 + px * 6),
                (int) Math.round(tipY - uy * 10 + py * 6), solid);
        this.semcs$leg(guiGraphics, tipX, tipY,
                (int) Math.round(tipX - ux * 10 - px * 6),
                (int) Math.round(tipY - uy * 10 - py * 6), solid);
        guiGraphics.drawCenteredString(this.font, label,
                (int) Math.round(tipX - ux * 22),
                (int) Math.round(tipY - uy * 22) - 4, solid);
    }

    // A solid 2px screen-space line segment - chevron wings.
    @Unique
    private void semcs$leg(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(1, Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1)));
        for (int i = 0; i <= steps; i++) {
            int x = x1 + (x2 - x1) * i / steps;
            int y = y1 + (y2 - y1) * i / steps;
            g.fill(x - 1, y - 1, x + 1, y + 1, color);
        }
    }

    @Unique
    private void semcs$drawRoutePlot(GuiGraphics guiGraphics) {
        List<BlockPos> nodes = RoutePlot.nodes();
        int color = 0xFFFFD75F;
        for (int i = 0; i < nodes.size(); i++) {
            if (i + 1 < nodes.size()) {
                int[] a = this.semcs$toScreenXZ(nodes.get(i).getX() + 0.5,
                        nodes.get(i).getZ() + 0.5);
                int[] b = this.semcs$toScreenXZ(nodes.get(i + 1).getX() + 0.5,
                        nodes.get(i + 1).getZ() + 0.5);
                LineOrder.dashedLine(guiGraphics, a[0], a[1], b[0], b[1], color);
            }
            int[] at = this.semcs$toScreenXZ(nodes.get(i).getX() + 0.5,
                    nodes.get(i).getZ() + 0.5);
            LineOrder.node(guiGraphics, at[0], at[1], color);
            guiGraphics.drawString(this.font, String.valueOf(i + 1),
                    at[0] + 5, at[1] - 4, 0xFFFFFFFF, false);
        }
        guiGraphics.drawCenteredString(this.font, Component.translatable(
                        "message.sem_classsystem.map.route_status", nodes.size()),
                this.width / 2, this.height - 42, 0xFFFFFFFF);
    }

    // Duty tethers: a dashed line from each posted own unit to its chunk
    // centre, or the waypoint polyline for route duty. Bright while the unit
    // is selected, faint otherwise - standing orders, not the focus.
    @Unique
    private void semcs$drawDutyLines(GuiGraphics guiGraphics) {
        ResourceKey<Level> viewed = this.semcs$viewedDim();
        String dim = viewed == null ? "" : viewed.location().toString();
        for (UnitMarker marker : UnitMarkerStore.markers()) {
            if (marker.dutyMode() == 0
                    || marker.allegiance() != UnitMarker.Allegiance.OWN
                    || !marker.dimension().equals(dim)) {
                continue;
            }
            int alpha = UnitMarkerStore.isSelected(marker) ? 0xFF : 0x59;
            int color = (alpha << 24) | 0xFFD75F;
            int[] from = this.semcs$toScreenXZ(marker.x(), marker.z());
            if (marker.dutyMode() == 3 && marker.route().length > 0) {
                int[] prev = from;
                for (long wp : marker.route()) {
                    BlockPos p = BlockPos.of(wp);
                    int[] at = this.semcs$toScreenXZ(p.getX() + 0.5, p.getZ() + 0.5);
                    LineOrder.dashedLine(guiGraphics, prev[0], prev[1], at[0], at[1], color);
                    LineOrder.node(guiGraphics, at[0], at[1], color);
                    prev = at;
                }
            } else {
                int[] to = this.semcs$toScreenXZ(
                        (marker.dutyChunkX() << 4) + 8, (marker.dutyChunkZ() << 4) + 8);
                LineOrder.dashedLine(guiGraphics, from[0], from[1], to[0], to[1], color);
            }
        }
    }

    @Unique
    private void semcs$drawDragPreview(GuiGraphics guiGraphics) {
        Vec3 a = new Vec3(this.semcs$dragAx + 0.5, this.semcs$dragAy,
                this.semcs$dragAz + 0.5);
        Vec3 b = new Vec3(this.mouseBlockPosX + 0.5, this.semcs$nodeY(),
                this.mouseBlockPosZ + 0.5);
        List<Integer> ids = LineOrder.sortedIds(a, b);
        int[] sa = this.semcs$toScreenXZ(a.x, a.z);
        int[] sb = this.semcs$toScreenXZ(b.x, b.z);
        LineOrder.dashedLine(guiGraphics, sa[0], sa[1], sb[0], sb[1], 0xAA55FF55);
        for (int i = 0; i < ids.size(); i++) {
            Vec3 p = LineOrder.slot(a, b, i, ids.size());
            int[] at = this.semcs$toScreenXZ(p.x, p.z);
            LineOrder.node(guiGraphics, at[0], at[1], 0xFF55FF55);
        }
    }

    // Refuse cross-dimension orders - the SEM order packet would run in the
    // player's level and silently miss units the map is showing elsewhere.
    @Unique
    private void semcs$dispatchLine(Vec3 a, Vec3 b) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        ResourceKey<Level> viewed = this.semcs$viewedDim();
        if (viewed != null && !viewed.equals(mc.player.level().dimension())) {
            mc.player.displayClientMessage(Component.translatable(
                    "message.sem_classsystem.map.line_wrong_dimension")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        LineOrder.dispatch(a, b);
        UiSounds.confirm();
    }

    @Unique
    private void semcs$hint(String key, Object... args) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable(key, args)
                    .withStyle(ChatFormatting.YELLOW), true);
        }
    }

    // Height for a plotted point: the map's surface guess, or the player's
    // own Y where the map has none (never the 32767 sentinel).
    @Unique
    private int semcs$nodeY() {
        if (this.mouseBlockPosY != SEMCS$NO_HEIGHT) {
            return this.mouseBlockPosY;
        }
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? 64 : mc.player.getBlockY();
    }

    // The dimension the map is currently showing - not always the player's.
    @Unique
    private ResourceKey<Level> semcs$viewedDim() {
        if (this.mapProcessor == null || this.mapProcessor.getMapWorld() == null) {
            return null;
        }
        return this.mapProcessor.getMapWorld().getCurrentDimensionId();
    }

    @Unique
    private boolean semcs$dropdownOpen() {
        return this.rightClickMenu != null || this.waypointMenu || this.playersMenu;
    }

    // World XZ to screen pixels: the map's maths runs in window pixels, so
    // divide back out by the same screenScale the map applies.
    @Unique
    private int[] semcs$toScreenXZ(double wx, double wz) {
        double px = (wx - this.cameraX) * this.scale / this.screenScale;
        double pz = (wz - this.cameraZ) * this.scale / this.screenScale;
        return new int[]{
                (int) Math.round(this.width / 2.0 + px),
                (int) Math.round(this.height / 2.0 + pz)};
    }
}
