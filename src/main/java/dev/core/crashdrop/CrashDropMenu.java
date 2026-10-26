package dev.core.crashdrop;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** The input slot owns only uncommitted items. Accepted wagers live in the global game ledger. */
public final class CrashDropMenu extends AbstractContainerMenu {
    public static final int BET = 0;
    public static final int WITHDRAW = 1;
    public static final int CLAIM = 2;
    public static final int ACTION_BET = BET;
    public static final int ACTION_WITHDRAW = WITHDRAW;
    public static final int ACTION_CLAIM = CLAIM;

    private final Player owner;
    private boolean initialized;
    private boolean closed;
    private CrashDropSnapshot clientSnapshot;
    private final SimpleContainer input = new SimpleContainer(1) {
        @Override
        public void setChanged() {
            super.setChanged();
            if (initialized) CrashDropMenu.this.slotsChanged(this);
        }
    };

    public CrashDropMenu(int containerId, Inventory inventory) {
        super(CrashDrop.MENU, containerId);
        owner = inventory.player;
        addSlot(new Slot(input, 0, 48, 70) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return snapshot().status() == CrashDropSnapshot.BetStatus.NONE
                        && !stack.isEmpty() && !stack.is(CrashDrop.TERMINAL)
                        && (!(owner instanceof ServerPlayer player)
                        || CrashDrop.game(player.level().getServer()).isAllowedStake(stack.copyWithCount(1)));
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 79 + column * 18, 164 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 79 + column * 18, 222));
        }
        clientSnapshot = new CrashDropSnapshot(0, 0, CrashDropSnapshot.Phase.BETTING, 0,
                CrashDropGame.BETTING_TICKS, 100, 0, ItemStack.EMPTY, CrashDropSnapshot.BetStatus.NONE,
                0, 0, 0, List.of());
        initialized = true;
    }

    public ItemStack getInputStack() {
        return input.getItem(0);
    }

    public CrashDropSnapshot snapshot() {
        if (owner instanceof ServerPlayer player) {
            return CrashDrop.game(player.level().getServer()).snapshotFor(player, getInputStack());
        }
        return clientSnapshot;
    }

    public void acceptSnapshot(CrashDropSnapshot snapshot) {
        if (closed || owner instanceof ServerPlayer || snapshot.roundId() < clientSnapshot.roundId()
                || snapshot.roundId() == clientSnapshot.roundId() && snapshot.serverTick() < clientSnapshot.serverTick()) return;
        clientSnapshot = snapshot;
    }

    public void handleAction(ServerPlayer player, long expectedRound, int action) {
        if (closed || player != owner || player.containerMenu != this || !stillValid(player)) return;
        CrashDropGame game = CrashDrop.game(player.level().getServer());
        if (expectedRound != game.roundId()) return;
        switch (action) {
            case BET -> {
                if (game.placeBet(player, getInputStack())) {
                    input.setItem(0, ItemStack.EMPTY);
                }
            }
            case WITHDRAW -> game.cashOut(player, expectedRound);
            case CLAIM -> game.claim(player);
            default -> { return; }
        }
        broadcastChanges();
        player.inventoryMenu.broadcastChanges();
    }

    @Override
    public boolean stillValid(Player player) {
        return !closed && player == owner && player.isAlive() && !player.isSpectator();
    }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        if (initialized && !closed) broadcastChanges();
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (initialized && !closed && owner instanceof ServerPlayer player) {
            CrashDropNetwork.sendSnapshot(player, containerId, snapshot());
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (!stillValid(player) || slotIndex < 0 || slotIndex >= slots.size()) return ItemStack.EMPTY;
        Slot source = slots.get(slotIndex);
        if (!source.hasItem() || !source.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = source.getItem();
        ItemStack original = stack.copy();
        boolean moved;
        if (slotIndex == 0) {
            moved = moveItemStackTo(stack, 1, 37, true);
        } else {
            moved = getSlot(0).mayPlace(stack) && moveItemStackTo(stack, 0, 1, false);
            if (!moved) {
                moved = slotIndex < 28 ? moveItemStackTo(stack, 28, 37, false)
                        : moveItemStackTo(stack, 1, 28, false);
            }
        }
        if (!moved) return ItemStack.EMPTY;
        if (stack.isEmpty()) source.setByPlayer(ItemStack.EMPTY);
        else source.setChanged();
        source.onTake(player, stack);
        return original;
    }

    @Override
    public void removed(Player player) {
        if (closed) return;
        closed = true;
        super.removed(player);
        if (!player.level().isClientSide()) {
            // Only the uncommitted input is returned. Closing never cashes out or refunds a bet.
            clearContainer(player, input);
        }
    }
}
