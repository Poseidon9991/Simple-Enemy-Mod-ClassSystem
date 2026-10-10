// Mixin package - renders the class badge chip in the unit equipment GUI
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.nekoyuni.SimpleEnemyMod.client.gui.screens.PmcUnitScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The menu title already carries the class ("BHQ Unit Equipment - Camper",
// set server-side by MixinPmcUnitMenuTitle) - so the badge parses it back
// out and draws a coloured chip at the panel's top-right. No data sync
// needed: the title string is the single source of truth.
// Only the screen class (SEM) is patched; leftPos/topPos/imageWidth are
// vanilla AbstractContainerScreen fields - remap stays default so the
// refmap handles them.
@Mixin(PmcUnitScreen.class)
public abstract class MixinPmcUnitScreenBadge extends AbstractContainerScreen {

    protected MixinPmcUnitScreenBadge() {
        super(null, null, null);   // mixin stub ctor - never actually runs
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void semcs$classBadge(GuiGraphics graphics, int mouseX, int mouseY,
                                  float partialTick, CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        String title = self.getTitle().getString();
        int dash = title.lastIndexOf(" - ");
        if (dash < 0) {
            return;   // unclassed unit - nothing to badge
        }
        String clsName = title.substring(dash + 3);
        // resolve the accent colour through the nickname table so a renamed
        // class still colours correctly
        int accent = 0xFFFFFF;
        for (WeaponClass cls : WeaponClass.values()) {
            if (ClassSystemConfig.classNickname(cls).equals(clsName)) {
                accent = SemcsRegistries.classAccent(cls);
                break;
            }
        }
        var font = Minecraft.getInstance().font;
        int w = font.width(clsName) + 8;
        int x = this.leftPos + this.imageWidth - w - 8;
        int y = this.topPos + 5;
        graphics.fill(x - 1, y - 1, x + w + 1, y + 11, 0xFF000000 | accent);
        graphics.fill(x, y, x + w, y + 10, 0xCC1A1A1E);
        graphics.drawString(font, clsName, x + 4, y + 1, 0xFFFFFF, false);
    }
}
