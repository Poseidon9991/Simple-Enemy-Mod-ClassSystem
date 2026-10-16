package com.poseidon.sem_classsystem.menu;

import com.poseidon.sem_classsystem.bridge.IClassifiedUnit;
import com.poseidon.sem_classsystem.config.ClassSystemConfig;
import com.poseidon.sem_classsystem.event.UnitOwnershipCap;
import com.poseidon.sem_classsystem.faction.WeaponClass;
import com.poseidon.sem_classsystem.faction.WeaponClassSupport;
import com.poseidon.sem_classsystem.registry.SemcsRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraftforge.network.NetworkHooks;
import net.nekoyuni.SimpleEnemyMod.config.common.MiscConfig;
import net.nekoyuni.SimpleEnemyMod.entity.unit.PmcUnitEntity;
import net.nekoyuni.SimpleEnemyMod.registry.ModEntities;

// Container menu for both the PMC Recruit Table and BHQ Recruit Table.
// Lets the player visually pick which WeaponClass (or Random) to recruit
// and pays the recruitment cost from anywhere in the player's inventory.
public class RecruitTableMenu extends AbstractContainerMenu {

    private final BlockPos tablePos;
    private final boolean isBhq;
    private final DataSlot selectedIndex = DataSlot.standalone();
    private final DataSlot recruitPriceSlot = DataSlot.standalone();

    public RecruitTableMenu(int containerId, Inventory playerInv, FriendlyByteBuf extraData) {
        this(containerId, playerInv, extraData.readBlockPos(), extraData.readBoolean());
    }

    public RecruitTableMenu(int containerId, Inventory playerInv, BlockPos tablePos, boolean isBhq) {
        super(SemcsRegistries.RECRUIT_TABLE_MENU.get(), containerId);
        this.tablePos = tablePos;
        this.isBhq = isBhq;
        this.addDataSlot(this.selectedIndex);
        this.addDataSlot(this.recruitPriceSlot);

        WeaponClass currentPref = WeaponClassSupport.getRecruitPreference(playerInv.player);
        this.selectedIndex.set(indexOfClass(currentPref));
        this.recruitPriceSlot.set(computePrice(isBhq));
    }

    public static void open(ServerPlayer player, BlockPos pos, boolean isBhq) {
        Component title = Component.literal(isBhq ? "BHQ Recruitment Table" : "PMC Recruitment Table");
        NetworkHooks.openScreen(
                player,
                new SimpleMenuProvider((id, inv, p) -> new RecruitTableMenu(id, inv, pos, isBhq), title),
                buf -> {
                    buf.writeBlockPos(pos);
                    buf.writeBoolean(isBhq);
                }
        );
    }

    private static int computePrice(boolean isBhq) {
        return isBhq ? ClassSystemConfig.bhqRecruitPrice() : MiscConfig.RECRUIT_PRICE.get();
    }

    public boolean isBhq() {
        return this.isBhq;
    }

    public int selectedIndex() {
        return this.selectedIndex.get();
    }

    public int recruitPrice() {
        int synced = this.recruitPriceSlot.get();
        return synced > 0 ? synced : computePrice(this.isBhq);
    }

    public static int indexOfClass(WeaponClass cls) {
        if (cls == null) {
            return 0;
        }
        WeaponClass[] main = WeaponClass.mainClasses();
        for (int i = 0; i < main.length; i++) {
            if (main[i] == cls) {
                return i + 1;
            }
        }
        return 0;
    }

    public static WeaponClass classForIndex(int idx) {
        WeaponClass[] main = WeaponClass.mainClasses();
        if (idx <= 0 || idx > main.length) {
            return null;
        }
        return main[idx - 1];
    }

