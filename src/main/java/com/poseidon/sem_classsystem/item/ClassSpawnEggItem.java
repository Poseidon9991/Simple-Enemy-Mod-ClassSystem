// Item package - spawn eggs that lock a unit to a specific weapon class
package com.poseidon.sem_classsystem.item;

import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.nekoyuni.SimpleEnemyMod.entity.ai.roles.utils.UnitRole;
import net.nekoyuni.SimpleEnemyMod.item.RoleSpawnEggItem;

import java.util.Objects;
import java.util.function.Supplier;

// A RoleSpawnEggItem that also bakes the weapon class into the spawned
// entity's NBT. SEM's own useOn builds a fresh EntityTag holding only
// UnitRole, so pre-stamping the stack doesn't work - we override useOn and
// write BOTH keys into the same tag:
//   EntityTag.UnitRole           -> FRIENDLY_DEFAULT (same as SEM eggs)
//   EntityTag.semcs_weapon_class -> our class
// EntityType.spawn() applies that tag via entity.load() BEFORE
// finalizeSpawn, so MixinAbstractUnit's restore runs first, getOrAssign
// returns this class, and the equipper picks from the class table.
public class ClassSpawnEggItem extends RoleSpawnEggItem {

    private final WeaponClass weaponClass;

    public ClassSpawnEggItem(Supplier<? extends EntityType<? extends Mob>> typeIn,
                             int primaryColorIn, int secondaryColorIn, WeaponClass weaponClass) {
        super(typeIn, primaryColorIn, secondaryColorIn,
                new Properties().stacksTo(64), UnitRole.FRIENDLY_DEFAULT);
        this.weaponClass = weaponClass;
    }

    @Override
    public InteractionResult useOn(UseOnContext pContext) {
        Level level = pContext.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        ItemStack itemstack = pContext.getItemInHand();
        BlockPos blockpos = pContext.getClickedPos();
        Direction direction = pContext.getClickedFace();
        BlockState blockstate = level.getBlockState(blockpos);

        BlockPos finalSpawnPos = blockpos;
        if (blockstate.getCollisionShape(level, blockpos).isEmpty()) {
            finalSpawnPos = blockpos.relative(direction);
        }

        CompoundTag itemTag = itemstack.getOrCreateTag();
        CompoundTag entityData = new CompoundTag();
        entityData.putString("UnitRole", UnitRole.FRIENDLY_DEFAULT.name());
        entityData.putString(WeaponClassSupport.NBT_KEY, weaponClass.name());
        itemTag.put("EntityTag", entityData);

        EntityType<?> entitytype = this.getType(itemstack.getTag());
        Entity entity = entitytype.spawn(
                (ServerLevel) level,
                itemstack,
                pContext.getPlayer(),
                finalSpawnPos,
                MobSpawnType.SPAWN_EGG,
                true,
                !Objects.equals(blockpos, pContext.getClickedPos())
        );

        itemTag.remove("EntityTag");

        if (entity != null) {
            itemstack.shrink(1);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    // Vanilla use() covers water/fluid spawning - it reads the stack's own
    // EntityTag, so stamping the class just before super is enough there.
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        player.getItemInHand(hand).getOrCreateTagElement("EntityTag")
                .putString(WeaponClassSupport.NBT_KEY, weaponClass.name());
        return super.use(level, player, hand);
    }
}
