// Client.xaero package - unit markers on Xaero's World Map
package com.poseidon.sem_classsystem.client.xaero;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.poseidon.sem_classsystem.client.UnitMarkerStore;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.faction.UnitClass;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.map.UnitMarker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.map.element.render.ElementReader;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderProvider;
import xaero.map.element.render.ElementRenderer;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

// Classifiable SEM units on Xaero's World Map: own squad always, others
// within the server's spot range. Implemented through Xaero's element
// framework (renderer + reader + provider) so hover detection lands in
// GuiMap.viewed for MixinSemcsGuiMap to pick up.
//
// Icons are drawn procedurally (no texture assets): a faction-coloured
// square, an allegiance frame, a one-letter class glyph and a health bar -
// readable at every zoom and zero art to maintain.
public final class UnitMarkerElements {

    private static final int HALF = 8;              // icon half-size
    private static final int HIT_BOX = 9;
    private static final int RENDER_BOX = 12;
    private static final int SELECTION_COLOR = 0xFFFFFFFF;
    private static final int SELECTION_SHADE = 0x60000000;

    // Allegiance frame colours
    private static final int FRAME_OWN = 0xFF55FF55;
    private static final int FRAME_FRIENDLY = 0xFFAAAAAA;
    private static final int FRAME_HOSTILE = 0xFFFF3333;

    public static final Renderer INSTANCE = new Renderer();

    private UnitMarkerElements() {
    }

    // One-letter glyph per weapon class - BMP chars so unifont covers them.
    public static String classGlyph(int classOrd) {
        WeaponClass[] values = WeaponClass.values();
        if (classOrd < 0 || classOrd >= values.length) {
            return "?";
        }
        return switch (values[classOrd]) {
            case RIFLE -> "R";
            case SMG -> "B";        // Breacher
            case SHOTGUN -> "S";    // Sweeper
            case SNIPER -> "C";     // Camper
            case HEAVY_MG -> "M";   // Machine-gunner (Suppressor)
            case HEAVY -> "D";      // Demolisher
            case JACK -> "J";
            case MEDIC -> "+";
            case PISTOL -> "P";
        };
    }

    // Display class name for the hover card: the configured nickname, or "?"
    // for a unit with no class rolled yet.
    public static String className(int classOrd) {
        WeaponClass[] values = WeaponClass.values();
        if (classOrd < 0 || classOrd >= values.length) {
            return "?";
        }
        return ClassSystemConfig.classNickname(values[classOrd]);
    }

    public static String factionName(int factionOrd) {
        UnitClass[] values = UnitClass.values();
        if (factionOrd < 0 || factionOrd >= values.length) {
            return "Unit";
        }
        return values[factionOrd].displayName();
    }

    // SEM OrderType ordinals -> lang keys, indexed in declaration order.
    private static final String[] ORDER_KEYS = {
            "semcs.order.follow_commander",
            "semcs.order.hold_position",
            "semcs.order.move_to_position",
            "semcs.order.form_wedge",
            "semcs.order.form_column",
            "semcs.order.cease_fire",
            "semcs.order.free_fire",
            "semcs.order.attack_that_target"};

    public static String orderKey(int orderOrd) {
        return orderOrd >= 0 && orderOrd < ORDER_KEYS.length
                ? ORDER_KEYS[orderOrd] : "semcs.order.none";
    }

    public static String dutyKey(int dutyMode) {
        return switch (dutyMode) {
            case 1 -> "semcs.duty.guard";
            case 2 -> "semcs.duty.patrol";
            case 4 -> "semcs.duty.post";
            default -> "semcs.duty.route";
        };
    }

    public static final class Ctx {
        ResourceKey<Level> mapDimension;
    }

    public static final class Renderer extends ElementRenderer<UnitMarker, Ctx, Renderer> {

        private Renderer() {
            super(new Ctx(), new Provider(), new Reader());
        }

