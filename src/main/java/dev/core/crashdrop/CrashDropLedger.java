package dev.core.crashdrop;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistent server-side custody of wagers, unclaimed payouts and fractional items.
 * Every item key includes all data components; a renamed or enchanted item never shares a balance
 * with an otherwise identical ordinary item. All access is on the server thread.
 */
public final class CrashDropLedger extends SavedData {
    private static final Codec<Long> NON_NEGATIVE_LONG = Codec.LONG.validate(value -> value >= 0
            ? DataResult.success(value) : DataResult.error(() -> "Expected a non-negative item balance"));
    public static final Codec<CrashDropLedger> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            NON_NEGATIVE_LONG.optionalFieldOf("round_id", 0L)
                    .forGetter(ledger -> ledger.roundId),
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Account.CODEC).optionalFieldOf("accounts", Map.of())
                    .forGetter(ledger -> ledger.accounts)
    ).apply(instance, CrashDropLedger::new));

    private static final SavedDataType<CrashDropLedger> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("crashdrop", "ledger"), CrashDropLedger::new,
            CODEC, DataFixTypes.LEVEL);

    private final Map<UUID, Account> accounts;
    private long roundId;

    public CrashDropLedger() {
        this(0L, Map.of());
    }

    private CrashDropLedger(long roundId, Map<UUID, Account> accounts) {
        this.roundId = roundId;
        this.accounts = new HashMap<>(accounts);
    }

    public static CrashDropLedger load(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** A saved, monotonically increasing ID prevents stale actions matching a later round. */
    public long nextRoundId() {
        long next = Math.incrementExact(roundId);
        roundId = next;
        setDirty();
        return next;
    }

    public boolean hasEscrow(UUID playerId) {
        Account account = accounts.get(playerId);
        return account != null && !account.escrow.isEmpty();
    }

    /** Returns an independent snapshot; callers cannot mutate the held wager. */
    public ItemStack escrow(UUID playerId) {
        Account account = accounts.get(playerId);
        return account == null ? ItemStack.EMPTY : account.escrow.copy();
    }

    /** The caller transfers these items out of the menu exactly once before accepting the wager. */
    public void hold(UUID playerId, ItemStack stake) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(stake, "stake");
        if (stake.isEmpty() || stake.getCount() < 1) {
            throw new IllegalArgumentException("An empty wager cannot be held");
        }
        if (hasEscrow(playerId)) throw new IllegalStateException("This player already has a wager");
        Account account = accounts.computeIfAbsent(playerId, ignored -> new Account());
        account.escrow = stake.copy();
        setDirty();
    }

    /** A failed round consumes only its wager, never fractions earned in earlier successful rounds. */
    public void lose(UUID playerId) {
        Account account = accounts.get(playerId);
        if (account == null || account.escrow.isEmpty()) return;
        account.escrow = ItemStack.EMPTY;
        removeEmptyAccount(playerId, account);
        setDirty();
    }

    /**
     * Consumes one wager and records its complete payout. Repeated settlement is rejected.
     * Arithmetic and payout-limit checks run before any held item or balance changes.
     */
    public RoundMath.Payout settle(UUID playerId, int multiplierCents, int maxPayoutItems) {
        Account account = accounts.get(playerId);
        if (account == null || account.escrow.isEmpty()) {
            throw new IllegalStateException("This player has no wager to settle");
        }
        ItemStack stake = account.escrow;
        Balance balance = account.find(stake);
        int priorRemainder = balance == null ? 0 : balance.remainder;
        RoundMath.Payout payout = RoundMath.payoutWithRemainder(stake.getCount(), multiplierCents,
                priorRemainder, maxPayoutItems);
        long pending = Math.addExact(balance == null ? 0L : balance.pending, payout.items());

        if (balance == null) {
            balance = new Balance(stake, pending, payout.remainderCents());
            account.balances.add(balance);
        } else {
            balance.pending = pending;
            balance.remainder = payout.remainderCents();
        }
        account.escrow = ItemStack.EMPTY;
        setDirty();
        return payout;
    }

    /**
     * A server restart cannot resume a hidden running round. Refund every unresolved wager into
     * persistent pending items; no player needs to be connected and no item is dropped in the world.
     */
    public void recoverEscrow() {
        // Check every addition first, so an invalid oversized saved balance cannot partially recover.
        boolean changed = false;
        for (Account account : accounts.values()) {
            if (account.escrow.isEmpty()) continue;
            Balance balance = account.find(account.escrow);
            Math.addExact(balance == null ? 0L : balance.pending, account.escrow.getCount());
            changed = true;
        }
        if (!changed) return;
        for (Account account : accounts.values()) {
            if (account.escrow.isEmpty()) continue;
            Balance balance = account.find(account.escrow);
            if (balance == null) {
                account.balances.add(new Balance(account.escrow, account.escrow.getCount(), 0));
            } else {
                balance.pending += account.escrow.getCount();
            }
            account.escrow = ItemStack.EMPTY;
        }
        setDirty();
    }

    public int remainder(UUID playerId, ItemStack item) {
        Account account = accounts.get(playerId);
        Balance balance = account == null ? null : account.find(item);
        return balance == null ? 0 : balance.remainder;
    }

    /** UI count, saturated at the int limit; the persisted balance itself uses long counts. */
    public int pendingCount(UUID playerId) {
        Account account = accounts.get(playerId);
        if (account == null) return 0;
        long total = 0;
        for (Balance balance : account.balances) {
            total += Math.min(balance.pending, Integer.MAX_VALUE - total);
            if (total == Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) total;
    }

    public int pendingCount(UUID playerId, ItemStack item) {
        Account account = accounts.get(playerId);
        Balance balance = account == null ? null : account.find(item);
        return balance == null ? 0 : (int) Math.min(balance.pending, Integer.MAX_VALUE);
    }

    /**
     * Moves only items that actually fit into the player's 36 normal inventory slots. Remainders
     * stay saved when the inventory is full. In particular, Inventory.add's creative-mode deletion
     * and placeItemBackInInventory's ground-drop fallback are deliberately not used here.
     */
    public int claim(ServerPlayer player) {
        UUID playerId = player.getUUID();
        Account account = accounts.get(playerId);
        if (account == null) return 0;
        Inventory inventory = player.getInventory();
        int claimed = 0;
        int slots = Math.min(Inventory.INVENTORY_SIZE, inventory.getNonEquipmentItems().size());

        for (Iterator<Balance> iterator = account.balances.iterator(); iterator.hasNext();) {
            Balance balance = iterator.next();
            int maximum = Math.min(balance.prototype.getMaxStackSize(),
                    inventory.getMaxStackSize(balance.prototype));
            // Fill matching partial stacks before taking any empty inventory slots.
            for (int pass = 0; pass < 2 && balance.pending > 0; pass++) {
                for (int slot = 0; slot < slots && balance.pending > 0; slot++) {
                    ItemStack current = inventory.getItem(slot);
                    if (!inventory.canPlaceItem(slot, balance.prototype)) continue;
                    if (pass == 0 && (current.isEmpty()
                            || !ItemStack.isSameItemSameComponents(current, balance.prototype))) continue;
                    if (pass == 1 && !current.isEmpty()) continue;
                    int capacity = maximum - (current.isEmpty() ? 0 : current.getCount());
                    if (capacity <= 0) continue;
                    int moved = (int) Math.min(balance.pending, capacity);
                    ItemStack replacement = balance.prototype.copyWithCount(
                            (current.isEmpty() ? 0 : current.getCount()) + moved);
                    inventory.setItem(slot, replacement);
                    balance.pending -= moved;
                    claimed = Math.addExact(claimed, moved);
                }
            }
            if (balance.pending == 0 && balance.remainder == 0) iterator.remove();
        }
        if (claimed > 0) {
            setDirty();
            inventory.setChanged();
            player.inventoryMenu.broadcastChanges();
            if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
        }
        removeEmptyAccount(playerId, account);
        return claimed;
    }

    private void removeEmptyAccount(UUID playerId, Account account) {
        if (account.escrow.isEmpty() && account.balances.isEmpty()) accounts.remove(playerId);
    }

    private static final class Account {
        private static final Codec<Account> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ItemStack.OPTIONAL_CODEC.optionalFieldOf("escrow", ItemStack.EMPTY)
                        .forGetter(account -> account.escrow),
                Balance.CODEC.listOf().optionalFieldOf("balances", List.of())
                        .forGetter(account -> account.balances)
        ).apply(instance, Account::new));

        private ItemStack escrow;
        private final List<Balance> balances;

        private Account() {
            this(ItemStack.EMPTY, List.of());
        }

        private Account(ItemStack escrow, List<Balance> balances) {
            this.escrow = escrow.copy();
            this.balances = new ArrayList<>(balances);
        }

        private Balance find(ItemStack item) {
            if (item.isEmpty()) return null;
            for (Balance balance : balances) {
                if (ItemStack.isSameItemSameComponents(balance.prototype, item)) return balance;
            }
            return null;
        }
    }

    private static final class Balance {
        private static final Codec<Balance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ItemStack.CODEC.fieldOf("item").forGetter(balance -> balance.prototype),
                NON_NEGATIVE_LONG.optionalFieldOf("pending", 0L)
                        .forGetter(balance -> balance.pending),
                Codec.intRange(0, RoundMath.ONE - 1).optionalFieldOf("remainder", 0)
                        .forGetter(balance -> balance.remainder)
        ).apply(instance, Balance::new));

        private final ItemStack prototype;
        private long pending;
        private int remainder;

        private Balance(ItemStack prototype, long pending, int remainder) {
            this.prototype = prototype.copyWithCount(1);
            this.pending = pending;
            this.remainder = remainder;
        }
    }
}
