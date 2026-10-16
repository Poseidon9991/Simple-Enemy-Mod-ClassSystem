// Client package - tiny UI sound helper shared by tablet / post / map UI
package com.poseidon.sem_classsystem.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

// Central click feedback so every custom UI (tablet apps, guard post
// screen, map marker selection + orders) sounds identical.
public final class UiSounds {

    private UiSounds() {
    }

    // Neutral UI tick - rows, toggles, app icons.
    public static void click() {
        play(1.0F);
    }

    // Higher pitch for committed actions (order issued, unit teleported).
    public static void confirm() {
        play(1.25F);
    }

    private static void play(float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() != null) {
            mc.getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, pitch));
        }
    }
}
