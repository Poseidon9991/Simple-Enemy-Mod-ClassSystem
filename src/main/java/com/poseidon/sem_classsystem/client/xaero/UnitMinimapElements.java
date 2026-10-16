// Client.xaero package - unit markers on Xaero's Minimap HUD
package com.poseidon.sem_classsystem.client.xaero;

import com.poseidon.sem_classsystem.client.UnitMarkerStore;
import com.poseidon.sem_classsystem.map.UnitMarker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.hud.minimap.element.render.MinimapElementReader;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.element.render.MinimapElementRenderProvider;
import xaero.hud.minimap.element.render.MinimapElementRenderer;

import java.util.Iterator;

// The minimap twin of UnitMarkerElements: the same server-synced markers as
// small dots on the minimap HUD. Sibling API, smaller contract - the minimap
// element framework (reader/provider/renderer + OVER_MINIMAP handler) is the
// same pattern the world map uses, minus interaction: markers here are
// display-only, no selection, no right-click menu.
public final class UnitMinimapElements {

    private static final int HALF = 4;              // dot half-size
    private static final int RENDER_BOX = 6;

    private static final int FRAME_OWN = 0xFF55FF55;
    private static final int FRAME_FRIENDLY = 0xFFAAAAAA;
    private static final int FRAME_HOSTILE = 0xFFFF3333;

    public static final Renderer INSTANCE = new Renderer();

    private UnitMinimapElements() {
    }

    public static final class Ctx {
        ResourceKey<Level> mapDimension;
    }

    public static final class Renderer extends MinimapElementRenderer<UnitMarker, Ctx> {

        private Renderer() {
            super(new Reader(), new Provider(), new Ctx());
        }

        @Override
        public void preRender(MinimapElementRenderInfo info, BufferSource buffers,
                              MultiTextureRenderTypeRendererProvider provider) {
            // mapDimension is what the minimap currently shows; on the HUD it
            // equals renderEntityDimension (the player's world).
            this.context.mapDimension = info.mapDimension != null
                    ? info.mapDimension : info.renderEntityDimension;
        }

        @Override
        public void postRender(MinimapElementRenderInfo info, BufferSource buffers,
                               MultiTextureRenderTypeRendererProvider provider) {
        }

        // The framework has already translated the pose onto the element's
        // minimap pixel - draw centred on the origin, like Xaero's own
        // tracked-player icons do.
        @Override
        public boolean renderElement(UnitMarker marker, boolean hovered, boolean cave,
                                     double optionalDepth, float optionalScale,
                                     double partialX, double partialY,
                                     MinimapElementRenderInfo info,
                                     GuiGraphics guiGraphics, BufferSource buffers) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0.0D, 0.0D, optionalDepth);
            guiGraphics.pose().scale(Math.max(optionalScale, 0.5F),
                    Math.max(optionalScale, 0.5F), 1.0F);

            int frame = switch (marker.allegiance()) {
                case OWN -> FRAME_OWN;
                case FRIENDLY -> FRAME_FRIENDLY;
                case HOSTILE -> FRAME_HOSTILE;
            };
            guiGraphics.fill(-HALF - 1, -HALF - 1, HALF + 1, HALF + 1, frame);
            guiGraphics.fill(-HALF, -HALF, HALF, HALF, 0xFF000000 | marker.rgb());

            Minecraft mc = Minecraft.getInstance();
            String glyph = UnitMarkerElements.classGlyph(marker.classOrd());
            guiGraphics.drawString(mc.font, glyph,
                    -mc.font.width(glyph) / 2, -4, 0xFFFFFFFF, false);

            guiGraphics.pose().popPose();
            return true;
        }

        // On the minimap HUD only - the fullscreen map has its own renderer.
        @Override
        public boolean shouldRender(MinimapElementRenderLocation location) {
            return location == MinimapElementRenderLocation.OVER_MINIMAP;
        }

        @Override
        public int getOrder() {
            return 100;
        }
    }

    private static final class Provider extends MinimapElementRenderProvider<UnitMarker, Ctx> {
        private Iterator<UnitMarker> iterator;

        @Override
        public void begin(MinimapElementRenderLocation location, Ctx context) {
            this.iterator = UnitMarkerStore.markers().iterator();
        }

        @Override
        public boolean hasNext(MinimapElementRenderLocation location, Ctx context) {
            return this.iterator != null && this.iterator.hasNext();
        }

        @Override
        public UnitMarker getNext(MinimapElementRenderLocation location, Ctx context) {
            return this.iterator.next();
        }

        @Override
        public void end(MinimapElementRenderLocation location, Ctx context) {
            this.iterator = null;
        }
    }

    private static final class Reader extends MinimapElementReader<UnitMarker, Ctx> {

        // Only draw on the marker's own dimension.
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

        // Display-only: no selection, no menus on the minimap.
        @Override
        public boolean isInteractable(MinimapElementRenderLocation location, UnitMarker marker) {
            return false;
        }

        @Override
        public int getInteractionBoxLeft(UnitMarker marker, Ctx context, float partialTicks) {
            return 0;
        }

        @Override
        public int getInteractionBoxRight(UnitMarker marker, Ctx context, float partialTicks) {
            return 0;
        }

        @Override
        public int getInteractionBoxTop(UnitMarker marker, Ctx context, float partialTicks) {
            return 0;
        }

        @Override
        public int getInteractionBoxBottom(UnitMarker marker, Ctx context, float partialTicks) {
            return 0;
        }

        @Override
        public int getRenderBoxLeft(UnitMarker marker, Ctx context, float partialTicks) {
            return -RENDER_BOX;
        }

        @Override
        public int getRenderBoxRight(UnitMarker marker, Ctx context, float partialTicks) {
            return RENDER_BOX;
        }

        @Override
        public int getRenderBoxTop(UnitMarker marker, Ctx context, float partialTicks) {
            return -RENDER_BOX;
        }

        @Override
        public int getRenderBoxBottom(UnitMarker marker, Ctx context, float partialTicks) {
            return RENDER_BOX;
        }

        @Override
        public int getLeftSideLength(UnitMarker marker, Minecraft mc) {
            return 0;
        }

        @Override
        public String getMenuName(UnitMarker marker) {
            return marker.name() == null || marker.name().isEmpty()
                    ? UnitMarkerElements.classGlyph(marker.classOrd()) : marker.name();
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
            return false;
        }
    }
}
