// Mixin package - renders unit armor at player proportions
package com.poseidon.sem_classsystem.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.nekoyuni.SimpleEnemyMod.entity.client.util.UniversalArmorLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// SEM inflates and hand-offsets armor parts on units (head x1.28, body
// ~x2, legs/boots ~x2.1) - tuned so flat vanilla shells read as chunky
// gear, but the result is a bloated blob instead of how armor looks on
// a real player. Part poses are already synced to the unit skeleton by
// syncArmorToEntity, so skipping both passes entirely renders armor at
// player proportions: 1.0 scale, no limb offsets.
@Mixin(UniversalArmorLayer.class)
public abstract class MixinUniversalArmorLayer {

    // Unit skin + its painted-gear overlay sits proud of a 1.0 armor shell,
    // so flat armor disappears inside the body. A mild uniform bump clears
    // the skin without SEM's bloated ~x2 inflation. Boots still scale past
    // leggings (SEM: 2.15 vs 2.1) or the legging piece swallows them.
    private static final float ARMOR_SCALE = 1.5F;
    private static final float BOOT_SCALE = 1.56F;

    @Inject(method = "applyManualScale", at = @At("HEAD"), cancellable = true, remap = false)
    private void semcs$playerScaleArmor(HumanoidModel<LivingEntity> armorModel,
                                        EquipmentSlot slot, CallbackInfo ci) {
        // Scale persists on the shared baked model across renders - snap
        // it back to ours in case anything else wrote it. Part sets follow
        // SEM's per-slot layout, just at our proportions.
        for (ModelPart part : new ModelPart[]{armorModel.head, armorModel.hat,
                armorModel.body, armorModel.rightArm, armorModel.leftArm}) {
            part.xScale = part.yScale = part.zScale = ARMOR_SCALE;
        }
        float legScale = slot == EquipmentSlot.FEET ? BOOT_SCALE : ARMOR_SCALE;
        armorModel.rightLeg.xScale = armorModel.rightLeg.yScale = armorModel.rightLeg.zScale = legScale;
        armorModel.leftLeg.xScale = armorModel.leftLeg.yScale = armorModel.leftLeg.zScale = legScale;
        ci.cancel();
    }

    @Inject(method = "applyManualPosition", at = @At("HEAD"), cancellable = true, remap = false)
    private void semcs$playerPositionArmor(HumanoidModel<LivingEntity> armorModel,
                                           EquipmentSlot slot, CallbackInfo ci) {
        ci.cancel();
    }
}