        @Override
        public void preRender(ElementRenderInfo info, BufferSource buffers,
                              MultiTextureRenderTypeRendererProvider provider, boolean shadow) {
            this.context.mapDimension = info.mapDimension;
            RenderSystem.enableBlend();
        }

        @Override
        public void postRender(ElementRenderInfo info, BufferSource buffers,
                               MultiTextureRenderTypeRendererProvider provider, boolean shadow) {
        }

        @Override
        public void renderElementShadow(UnitMarker marker, boolean hovered, float optionalScale,
                                        double partialX, double partialY, ElementRenderInfo info,
                                        GuiGraphics guiGraphics, BufferSource buffers,
                                        MultiTextureRenderTypeRendererProvider provider) {
            // Nothing: shouldRender refuses the shadow pass entirely.
        }

        @Override
        public boolean renderElement(UnitMarker marker, boolean hovered, double optionalDepth,
                                     float optionalScale, double partialX, double partialY,
                                     ElementRenderInfo info, GuiGraphics guiGraphics,
                                     BufferSource buffers,
                                     MultiTextureRenderTypeRendererProvider provider) {
            PoseStack pose = guiGraphics.pose();
            pose.pushPose();
            pose.translate(partialX, partialY, optionalDepth);
            pose.scale(optionalScale, optionalScale, 1.0F);

            Minecraft mc = Minecraft.getInstance();
            boolean selected = UnitMarkerStore.isSelected(marker);

            if (selected) {
                // 2px solid-white frame around the icon - four edge bars so
                // the selection reads clearly even at max map zoom-out.
                guiGraphics.fill(-HIT_BOX - 1, -HIT_BOX - 1, HIT_BOX + 1, -HIT_BOX + 1,
                        SELECTION_COLOR);
                guiGraphics.fill(-HIT_BOX - 1, HIT_BOX - 1, HIT_BOX + 1, HIT_BOX + 1,
                        SELECTION_COLOR);
                guiGraphics.fill(-HIT_BOX - 1, -HIT_BOX - 1, -HIT_BOX + 1, HIT_BOX + 1,
                        SELECTION_COLOR);
                guiGraphics.fill(HIT_BOX - 1, -HIT_BOX - 1, HIT_BOX + 1, HIT_BOX + 1,
                        SELECTION_COLOR);
            } else if (hovered) {
                guiGraphics.fill(-HIT_BOX, -HIT_BOX, HIT_BOX, HIT_BOX, SELECTION_SHADE);
                guiGraphics.fill(-HIT_BOX + 1, -HIT_BOX + 1, HIT_BOX - 1, HIT_BOX - 1,
                        SELECTION_SHADE);
            }

            // Allegiance frame then faction fill.
            int frame = switch (marker.allegiance()) {
                case OWN -> FRAME_OWN;
                case FRIENDLY -> FRAME_FRIENDLY;
                case HOSTILE -> FRAME_HOSTILE;
            };
            guiGraphics.fill(-HALF - 1, -HALF - 1, HALF + 1, HALF + 1, frame);
            guiGraphics.fill(-HALF, -HALF, HALF, HALF, 0xFF000000 | marker.rgb());

            // Weapon silhouette, centred - drawn in code so no icon assets
            // are needed and every class reads at map zoom.
            drawWeaponIcon(guiGraphics, marker.classOrd());

            // Duty badge in the top-right corner: a dark chip + one glyph so
            // posted units read at a glance (guard/patrol/route).
            if (marker.dutyMode() != 0) {
                String badge = switch (marker.dutyMode()) {
                    case 1 -> "\u2302";    // house - guard chunk
                    case 2 -> "\u21BB";    // cycle - patrol loop
                    case 4 -> "\u2691";    // flag - guard post
                    default -> "\u2933";   // bend arrow - route duty
                };
                guiGraphics.fill(HALF - 8, -HALF - 1, HALF + 1, -HALF + 8, 0xC0000000);
                guiGraphics.drawString(mc.font, badge, HALF - 7, -HALF,
                        0xFFFFFF55, false);
            }

            // Health bar under the icon.
            int barY = HALF + 2;
            guiGraphics.fill(-HALF, barY, HALF, barY + 2, 0xFF202020);
            int fill = Math.round(HALF * 2 * Math.max(0.0F, Math.min(1.0F, marker.healthFrac())));
            if (fill > 0) {
                int c = marker.healthFrac() > 0.5F ? 0xFF55FF55
                        : marker.healthFrac() > 0.25F ? 0xFFFFFF55 : 0xFFFF5555;
                guiGraphics.fill(-HALF, barY, -HALF + fill, barY + 2, c);
            }

            // Hover card: rank + callsign, faction + class, HP, order, duty.
            // Only while hovered - keeps the map clean.
            if (hovered) {
                List<Component> lines = new ArrayList<>(5);
                String callsign = marker.name() == null || marker.name().isEmpty()
                        ? className(marker.classOrd()) : marker.name();
                lines.add(Component.literal(
                        (marker.rankTitle().isEmpty() ? "" : marker.rankTitle() + " ")
                                + callsign));
                lines.add(Component.literal(factionName(marker.factionOrd()) + " "
                        + className(marker.classOrd())).withStyle(ChatFormatting.GRAY));
                lines.add(Component.translatable("semcs.tip.hp",
                                Math.round(marker.health()),
                                Math.round(marker.maxHealth()))
                        .withStyle(marker.healthFrac() > 0.5F ? ChatFormatting.GREEN
                                : marker.healthFrac() > 0.25F
                                ? ChatFormatting.YELLOW : ChatFormatting.RED));
                lines.add(Component.translatable("semcs.tip.order",
                                Component.translatable(orderKey(marker.orderOrd())))
                        .withStyle(ChatFormatting.AQUA));
                if (marker.dutyMode() != 0) {
                    lines.add(Component.translatable("semcs.tip.duty",
                                    Component.translatable(dutyKey(marker.dutyMode())),
                                    marker.dutyChunkX(), marker.dutyChunkZ())
                            .withStyle(ChatFormatting.GOLD));
                }

                int w = 0;
                for (Component line : lines) {
                    w = Math.max(w, mc.font.width(line));
                }
                int y = barY + 4;
                int h = lines.size() * mc.font.lineHeight + 2;
                // Clamp the card's screen position so it cannot slide off the
                // left/right edge - the marker is at partialX, scaled by
                // optionalScale, so the correction converts back.
                int screenW = mc.getWindow().getGuiScaledWidth();
                double halfW = (w / 2.0 + 3.0) * optionalScale;
                double shiftPx = partialX - halfW < 2 ? 2 - (partialX - halfW)
                        : partialX + halfW > screenW - 2
                        ? screenW - 2 - (partialX + halfW) : 0;
                int left = (int) Math.round(-w / 2.0 - 3.0
                        + shiftPx / Math.max(optionalScale, 0.001F));
                guiGraphics.fill(left, y - 2, left + w + 6, y + h, 0xC0000000);
                int ty = y;
                for (Component line : lines) {
                    guiGraphics.drawString(mc.font, line, left + 3, ty,
                            0xFFFFFFFF, false);
                    ty += mc.font.lineHeight;
                }
            }

            pose.popPose();
            return true;
        }

