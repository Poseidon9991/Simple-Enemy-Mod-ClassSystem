package com.poseidon.sem_classsystem.mixin;

// Soft-compat gate for sem_classsystem.xaero.mixins.json - the gated mod is
// Xaero's World Map (Forge id "xaeroworldmap"; Minimap's "xaerominimap" does
// not ship xaero.map.gui.GuiMap).
public final class XaeroSemcsMixinPlugin extends AbstractSoftDepMixinPlugin {

    @Override
    protected String modId() {
        return "xaeroworldmap";
    }
}