    // Button IDs:
    //   0..8   -> select class preference (0 = Random, 1..8 = mainClasses()[id - 1])
    //   10..18 -> select AND immediately recruit that class
    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        int cardCount = WeaponClass.mainClasses().length + 1;   // 9
        if (buttonId >= 0 && buttonId < cardCount) {
            WeaponClass chosen = classForIndex(buttonId);
            WeaponClassSupport.setRecruitPreference(player, chosen);
            this.selectedIndex.set(buttonId);
            return true;
        }
        if (buttonId >= 10 && buttonId < 10 + cardCount) {
            int idx = buttonId - 10;
            WeaponClass chosen = classForIndex(idx);
            WeaponClassSupport.setRecruitPreference(player, chosen);
            this.selectedIndex.set(idx);
            if (!player.level().isClientSide) {
                recruitUnit(player, chosen);
            }
            return true;
        }
        return false;
    }

    private void recruitUnit(Player player, WeaponClass chosenClass) {
        Level level = player.level();
        // Squad-size cap BEFORE any payment is taken
        if (player instanceof ServerPlayer sp && UnitOwnershipCap.denyIfAtCap(sp)) {
            return;
        }
        Item requiredItem = MiscConfig.getRecruitItem();
        int requiredAmount = computePrice(this.isBhq);

        boolean free = player.getAbilities().instabuild;
        if (!free) {
            int available = countItemInInventory(player, requiredItem);
            if (available < requiredAmount) {
                String unitLabel = this.isBhq ? "BHQ Operator" : "PMC Unit";
                player.displayClientMessage(
                        Component.literal("You need " + requiredAmount + " "
                                + requiredItem.getDescription().getString()
                                + " to recruit a " + unitLabel + " (have " + available + ")!"),
                        true
                );
                level.playSound(null, this.tablePos, SoundEvents.VILLAGER_NO, SoundSource.BLOCKS, 1.0F, 1.0F);
                return;
            }
        }

        // Create BEFORE taking payment: if the entity fails to instantiate
        // the player must not be charged. A discarded unit is never added
        // to the level, so it simply gets garbage-collected.
        EntityType<? extends PmcUnitEntity> type = this.isBhq
                ? SemcsRegistries.BHQ_UNIT.get()
                : ModEntities.PMCUNIT.get();
        PmcUnitEntity unit = type.create(level);
        if (unit == null) {
            return;
        }
        if (!free) {
            consumeItemFromInventory(player, requiredItem, requiredAmount);
        }

        if (chosenClass != null && unit instanceof IClassifiedUnit classified) {
            classified.semcs$setWeaponClass(chosenClass);
        }

        unit.moveTo(
                this.tablePos.getX() + 0.5D,
                this.tablePos.getY() + 1.0D,
                this.tablePos.getZ() + 0.5D,
                player.getYRot() + 180.0F,
                0.0F
        );

        unit.finalizeSpawn(
                (ServerLevelAccessor) level,
                level.getCurrentDifficultyAt(this.tablePos),
                MobSpawnType.TRIGGERED,
                null,
                null
        );

        unit.setOwner(player.getUUID());
        level.addFreshEntity(unit);

        WeaponClass actualClass = WeaponClassSupport.get(unit);
        String classLabel = actualClass != null ? ClassSystemConfig.classNickname(actualClass) : "Operator";
        String factionLabel = this.isBhq ? "BHQ" : "PMC";
        player.displayClientMessage(
                Component.literal("Recruited " + factionLabel + " " + classLabel + "!"),
                true
        );

        playContractSuccessSound(level, this.tablePos);
    }

    public static int countItemInInventory(Player player, Item item) {
        int total = 0;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void consumeItemFromInventory(Player player, Item item, int amount) {
        int remaining = amount;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
                if (stack.isEmpty()) {
                    inv.setItem(i, ItemStack.EMPTY);
                }
            }
        }
        inv.setChanged();
    }

    private static void playContractSuccessSound(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel) || level.getServer() == null) {
            return;
        }
        serverLevel.sendParticles(
                ParticleTypes.WHITE_ASH,
                pos.getX() + 0.5D, pos.getY() + 1.1D, pos.getZ() + 0.5D,
                10, 0.2D, 0.2D, 0.2D, 0.05D
        );
        level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.getServer().tell(new TickTask(
                level.getServer().getTickCount() + 10, () -> {
            level.playSound(null, pos, SoundEvents.VILLAGER_YES, SoundSource.BLOCKS, 1.0F, 1.0F);
            serverLevel.sendParticles(
                    ParticleTypes.HAPPY_VILLAGER,
                    pos.getX() + 0.5D, pos.getY() + 1.2D, pos.getZ() + 0.5D,
                    15, 0.4D, 0.4D, 0.4D, 0.1D
            );
        }));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(
                this.tablePos.getX() + 0.5D,
                this.tablePos.getY() + 0.5D,
                this.tablePos.getZ() + 0.5D) <= 64.0D;
    }
}
