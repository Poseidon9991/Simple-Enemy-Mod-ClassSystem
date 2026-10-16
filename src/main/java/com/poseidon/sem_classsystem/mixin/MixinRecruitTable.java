// Mixin package - patches SEM's Recruit Table block for class selection
package com.poseidon.sem_classsystem.mixin;

import com.poseidon.sem_classsystem.bridge.IClassifiedUnit;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.poseidon.sem_classsystem.menu.RecruitTableMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.nekoyuni.SimpleEnemyMod.block.RecruitTableBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Opens the RecruitTableMenu class-selection GUI when right-clicking SEM's
// PMC Recruit Table.
@Mixin(RecruitTableBlock.class)
public abstract class MixinRecruitTable {

    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void semcs$openRecruitGui(BlockState state, Level level, BlockPos pos, Player player,
                                      InteractionHand hand, BlockHitResult hit,
                                      CallbackInfoReturnable<InteractionResult> cir) {
        if (level.isClientSide) {
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            RecruitTableMenu.open(serverPlayer, pos, false);
            cir.setReturnValue(InteractionResult.CONSUME);
        }
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
