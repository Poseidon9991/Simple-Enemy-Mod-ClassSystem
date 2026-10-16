package com.poseidon.sem_classsystem.mixin;

import java.util.List;
import java.util.Set;

import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

// Shared body for soft-dep mixin gates. A config that is "required": false
// still QUEUES its mixins and lets Mixin's missing-target tolerance emit a
// warning when the mod is absent - skipping application here means those
// mixins never enter the apply queue.
//
// Uses LoadingModList (not ModList): mixin plugins run before the runtime
// mod list is up. Mixin instantiates the concrete plugin reflectively by
// FQCN, so subclasses keep the implicit public no-arg constructor.
public abstract class AbstractSoftDepMixinPlugin implements IMixinConfigPlugin {

    private Boolean loaded;

    // The gated mod's id, exactly as it appears in mods.toml.
    protected abstract String modId();

    private boolean loaded() {
        if (this.loaded == null) {
            this.loaded = LoadingModList.get().getModFileById(modId()) != null;
        }
        return this.loaded;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return loaded();
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
    }
}