        // Weapon silhouettes as pixel rects inside the 16x16 icon, drawn
        // left-to-right (stock at -x, muzzle at +x). White on the faction
        // fill with a 1px dark drop-shadow offset so it stays legible on
        // pale faction colours.
        private static void drawWeaponIcon(GuiGraphics g, int classOrd) {
            int ink = 0xFF202020;
            int white = 0xFFF2F2F2;
            g.pose().pushPose();
            g.pose().translate(0.0F, -1.0F, 0.0F);   // optical centre of the icon
            for (int pass = 0; pass < 2; pass++) {
                int color = pass == 0 ? ink : white;
                int ox = pass == 0 ? 1 : 0;          // shadow pass offset
                int oy = pass == 0 ? 1 : 0;
                WeaponClass[] values = WeaponClass.values();
                WeaponClass cls = classOrd >= 0 && classOrd < values.length
                        ? values[classOrd] : null;
                if (cls == null) {
                    break;
                }
                switch (cls) {
                    case RIFLE -> {                   // rifle w/ stock + mag
                        gun(g, ox, oy, color, -6, -2, 6, -1);   // barrel/upper
                        gun(g, ox, oy, color, -3, -1, 3, 0);    // receiver
                        gun(g, ox, oy, color, -6, 0, -4, 2);    // stock
                        gun(g, ox, oy, color, 0, 0, 1, 3);      // magazine
                    }
                    case SMG -> {                     // compact, big mag
                        gun(g, ox, oy, color, -4, -2, 4, -1);
                        gun(g, ox, oy, color, -2, -1, 2, 0);
                        gun(g, ox, oy, color, 0, 0, 1, 4);      // long magazine
                        gun(g, ox, oy, color, -5, -1, -4, 0);   // stubby stock
                    }
                    case SHOTGUN -> {                 // thick barrel + pump
                        gun(g, ox, oy, color, -6, -3, 5, -1);
                        gun(g, ox, oy, color, 1, 0, 3, 1);      // pump
                        gun(g, ox, oy, color, -3, 0, 3, 1);
                        gun(g, ox, oy, color, -6, 0, -4, 2);    // stock
                    }
                    case SNIPER -> {                  // long barrel + scope
                        gun(g, ox, oy, color, -7, -2, 7, -1);
                        gun(g, ox, oy, color, -1, -4, 1, -3);   // scope
                        gun(g, ox, oy, color, -3, -1, 3, 0);
                        gun(g, ox, oy, color, -6, 0, -4, 2);
                    }
                    case HEAVY_MG -> {                // fat receiver + box mag
                        gun(g, ox, oy, color, -4, -1, 3, 1);
                        gun(g, ox, oy, color, 3, -2, 6, -1);    // barrel
                        gun(g, ox, oy, color, -1, 1, 1, 4);     // ammo box
                        gun(g, ox, oy, color, -6, 0, -5, 2);    // stock
                    }
                    case HEAVY -> {                   // launcher tube
                        gun(g, ox, oy, color, -6, -3, 6, -1);
                        gun(g, ox, oy, color, 0, 0, 1, 2);      // grip
                        gun(g, ox, oy, color, -2, 0, -1, 1);    // trigger
                    }
                    case JACK -> {                    // short carbine
                        gun(g, ox, oy, color, -5, -2, 5, -1);
                        gun(g, ox, oy, color, -2, -1, 2, 0);
                        gun(g, ox, oy, color, 0, 0, 1, 2);
                    }
                    case MEDIC -> {                   // medkit cross, not a gun
                        gun(g, ox, oy, color, -1, -4, 1, 3);
                        gun(g, ox, oy, color, -4, -1, 3, 1);
                    }
                    case PISTOL -> {                  // slide + grip L
                        gun(g, ox, oy, color, -3, -2, 3, -1);
                        gun(g, ox, oy, color, -2, -1, 0, 2);
                    }
                }
            }
            g.pose().popPose();
        }

