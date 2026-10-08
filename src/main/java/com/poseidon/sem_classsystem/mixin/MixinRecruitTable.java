// Mixin package - patches SEM's Recruit Table block for class selection
package com.poseidon.sem_classsystem.mixin;

// Our bridge interface injected onto every SEM unit
import com.poseidon.sem_classsystem.bridge.IClassifiedUnit;
// Preference storage helpers (player persistent NBT)
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
// Vanilla types used by the block's use() signature
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
// SEM's recruit table block - THE CLASS WE PATCH
import net.nekoyuni.SimpleEnemyMod.block.RecruitTableBlock;
// Mixin annotations:
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Adds class selection to SEM's Recruit Table:
//   sneak + right-click = cycle your preferred class (random -> smg -> ... -> random)
//   normal right-click  = recruit a PMC of the preferred class
// The preference lives in the player's persistent NBT, so it survives
// death and relog. Never set a preference? Recruits stay fully random.
@Mixin(RecruitTableBlock.class)
public abstract class MixinRecruitTable {

    // HEAD of use(): intercept sneak-clicks BEFORE the recruit logic runs,
    // so cycling never consumes the recruit item or spawns a unit.
    // remap stays default (true): "use" is a vanilla block method, the
    // refmap translates it to the obfuscated name in production.
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void semcs$cycleOnSneak(BlockState state, Level level, BlockPos pos, Player player,
                                    InteractionHand hand, BlockHitResult hit,
                                    CallbackInfoReturnable<InteractionResult> cir) {
        // Client side does nothing - original returns SUCCESS there anyway
        if (level.isClientSide) {
            return;
        }
        // Only a sneaking player triggers the class picker
        if (!player.isShiftKeyDown()) {
            return;
        }
        // Step to the next preference and tell the player what is selected
        WeaponClass next = WeaponClassSupport.cycleRecruitPreference(player);
        String label = next == null ? "Random" : next.defaultNickname();
        player.displayClientMessage(
                Component.literal("Recruit class: " + label), true);   // actionbar
        // Consume the click - nothing is recruited this time
        cir.setReturnValue(InteractionResult.SUCCESS);
    }

    // @Redirect on the ModEntities.PMCUNIT.get().create(level) call inside
    // use(): we set the preferred class on the freshly-created unit BEFORE
    // SEM calls finalizeSpawn on it. Our finalizeSpawn HEAD injector keeps
    // an already-set class, so the recruit equips, tunes and names itself
    // for the chosen class - and the random roll never happens.
    //
    // We target EntityType.create instead of unit.finalizeSpawn because
    // finalizeSpawn is only DECLARED on Mob/AbstractUnit - the bytecode here
    // invokes it through PmcUnitEntity, which carries no SRG mapping of its
    // own and the redirect would not remap in production.
    //
    // Redirect signature for an INSTANCE call inside an instance method:
    //   (owner instance, call args...) + (enclosing method args...)
    @Redirect(method = "use",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/EntityType;create(Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/entity/Entity;"))
    private net.minecraft.world.entity.Entity semcs$recruitPreferredClass(
            net.minecraft.world.entity.EntityType<?> entityType, Level level,
            BlockState state, Level levelRef, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        // SEM's original call: create the PMC entity
        net.minecraft.world.entity.Entity unit = entityType.create(level);
        // Pre-set the player's preferred class if they picked one
        WeaponClass pref = WeaponClassSupport.getRecruitPreference(player);
        if (pref != null && unit instanceof IClassifiedUnit classified) {
            classified.semcs$setWeaponClass(pref);
        }
        return unit;
    }
}
