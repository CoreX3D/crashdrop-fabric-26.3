package dev.core.crashdrop.gametest;

import dev.core.crashdrop.CrashDrop;
import dev.core.crashdrop.CrashDropConfig;
import dev.core.crashdrop.CrashDropGame;
import dev.core.crashdrop.CrashDropLedger;
import dev.core.crashdrop.CrashDropMenu;
import dev.core.crashdrop.CrashDropSnapshot;
import dev.core.crashdrop.RoundMath;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.GameType;

/** Native inventory, persistence, and server-boundary regression coverage; no sampled outcomes. */
public final class CrashDropGameTests {
    private static final String EMPTY = "crashdrop:gametest/empty3x3x3";

    @GameTest(structure = EMPTY, maxTicks = 100)
    public void allPlayersShareRoundMultiplierAndCrash(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            ServerPlayer second = f.player();
            check(helper, f.game.placeBet(f.first, new ItemStack(Items.DIAMOND))
                    && f.game.placeBet(second, new ItemStack(Items.EMERALD, 3)), "Both players join one round");
            f.runAt(100, 500);
            CrashDropSnapshot a = f.game.snapshotFor(f.first, ItemStack.EMPTY);
            CrashDropSnapshot b = f.game.snapshotFor(second, ItemStack.EMPTY);
            check(helper, a.roundId() == b.roundId() && a.phase() == CrashDropSnapshot.Phase.RUNNING
                    && a.phase() == b.phase() && a.multiplierCents() == b.multiplierCents(),
                    "Viewers must see exactly the same server round and multiplier");
            f.runAt(firstTickAtLeast(500, f.config), 500);
            a = f.game.snapshotFor(f.first, ItemStack.EMPTY);
            b = f.game.snapshotFor(second, ItemStack.EMPTY);
            check(helper, a.phase() == CrashDropSnapshot.Phase.CRASHED && b.phase() == a.phase()
                    && a.multiplierCents() == 500 && b.multiplierCents() == 500
                    && a.status() == CrashDropSnapshot.BetStatus.LOST && b.status() == a.status(),
                    "The same crash must burn every unresolved player's stake");
            check(helper, f.ledger.pendingCount(f.first.getUUID()) == 0
                    && f.ledger.pendingCount(second.getUUID()) == 0, "A crash creates no refund");
        }
        helper.succeed();
    }

    @GameTest(structure = EMPTY, maxTicks = 100)
    public void nativeMenuConsumesOneBetAndRejectsDuplicateStaleAndLateRequests(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            CrashDropMenu menu = f.menu(f.first);
            menu.getSlot(0).set(new ItemStack(Items.DIAMOND, 7));
            long round = f.game.roundId();
            menu.handleAction(f.first, round - 1, CrashDropMenu.BET);
            check(helper, menu.getInputStack().getCount() == 7 && !f.ledger.hasEscrow(f.first.getUUID()),
                    "A stale round request cannot take the input");
            menu.handleAction(f.first, round, CrashDropMenu.BET);
            menu.handleAction(f.first, round, CrashDropMenu.BET);
            check(helper, menu.getInputStack().isEmpty() && f.ledger.escrow(f.first.getUUID()).getCount() == 7,
                    "A repeated button request cannot wager or consume twice");
            ItemStack secondInput = new ItemStack(Items.EMERALD, 4);
            check(helper, !menu.getSlot(0).mayPlace(secondInput) && secondInput.getCount() == 4,
                    "After acceptance, the native input slot rejects another item without taking anything from its source");
            // A forged direct container mutation must also fail to commit a second wager.
            menu.getSlot(0).set(secondInput);
            menu.handleAction(f.first, round, CrashDropMenu.BET);
            check(helper, menu.getInputStack().getCount() == 4 && f.ledger.escrow(f.first.getUUID()).is(Items.DIAMOND),
                    "Only one accepted bet per player can exist in a round");
            ServerPlayer late = f.player();
            f.runAt(30, 1000);
            ItemStack lateInput = new ItemStack(Items.DIAMOND, 5);
            check(helper, !f.game.placeBet(late, lateInput) && lateInput.getCount() == 5,
                    "A running round must reject late wagers without consuming their items");
        }
        helper.succeed();
    }

    @GameTest(structure = EMPTY, maxTicks = 100)
    public void oneDiamondAtTwoTimesPaysExactlyTwoAndOnlyOnce(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            ItemStack live = new ItemStack(Items.DIAMOND);
            check(helper, f.game.placeBet(f.first, live) && live.isEmpty(), "Accepted wager transfers ownership");
            long round = f.game.roundId();
            f.runAt(firstTickAtLeast(200, f.config), 500);
            check(helper, f.game.snapshotFor(f.first, ItemStack.EMPTY).multiplierCents() == 200,
                    "This fixture cashes out at exactly 2.00x");
            check(helper, f.game.cashOut(f.first, round), "The active wager must cash out");
            check(helper, inventoryCount(f.first, new ItemStack(Items.DIAMOND)) == 2
                    && !f.ledger.hasEscrow(f.first.getUUID()) && f.ledger.pendingCount(f.first.getUUID()) == 0,
                    "The 2x payout includes the initial diamond and is delivered once");
            check(helper, !f.game.cashOut(f.first, round) && !f.game.cashOut(f.first, round - 1)
                    && f.game.claim(f.first) == 0 && inventoryCount(f.first, new ItemStack(Items.DIAMOND)) == 2,
                    "Duplicate, stale and repeated claim actions cannot duplicate the prize");
        }
        helper.succeed();
    }

    @GameTest(structure = EMPTY, maxTicks = 100)
    public void cashoutOnCrashBoundaryResolvesCrashBeforeAcceptingAction(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.game.placeBet(f.first, new ItemStack(Items.DIAMOND));
            long round = f.game.roundId();
            // Do not call tick(): this is a network action arriving before END_SERVER_TICK.
            put(f.game, "crashCents", 200);
            put(f.game, "phaseStartTick", f.now() - CrashDropGame.BETTING_TICKS - firstTickAtLeast(200, f.config));
            check(helper, !f.game.cashOut(f.first, round), "Equality is already a crash, even before the tick callback");
            check(helper, f.game.snapshotFor(f.first, ItemStack.EMPTY).status() == CrashDropSnapshot.BetStatus.LOST
                    && !f.ledger.hasEscrow(f.first.getUUID()) && f.game.claim(f.first) == 0
                    && f.first.getInventory().isEmpty(), "A boundary request loses the stake and creates no payout");
        }
        helper.succeed();
    }

    @GameTest(structure = EMPTY, maxTicks = 100)
    public void fractionalItemsAccumulateForExactComponentsAndSurviveLaterLoss(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            UUID id = f.first.getUUID();
            ItemStack plain = new ItemStack(Items.DIAMOND);
            ItemStack renamed = plain.copy();
            renamed.set(DataComponents.CUSTOM_NAME, Component.literal("different diamond"));
            f.ledger.hold(id, plain);
            var first = f.ledger.settle(id, 180, 6400);
            check(helper, first.items() == 1 && first.remainderCents() == 80 && f.ledger.claim(f.first) == 1,
                    "A 1.80x win pays one diamond and saves exactly 0.80");
            f.ledger.hold(id, renamed);
            f.ledger.settle(id, 150, 6400);
            check(helper, f.ledger.remainder(id, plain) == 80 && f.ledger.remainder(id, renamed) == 50,
                    "Renaming cannot merge independently saved component balances");
            f.ledger.hold(id, plain);
            f.ledger.lose(id);
            check(helper, f.ledger.remainder(id, plain) == 80, "A later lost wager cannot burn an earlier fraction");
            f.ledger.hold(id, plain);
            var second = f.ledger.settle(id, 120, 6400);
            check(helper, second.items() == 2 && second.remainderCents() == 0,
                    "The next successful 1.20x payout completes two exact diamonds with the saved 0.80");
            check(helper, f.ledger.claim(f.first) == 3 && inventoryCount(f.first, plain) == 3
                    && inventoryCount(f.first, renamed) == 1 && f.ledger.claim(f.first) == 0,
                    "Each component-specific pending prize must be delivered exactly once");
        }
        helper.succeed();
    }

    @GameTest(structure = EMPTY, maxTicks = 100)
    public void payoutPreservesEnchantmentsDamageNameAndCustomData(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            ItemStack stake = componentRichHelmet(helper);
            ItemStack expected = stake.copy();
            f.game.placeBet(f.first, stake);
            long round = f.game.roundId();
            f.runAt(firstTickAtLeast(200, f.config), 500);
            check(helper, f.game.cashOut(f.first, round), "A component-rich equipment wager can cash out");
            List<ItemStack> helmets = inventoryStacks(f.first).stream().filter(stack -> stack.is(Items.DIAMOND_HELMET)).toList();
            check(helper, helmets.size() == 2 && helmets.stream().allMatch(stack -> stack.getCount() == 1
                    && ItemStack.isSameItemSameComponents(stack, expected)),
                    "Unstackable prizes retain every original component and split into legal stacks");
            helmets.getFirst().set(DataComponents.CUSTOM_NAME, Component.literal("mutated after claim"));
            check(helper, ItemStack.isSameItemSameComponents(helmets.getLast(), expected),
                    "Each delivered stack must own independent component state");
        }
        helper.succeed();
    }

    @GameTest(structure = EMPTY, maxTicks = 100)
    public void closingReturnsOnlyUncommittedInputOnceAndActiveBetContinues(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            CrashDropMenu uncommitted = f.menu(f.first);
            uncommitted.getSlot(0).set(new ItemStack(Items.DIAMOND, 3));
            uncommitted.removed(f.first);
            uncommitted.removed(f.first);
            check(helper, inventoryCount(f.first, new ItemStack(Items.DIAMOND)) == 3
                    && !f.ledger.hasEscrow(f.first.getUUID()), "Closing returns uncommitted input exactly once");
            ServerPlayer second = f.player();
            CrashDropMenu committed = f.menu(second);
            committed.getSlot(0).set(new ItemStack(Items.EMERALD, 4));
            committed.handleAction(second, f.game.roundId(), CrashDropMenu.BET);
            committed.removed(second);
            committed.removed(second);
            check(helper, second.getInventory().isEmpty() && f.ledger.escrow(second.getUUID()).getCount() == 4,
                    "Closing a committed wager must leave it in server custody, without refunding or cashing out");
            f.runAt(firstTickAtLeast(200, f.config), 200);
            check(helper, !f.ledger.hasEscrow(second.getUUID()) && f.ledger.pendingCount(second.getUUID()) == 0
                    && second.getInventory().isEmpty(), "A closed wager still loses at the shared crash");
        }
        helper.succeed();
    }

    @GameTest(structure = EMPTY, maxTicks = 100)
    public void fullInventoryKeepsPersistentPrizeWithoutGroundDropsAndRetriesOnce(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            for (int slot = 0; slot < 36; slot++) f.first.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            long groundBefore = groundItems(f.first);
            f.game.placeBet(f.first, new ItemStack(Items.DIAMOND, 40));
            long round = f.game.roundId();
            f.runAt(firstTickAtLeast(200, f.config), 500);
            check(helper, f.game.cashOut(f.first, round) && f.ledger.pendingCount(f.first.getUUID()) == 80
                    && f.game.claim(f.first) == 0 && groundItems(f.first) == groundBefore,
                    "A full inventory must retain all 80 items in custody without dropping or deleting prizes");
            f.first.getInventory().setItem(0, ItemStack.EMPTY);
            check(helper, f.game.claim(f.first) == 64 && f.ledger.pendingCount(f.first.getUUID()) == 16,
                    "A retry only moves the amount that actually fits");
            f.first.getInventory().setItem(1, ItemStack.EMPTY);
            check(helper, f.game.claim(f.first) == 16 && f.game.claim(f.first) == 0
                    && inventoryCount(f.first, new ItemStack(Items.DIAMOND)) == 80
                    && groundItems(f.first) == groundBefore, "Subsequent retries deliver the remainder exactly once");
        }
        helper.succeed();
    }

    @GameTest(structure = EMPTY, maxTicks = 100)
    public void offlineStakeStillCrashesAndCannotBeRecoveredAsAnUnfinishedRound(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            check(helper, f.game.placeBet(f.first, new ItemStack(Items.DIAMOND, 9)),
                    "The online player first transfers a real wager into server custody");
            // Native mock players log in normally. Remove through the real server lifecycle path.
            f.server.getPlayerList().remove(f.first);
            check(helper, f.server.getPlayerList().getPlayer(f.first.getUUID()) == null,
                    "The wager owner must be absent from the online player list for this regression");
            check(helper, f.ledger.escrow(f.first.getUUID()).getCount() == 9,
                    "Leaving the server cannot refund or remove an already committed wager");
            f.runAt(firstTickAtLeast(200, f.config), 200);
            f.ledger.recoverEscrow();
            f.game.stop();
            check(helper, !f.ledger.hasEscrow(f.first.getUUID()) && f.ledger.pendingCount(f.first.getUUID()) == 0
                    && f.ledger.claim(f.first) == 0, "Disconnecting cannot turn a completed loss into a restart refund");
        }
        helper.succeed();
    }

    @GameTest(structure = EMPTY, maxTicks = 100)
    public void serverStopRefundsUnresolvedEscrowExactlyOnceAcrossRecovery(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            ItemStack rich = componentRichHelmet(helper);
            f.game.placeBet(f.first, rich.copy());
            f.runAt(40, 1000);
            f.game.stop();
            f.game.stop();
            CrashDropLedger recovered = roundTrip(helper, f.ledger);
            recovered.recoverEscrow();
            recovered.recoverEscrow();
            check(helper, !recovered.hasEscrow(f.first.getUUID()) && recovered.pendingCount(f.first.getUUID()) == 1,
                    "Server stop and repeated restart recovery refund only the unresolved original wager");
            check(helper, recovered.claim(f.first) == 1 && recovered.claim(f.first) == 0
                    && inventoryCount(f.first, rich) == 1, "Recovery preserves components and cannot refund twice");
            check(helper, !f.game.placeBet(f.first, rich.copy()), "A stopped game cannot take any new wager");
        }
        helper.succeed();
    }

    @GameTest(structure = EMPTY, maxTicks = 100)
    public void nativeSavedDataRoundTripRetainsEscrowPendingFractionsAndRoundIds(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            UUID id = f.first.getUUID();
            ItemStack rich = componentRichHelmet(helper);
            f.ledger.hold(id, rich);
            f.ledger.settle(id, 180, 6400);
            f.ledger.hold(id, new ItemStack(Items.EMERALD, 7));
            long previous = f.ledger.nextRoundId();
            CrashDropLedger loaded = roundTrip(helper, f.ledger);
            check(helper, loaded.pendingCount(id, rich) == 1 && loaded.remainder(id, rich) == 80
                    && loaded.escrow(id).is(Items.EMERALD) && loaded.escrow(id).getCount() == 7
                    && loaded.nextRoundId() == previous + 1, "Registry-aware NBT must preserve all durable custody state");
            ItemStack escaped = loaded.escrow(id);
            escaped.setCount(1);
            check(helper, loaded.escrow(id).getCount() == 7, "Escrow snapshots cannot mutate persisted custody");
            loaded.recoverEscrow();
            check(helper, loaded.claim(f.first) == 8 && loaded.claim(f.first) == 0
                    && inventoryCount(f.first, rich) == 1 && loaded.remainder(id, rich) == 80,
                    "Loaded pending items and recovered escrow can be claimed once while the fraction remains saved");
        }
        helper.succeed();
    }

    @GameTest(structure = EMPTY, maxTicks = 100)
    public void runningSnapshotsNeverRevealFutureCrashThresholdOrTime(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.runAt(20, 500);
            CrashDropSnapshot low = f.game.snapshotFor(f.first, ItemStack.EMPTY);
            put(f.game, "crashCents", 9000);
            CrashDropSnapshot high = f.game.snapshotFor(f.first, ItemStack.EMPTY);
            int publicMaximumDuration = (int) Math.ceil(Math.log(f.config.maxMultiplierCents() / 100.0)
                    / f.config.growthPerSecond * RoundMath.TICKS_PER_SECOND);
            check(helper, low.phase() == CrashDropSnapshot.Phase.RUNNING && low.crashCents() == 0
                    && high.crashCents() == 0 && low.phaseLength() == publicMaximumDuration
                    && high.phaseLength() == publicMaximumDuration && low.phaseTicks() == high.phaseTicks()
                    && low.multiplierCents() == high.multiplierCents() && low.history().equals(high.history()),
                    "Changing the hidden threshold must not change any running-round observable timing or crash field");
        }
        helper.succeed();
    }

    @GameTest(structure = EMPTY, maxTicks = 100)
    public void historyRetainsOnlyEightCompletedRoundsAndIdsKeepIncreasing(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            long previous = f.game.roundId();
            List<Integer> expected = new ArrayList<>();
            for (int index = 0; index < 11; index++) {
                int threshold = 110 + index;
                f.runAt(firstTickAtLeast(threshold, f.config), threshold);
                expected.addFirst(threshold);
                if (expected.size() > 8) expected.removeLast();
                CrashDropSnapshot crashed = f.game.snapshotFor(f.first, ItemStack.EMPTY);
                check(helper, crashed.phase() == CrashDropSnapshot.Phase.CRASHED
                        && crashed.history().equals(expected), "Only the newest eight completed thresholds belong in history");
                f.nextRound();
                long next = f.game.roundId();
                check(helper, next > previous, "A new round ID must be strictly newer than all prior action IDs");
                previous = next;
            }
        }
        helper.succeed();
    }

    @GameTest(structure = EMPTY, maxTicks = 100)
    public void serverAndNativeSlotsRejectBlacklistedTerminalOversizedAndForeignActions(GameTestHelper helper) {
        CrashDropConfig config = new CrashDropConfig();
        config.blacklist.add("minecraft:diamond");
        try (Fixture f = new Fixture(helper, config)) {
            CrashDropMenu menu = f.menu(f.first);
            ItemStack terminal = new ItemStack(CrashDrop.TERMINAL);
            ItemStack diamond = new ItemStack(Items.DIAMOND, 4);
            check(helper, !menu.getSlot(0).mayPlace(terminal) && !menu.getSlot(0).mayPlace(diamond)
                    && !f.game.placeBet(f.first, terminal) && !f.game.placeBet(f.first, diamond)
                    && terminal.getCount() == 1 && diamond.getCount() == 4,
                    "The native slot and server authority must both reject terminal and configured forbidden items");
            ItemStack oversized = new ItemStack(Items.EMERALD, 65);
            check(helper, !f.game.placeBet(f.first, oversized) && oversized.getCount() == 65,
                    "A forged oversized stack cannot bypass the server stake limit");
            menu.getSlot(0).set(new ItemStack(Items.EMERALD, 3));
            ServerPlayer foreign = f.player();
            menu.handleAction(foreign, f.game.roundId(), CrashDropMenu.BET);
            f.first.containerMenu = f.first.inventoryMenu;
            menu.handleAction(f.first, f.game.roundId(), CrashDropMenu.BET);
            check(helper, menu.getInputStack().getCount() == 3 && !f.ledger.hasEscrow(f.first.getUUID()),
                    "A foreign player or a packet for a non-current menu cannot consume its input");
        }
        helper.succeed();
    }

    private static ItemStack componentRichHelmet(GameTestHelper helper) {
        ItemStack stack = new ItemStack(Items.DIAMOND_HELMET);
        stack.setDamageValue(17);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("CrashDrop component custody"));
        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        var registry = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        enchantments.set(registry.get(Identifier.fromNamespaceAndPath("minecraft", "protection")).orElseThrow(), 4);
        enchantments.set(registry.get(Identifier.fromNamespaceAndPath("minecraft", "mending")).orElseThrow(), 1);
        stack.set(DataComponents.ENCHANTMENTS, enchantments.toImmutable());
        CompoundTag tag = new CompoundTag();
        tag.putString("crashdrop_regression", "preserve exact native components");
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    private static int firstTickAtLeast(int multiplierCents, CrashDropConfig config) {
        for (int elapsed = 0; elapsed < 100_000; elapsed++) {
            if (RoundMath.multiplierCentsAtTick(elapsed, config) >= multiplierCents) return elapsed;
        }
        throw new IllegalArgumentException("Unreachable fixture multiplier " + multiplierCents);
    }

    private static CrashDropLedger roundTrip(GameTestHelper helper, CrashDropLedger ledger) {
        var ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var encoded = CrashDropLedger.CODEC.encodeStart(ops, ledger).getOrThrow();
        return CrashDropLedger.CODEC.parse(ops, encoded).getOrThrow();
    }

    private static int inventoryCount(ServerPlayer player, ItemStack prototype) {
        return inventoryStacks(player).stream().filter(stack -> ItemStack.isSameItemSameComponents(stack, prototype))
                .mapToInt(ItemStack::getCount).sum();
    }

    private static List<ItemStack> inventoryStacks(ServerPlayer player) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) result.add(stack);
        }
        return result;
    }

    private static long groundItems(ServerPlayer player) {
        return player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(8)).size();
    }

    private static void check(GameTestHelper helper, boolean condition, String reason) {
        helper.assertTrue(condition, reason);
    }

    private static Object get(Object owner, String name) {
        try {
            Class<?> type = owner instanceof Class<?> c ? c : owner.getClass();
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner instanceof Class<?> ? null : owner);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot read native test fixture state " + name, exception);
        }
    }

    private static void put(Object owner, String name, Object value) {
        try {
            Class<?> type = owner instanceof Class<?> c ? c : owner.getClass();
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(owner instanceof Class<?> ? null : owner, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot prepare native test fixture state " + name, exception);
        }
    }

    /** Synchronous fixtures never share custody, player inventories, or a globally exposed test game. */
    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final MinecraftServer server;
        final CrashDropConfig config;
        final CrashDropLedger ledger = new CrashDropLedger();
        final CrashDropGame game;
        final ServerPlayer first;
        final Map<MinecraftServer, CrashDropGame> games;
        final CrashDropGame previous;
        final List<ServerPlayer> players = new ArrayList<>();
        final List<CrashDropMenu> menus = new ArrayList<>();

        Fixture(GameTestHelper helper) { this(helper, new CrashDropConfig()); }

        @SuppressWarnings("unchecked")
        Fixture(GameTestHelper helper, CrashDropConfig configuration) {
            this.helper = helper;
            server = helper.getLevel().getServer();
            config = configuration.copy();
            games = (Map<MinecraftServer, CrashDropGame>) get(CrashDrop.class, "GAMES");
            previous = games.get(server);
            CrashDropLedger shared = CrashDropLedger.load(server);
            CrashDropLedger saved = roundTrip(helper, shared);
            try {
                game = new CrashDropGame(server, config);
            } finally {
                Map<Object, Object> accounts = (Map<Object, Object>) get(shared, "accounts");
                accounts.clear();
                accounts.putAll((Map<Object, Object>) get(saved, "accounts"));
                put(shared, "roundId", get(saved, "roundId"));
            }
            put(ledger, "roundId", get(game, "roundId"));
            put(game, "ledger", ledger);
            put(game, "crashCents", 10_000);
            games.put(server, game);
            first = player();
        }

        long now() { return Integer.toUnsignedLong(server.getTickCount()); }

        ServerPlayer player() {
            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            players.add(player);
            return player;
        }

        CrashDropMenu menu(ServerPlayer player) {
            CrashDropMenu menu = new CrashDropMenu(menus.size() + 1, player.getInventory());
            player.containerMenu = menu;
            menus.add(menu);
            return menu;
        }

        void runAt(int elapsed, int threshold) {
            put(game, "crashCents", threshold);
            CrashDropSnapshot.Phase phase = (CrashDropSnapshot.Phase) get(game, "phase");
            if (phase == CrashDropSnapshot.Phase.CRASHED) throw new IllegalStateException("Begin the next round first");
            put(game, "phaseStartTick", now() - elapsed
                    - (phase == CrashDropSnapshot.Phase.BETTING ? CrashDropGame.BETTING_TICKS : 0));
            game.tick();
        }

        void nextRound() {
            if (get(game, "phase") != CrashDropSnapshot.Phase.CRASHED) {
                put(game, "crashCents", 100);
                put(game, "phase", CrashDropSnapshot.Phase.RUNNING);
                put(game, "phaseStartTick", now());
                game.tick();
            }
            put(game, "phaseStartTick", now() - CrashDropGame.CRASHED_TICKS);
            game.tick();
            put(game, "crashCents", 10_000);
        }

        @Override public void close() {
            try {
                for (CrashDropMenu menu : menus) {
                    menu.getSlot(0).set(ItemStack.EMPTY);
                    for (ServerPlayer player : players) if (player.containerMenu == menu) menu.removed(player);
                }
                for (ServerPlayer player : players) {
                    player.getInventory().clearContent();
                    player.containerMenu = player.inventoryMenu;
                }
                game.stop();
            } finally {
                if (previous == null) games.remove(server);
                else games.put(server, previous);
            }
        }
    }
}