        private static void gun(GuiGraphics g, int ox, int oy, int color,
                                int x1, int y1, int x2, int y2) {
            g.fill(x1 + ox, y1 + oy, x2 + 1 + ox, y2 + 1 + oy, color);
        }

        @Override
        public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
            return !shadow;
        }

        /** Above Xaero's tracked players (200) - your squad is why the map is open. */
        @Override
        public int getOrder() {
            return 300;
        }
    }

    private static final class Provider extends ElementRenderProvider<UnitMarker, Ctx> {
        private Iterator<UnitMarker> iterator;

        @Override
        public void begin(ElementRenderLocation location, Ctx context) {
            this.iterator = UnitMarkerStore.markers().iterator();
        }

        @Override
        public boolean hasNext(ElementRenderLocation location, Ctx context) {
            return this.iterator != null && this.iterator.hasNext();
        }

        @Override
        public UnitMarker getNext(ElementRenderLocation location, Ctx context) {
            return this.iterator.next();
        }

        @Override
        public void end(ElementRenderLocation location, Ctx context) {
            this.iterator = null;
        }
    }

    private static final class Reader extends ElementReader<UnitMarker, Ctx, Renderer> {

        // Only draw on the marker's own dimension's map.
        @Override
        public boolean isHidden(UnitMarker marker, Ctx context) {
            return context.mapDimension != null
                    && !context.mapDimension.location().toString().equals(marker.dimension());
        }

        @Override
        public double getRenderX(UnitMarker marker, Ctx context, float partialTicks) {
            return marker.x();
        }

        @Override
        public double getRenderY(UnitMarker marker, Ctx context, float partialTicks) {
            return marker.y();
        }

        @Override
        public double getRenderZ(UnitMarker marker, Ctx context, float partialTicks) {
            return marker.z();
        }

        @Override
        public boolean hasYCoordinate() {
            return true;
        }

        @Override
        public boolean isInteractable(ElementRenderLocation location, UnitMarker marker) {
            return true;
        }

        // Right-click falls through to the map's own menu - that is where the
        // squad orders live (MixinSemcsGuiMap.getRightClickOptions).
        @Override
        public boolean isRightClickValid(UnitMarker marker) {
            return false;
        }

        @Override
        public int getInteractionBoxLeft(UnitMarker marker, Ctx context, float partialTicks) {
            return -HIT_BOX;
        }

        @Override
        public int getInteractionBoxRight(UnitMarker marker, Ctx context, float partialTicks) {
            return HIT_BOX;
        }

        @Override
        public int getInteractionBoxTop(UnitMarker marker, Ctx context, float partialTicks) {
            return -HIT_BOX;
        }

        @Override
        public int getInteractionBoxBottom(UnitMarker marker, Ctx context, float partialTicks) {
            return HIT_BOX;
        }

        @Override
        public int getRenderBoxLeft(UnitMarker marker, Ctx context, float partialTicks) {
            return -RENDER_BOX - 70;   // the hover card can be wider than the icon
        }

        @Override
        public int getRenderBoxRight(UnitMarker marker, Ctx context, float partialTicks) {
            return RENDER_BOX + 70;
        }

        @Override
        public int getRenderBoxTop(UnitMarker marker, Ctx context, float partialTicks) {
            return -RENDER_BOX;
        }

        @Override
        public int getRenderBoxBottom(UnitMarker marker, Ctx context, float partialTicks) {
            return RENDER_BOX + 64;    // health bar + five-line hover card
        }

        @Override
        public int getLeftSideLength(UnitMarker marker, Minecraft mc) {
            return 9 + mc.font.width(getMenuName(marker));
        }

        @Override
        public String getMenuName(UnitMarker marker) {
            return marker.name() == null || marker.name().isEmpty()
                    ? classGlyph(marker.classOrd()) : marker.name();
        }

        @Override
        public String getFilterName(UnitMarker marker) {
            return getMenuName(marker);
        }

        @Override
        public int getMenuTextFillLeftPadding(UnitMarker marker) {
            return 0;
        }

        @Override
        public int getRightClickTitleBackgroundColor(UnitMarker marker) {
            return 0xFF000000 | marker.rgb();
        }

        @Override
        public boolean shouldScaleBoxWithOptionalScale() {
            return true;
        }
    }
}
