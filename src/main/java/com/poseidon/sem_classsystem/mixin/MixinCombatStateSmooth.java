package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.client.UnitProceduralAnimState;
import com.tacz.guns.api.item.nbt.GunItemDataAccessor;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.nekoyuni.SimpleEnemyMod.entity.client.animation.procedural.CombatStateLayer;
import net.nekoyuni.SimpleEnemyMod.entity.unit.AbstractUnit;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Smoothly blends between low-ready patrol stance and high-ready combat stance
// instead of snapping in 1 frame when unit.isInCombat() flips.
@Mixin(CombatStateLayer.class)
public abstract class MixinCombatStateSmooth {

    @Shadow(remap = false) @Final private ModelPart rightArm;
    @Shadow(remap = false) @Final private ModelPart leftArm;

    @Inject(method = "applyTransformations", at = @At("HEAD"), cancellable = true, remap = false)
    private void semcs$blendCombatStance(ModelPart root, Entity entity, float ageInTicks, CallbackInfo ci) {
        if (!(entity instanceof AbstractUnit unit)) {
            ci.cancel();
            return;
        }

        ItemStack stack = unit.getMainHandItem();
        if (!(stack.getItem() instanceof GunItemDataAccessor gunItem)) {
            ci.cancel();
            return;
        }

        ResourceLocation gunId = gunItem.getGunId(stack);
        if (gunId != null && gunId.getPath().contains("minigun")) {
            ci.cancel();
            return;
        }

        UnitProceduralAnimState state = UnitProceduralAnimState.get(unit);
        state.update(unit, ageInTicks);
        float b = state.smoothCombatBlend();

        if (b < 0.999F) {
            this.rightArm.xRot = Mth.lerp(b, -0.95F, this.rightArm.xRot);
            this.rightArm.yRot = Mth.lerp(b, 0.00F, this.rightArm.yRot);
            this.rightArm.zRot = Mth.lerp(b, 0.00F, this.rightArm.zRot);

            this.leftArm.xRot = Mth.lerp(b, -0.25F, this.leftArm.xRot);
            this.leftArm.yRot = Mth.lerp(b, 0.50F, this.leftArm.yRot);
            this.leftArm.zRot = Mth.lerp(b, 0.50F, this.leftArm.zRot);
        }
        ci.cancel();
    }
}
