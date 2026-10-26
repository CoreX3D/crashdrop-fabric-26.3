package dev.core.crashdrop.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import dev.core.crashdrop.CrashDrop;
import dev.core.crashdrop.CrashDropConfig;
import dev.core.crashdrop.CrashDropGame;
import dev.core.crashdrop.CrashDropMenu;
import dev.core.crashdrop.CrashDropSnapshot;
import dev.core.crashdrop.CrashDropSnapshot.BetStatus;
import dev.core.crashdrop.CrashDropSnapshot.Phase;
import dev.core.crashdrop.RoundMath;
import dev.core.crashdrop.client.CrashDropClient;
import dev.core.crashdrop.client.CrashDropScreen;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Real terminal, native slot clicks, button packets, shared-round snapshots and rendered animation. */
@Environment(EnvType.CLIENT)
@SuppressWarnings("UnstableApiUsage")
public final class CrashDropClientGameTest implements FabricClientGameTest {
    private static final double FIXTURE_GROWTH = 0.000001;

    @Override
    public void runTest(ClientGameTestContext context) {
        Path directory = context.computeOnClient(client -> client.gameDirectory.toPath().toAbsolutePath().normalize());
        require(directory.endsWith(Path.of("build", "client-render-smoke")),
                "CrashDrop client fixtures may only use build/client-render-smoke: " + directory);
        int[] settings = context.computeOnClient(client -> new int[] {
                client.getWindow().getScreenWidth(), client.getWindow().getScreenHeight(),
                client.options.guiScale().get(), client.options.renderDistance().get(),
                client.options.simulationDistance().get()});
        String[] language = context.computeOnClient(client -> new String[] {
                client.getLanguageManager().getSelected(), client.options.languageCode});
        List<String> evidence = new ArrayList<>();
        for (String id : System.getProperty("crashdrop.test.requiredMods", "").split(",")) {
            if (id.isBlank()) continue;
            var mod = FabricLoader.getInstance().getModContainer(id);
            require(mod.isPresent(), "CrashDrop compatibility client omitted required profile mod " + id);
            evidence.add("Profile mod loaded: " + id + " "
                    + mod.orElseThrow().getMetadata().getVersion().getFriendlyString());
        }
        try {
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.options.renderDistance().set(5);
                client.options.simulationDistance().set(5);
            });
            context.getInput().resizeWindow(1600, 900);
            context.runOnClient(Minecraft::resizeGui);
            reloadLanguage(context, "ru_ru", "ru_ru");
            try (var world = context.worldBuilder().setUseConsistentSettings(true).create()) {
                validate(context, world.getServer(), world.getConnection(), evidence);
                evidence.add("Temporary world: " + world.getWorldSave().getSaveDirectory().toAbsolutePath());
            }
            evidence.add("PASS: Russian CrashDrop screen, real terminal opening, inventory input and button packets;"
                    + " authoritative shared round, smooth interpolation, success, fractions, crash and bounded history.");
            saveEvidence(directory, evidence);
        } catch (RuntimeException | AssertionError failure) {
            evidence.add("FAIL: " + failure);
            saveEvidence(directory, evidence);
            throw failure;
        } finally {
            reloadLanguage(context, language[0], language[1]);
            context.runOnClient(client -> {
                client.options.guiScale().set(settings[2]);
                client.options.renderDistance().set(settings[3]);
                client.options.simulationDistance().set(settings[4]);
            });
            context.getInput().resizeWindow(settings[0], settings[1]);
            context.runOnClient(Minecraft::resizeGui);
        }
    }

    private static void validate(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, List<String> evidence) {
        ChatComponent.State chat = context.computeOnClient(client -> client.gui.hud.getChat().storeState());
        SavedPlayer saved = server.computeOnServer(instance -> {
            var player = connection.getServerPlayer();
            List<ItemStack> inventory = new ArrayList<>();
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                inventory.add(player.getInventory().getItem(slot).copy());
            }
            return new SavedPlayer(inventory, player.getInventory().getSelectedSlot(),
                    player.gameMode.getGameModeForPlayer(), player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot());
        });
        try {
            server.runOnServer(instance -> {
                var player = connection.getServerPlayer();
                var level = connection.getServerLevel();
                level.setBlock(new BlockPos(0, 63, 0), Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_ALL);
                player.teleportTo(level, .5, 64, .5, Set.of(), 180f, 0f, true);
                player.setGameMode(GameType.SURVIVAL);
                player.getInventory().clearContent();
                player.getInventory().setSelectedSlot(0);
                player.getInventory().setItem(0, new ItemStack(CrashDrop.TERMINAL));
                player.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 4));
                player.getInventory().setItem(10, new ItemStack(Items.DIRT));
                beginFixtureRound(CrashDrop.game(instance), Integer.toUnsignedLong(instance.getTickCount()));
                player.inventoryMenu.broadcastChanges();
                CrashDrop.TERMINAL.use(level, player, InteractionHand.MAIN_HAND);
                require(player.containerMenu instanceof CrashDropMenu, "The real terminal item did not open CrashDrop");
            });
            connection.waitForClientboundPackets();
            context.waitFor(client -> client.gui.screen() instanceof CrashDropScreen
                    && screen(client).getMenu().snapshot().roundId() > 0, 1200);
            require(clientSnapshot(context).phase() == Phase.BETTING, "Initial round was not accepting bets");
            screenshot(context, evidence, "crashdrop-waiting");
            validateCompactScreen(context, evidence);

            placeOneDiamond(context, server, connection, 3);
            rejectAnotherInput(context, server, connection, evidence);
            long firstRound = clientSnapshot(context).roundId();
            startRunning(context, server, connection, 100);
            require(clientSnapshot(context).roundId() == firstRound && clientSnapshot(context).crashCents() == 0,
                    "RUNNING snapshot changed the shared round or disclosed its future crash point");
            require(inventoryDiamonds(server, connection) == 3,
                    "Accepted escrow did not remove exactly one diamond before the running round");
            screenshot(context, evidence, "crashdrop-running-one");

            forceMultiplier(context, server, connection, 200, false);
            context.runOnClient(client -> {
                var gui = screen(client);
                long received = field(gui, CrashDropScreen.class, "receivedNanos", Long.class);
                double start = displayed(gui, received);
                double early = displayed(gui, received + 25_000_000);
                double late = displayed(gui, received + 75_000_000);
                double settled = displayed(gui, received + 200_000_000);
                require(start >= 100 && start < early && early < late && late < settled && settled == 200,
                        "Rendered multiplier did not interpolate monotonically between received states: "
                                + start + ", " + early + ", " + late + ", " + settled);
                require(displayed(gui, received + 10_000_000_000L) == 200,
                        "Client extrapolated the multiplier beyond the last server snapshot");
            });
            evidence.add("PASS: server packet moved 1.00× to 2.00×; native display samples at 25/75/200 ms"
                    + " are strictly increasing, bounded by the received multiplier and never extrapolate.");
            screenshot(context, evidence, "crashdrop-running-two");
            clickRelative(context, 237, 140, InputConstants.MOUSE_BUTTON_LEFT);
            awaitStatus(context, server, connection, BetStatus.CASHED_OUT);
            require(clientSnapshot(context).paidItems() == 2 && inventoryDiamonds(server, connection) == 5,
                    "One diamond withdrawn at 2.00× did not return exactly two diamonds");
            require(clientSnapshot(context).remainderCents() == 0, "Whole payout invented a fractional credit");
            assertEmptyInputAndCursor(context, server, connection);
            // Send real duplicate serverbound packets after the native button has already committed.
            context.runOnClient(client -> {
                CrashDropClient.sendAction(screen(client).getMenu(), CrashDropMenu.WITHDRAW);
                CrashDropClient.sendAction(screen(client).getMenu(), CrashDropMenu.WITHDRAW);
            });
            connection.waitForServerboundPackets();
            connection.waitForClientboundPackets();
            require(inventoryDiamonds(server, connection) == 5 && clientSnapshot(context).paidItems() == 2,
                    "Duplicate withdraw packets minted a second payout");
            screenshot(context, evidence, "crashdrop-success");
            evidence.add("PASS: native withdraw button at 2.00× consumed escrow once and paid exactly two"
                    + " diamonds; total inventory 4 → 3 → 5; duplicate network actions paid nothing more.");

            nextRound(context, server, connection);
            placeOneDiamond(context, server, connection, 4);
            startRunning(context, server, connection, 100);
            long activeRound = clientSnapshot(context).roundId();
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            connection.waitForServerboundPackets();
            context.waitFor(client -> client.gui.screen() == null);
            require(inventoryDiamonds(server, connection) == 4, "Closing the active round refunded its committed stake");
            openTerminal(context, server, connection);
            require(clientSnapshot(context).roundId() == activeRound
                    && clientSnapshot(context).status() == BetStatus.ACTIVE
                    && clientSnapshot(context).stake().is(Items.DIAMOND)
                    && clientSnapshot(context).stake().getCount() == 1,
                    "Reopening did not reconnect to the same active shared round and escrow");
            forceMultiplier(context, server, connection, 150, true);
            awaitStatus(context, server, connection, BetStatus.LOST);
            require(clientSnapshot(context).phase() == Phase.CRASHED && clientSnapshot(context).crashCents() == 150,
                    "Client did not receive the actual authoritative crash state");
            require(inventoryDiamonds(server, connection) == 4 && clientSnapshot(context).paidItems() == 0,
                    "Crash paid, duplicated or refunded the lost diamond");
            context.waitTicks(2);
            screenshot(context, evidence, "crashdrop-crash-flash");
            context.waitTicks(14);
            screenshot(context, evidence, "crashdrop-crash-settled");
            context.runOnClient(client -> {
                var gui = screen(client);
                require(field(gui, CrashDropScreen.class, "crashedNanos", Long.class) > 0,
                        "Real crash snapshot never started its animation clock");
                require(displayed(gui, System.nanoTime()) == 150,
                        "Crash animation changed the settled multiplier or kept rising");
            });
            evidence.add("PASS: closing/reopening preserves the accepted stake and shared round; server crashes"
                    + " at 1.50×, changes status to LOST and pays zero; crash flash/fragments and settled UI captured.");

            // Fractional diamonds stay in the authoritative exact-item ledger across independent rounds.
            nextRound(context, server, connection);
            placeOneDiamond(context, server, connection, 3);
            startRunning(context, server, connection, 180);
            clickRelative(context, 237, 140, InputConstants.MOUSE_BUTTON_LEFT);
            awaitStatus(context, server, connection, BetStatus.CASHED_OUT);
            require(clientSnapshot(context).paidItems() == 1 && clientSnapshot(context).remainderCents() == 80
                    && inventoryDiamonds(server, connection) == 4, "1.80× payout did not preserve 0.80 diamond credit");
            nextRound(context, server, connection);
            placeOneDiamond(context, server, connection, 3);
            startRunning(context, server, connection, 140);
            clickRelative(context, 237, 140, InputConstants.MOUSE_BUTTON_LEFT);
            awaitStatus(context, server, connection, BetStatus.CASHED_OUT);
            require(clientSnapshot(context).paidItems() == 2 && clientSnapshot(context).remainderCents() == 20
                    && inventoryDiamonds(server, connection) == 5,
                    "Saved 0.80 plus a 1.40× payout did not pay two and retain 0.20 diamond");
            screenshot(context, evidence, "crashdrop-fraction-credit");
            evidence.add("PASS: native 1.80× win pays one diamond and saves 0.80; next native 1.40× win"
                    + " pays two diamonds and keeps 0.20; no random rounding or destroyed fractions.");

            // More than eight completed shared rounds must remain a bounded, renderable history.
            server.runOnServer(instance -> {
                CrashDropGame game = CrashDrop.game(instance);
                for (int index = 0; index < 10; index++) {
                    long now = Integer.toUnsignedLong(instance.getTickCount());
                    beginFixtureRound(game, now);
                    setField(game, "crashCents", 100);
                    setField(game, "phaseStartTick", now - CrashDropGame.BETTING_TICKS);
                    game.tick();
                }
                setField(game, "phaseStartTick", Integer.toUnsignedLong(instance.getTickCount()) + 100_000L);
                serverMenu(connection).broadcastChanges();
            });
            connection.waitForClientboundPackets();
            context.waitFor(client -> screen(client).getMenu().snapshot().history().size() == 8);
            require(clientSnapshot(context).history().stream().allMatch(value -> value == 100),
                    "Bounded history reordered or retained stale crash values");
            screenshot(context, evidence, "crashdrop-history");
            evidence.add("PASS: ten more completed rounds retain exactly the most recent eight history chips.");

            nextRound(context, server, connection);
            moveOneDiamondIntoInput(context, server, connection, 4);
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            connection.waitForServerboundPackets();
            context.waitFor(client -> client.gui.screen() == null);
            server.waitFor(instance -> connection.getServerPlayer().containerMenu
                    == connection.getServerPlayer().inventoryMenu);
            require(inventoryDiamonds(server, connection) == 5,
                    "Closing with an uncommitted input did not return exactly that diamond");
            openTerminal(context, server, connection);
            assertEmptyInputAndCursor(context, server, connection);
            evidence.add("PASS: closing uncommitted input returns exactly one diamond; reopen has empty input/cursor;"
                    + " no surviving wager, duplicated output or free refund.");
        } finally {
            server.runOnServer(instance -> {
                var player = connection.getServerPlayer();
                player.closeContainer();
                player.getInventory().clearContent();
                for (int slot = 0; slot < saved.inventory().size(); slot++) {
                    player.getInventory().setItem(slot, saved.inventory().get(slot));
                }
                player.getInventory().setSelectedSlot(saved.selected());
                player.setGameMode(saved.mode());
                player.teleportTo(connection.getServerLevel(), saved.x(), saved.y(), saved.z(), Set.of(),
                        saved.yaw(), saved.pitch(), true);
                player.inventoryMenu.broadcastChanges();
            });
            connection.waitForClientboundPackets();
            context.setScreen(() -> null);
            context.runOnClient(client -> client.gui.hud.getChat().restoreState(chat));
        }
    }

    private static void validateCompactScreen(ClientGameTestContext context, List<String> evidence) {
        context.getInput().resizeWindow(854, 480);
        context.runOnClient(Minecraft::resizeGui);
        context.waitTick();
        context.runOnClient(client -> {
            var gui = screen(client);
            int left = field(gui, AbstractContainerScreen.class, "leftPos", Integer.class);
            int top = field(gui, AbstractContainerScreen.class, "topPos", Integer.class);
            int width = client.getWindow().getGuiScaledWidth();
            int height = client.getWindow().getGuiScaledHeight();
            require(height == 240 && CrashDropScreen.WIDTH == 320 && CrashDropScreen.HEIGHT == 240,
                    "Compact fixture did not exercise the 320×240 panel in the minimum 240-pixel GUI height");
            require(left >= 0 && top >= 0 && left + CrashDropScreen.WIDTH <= width
                    && top + CrashDropScreen.HEIGHT <= height,
                    "CrashDrop panel escaped the compact viewport: origin=" + left + "," + top
                            + " viewport=" + width + "×" + height);
            for (var slot : gui.getMenu().slots) {
                require(left + slot.x >= 0 && top + slot.y >= 0
                        && left + slot.x + 16 <= width && top + slot.y + 16 <= height,
                        "Native input/inventory/hotbar slot escaped the compact viewport: " + slot.x + "," + slot.y);
            }
        });
        screenshot(context, evidence, "crashdrop-compact-240");
        evidence.add("PASS: 854×480 window at GUI scale 2 gives a 427×240 viewport; whole 320×240 panel,"
                + " native input, all 27 inventory slots and every hotbar slot remain visible and in bounds.");
        context.getInput().resizeWindow(1600, 900);
        context.runOnClient(Minecraft::resizeGui);
        context.waitTick();
    }

    private static void rejectAnotherInput(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, List<String> evidence) {
        int dirtSlot = context.computeOnClient(client -> {
            var menu = screen(client).getMenu();
            for (int slot = 1; slot < menu.slots.size(); slot++) {
                if (menu.getSlot(slot).getItem().is(Items.DIRT)) return slot;
            }
            throw new AssertionError("Missing dirt fixture for an actual post-bet input click");
        });
        int[] origin = context.computeOnClient(client -> new int[] {
                screen(client).getMenu().getSlot(dirtSlot).x + 8,
                screen(client).getMenu().getSlot(dirtSlot).y + 8});
        clickRelative(context, origin[0], origin[1], InputConstants.MOUSE_BUTTON_LEFT);
        clickRelative(context, 56, 78, InputConstants.MOUSE_BUTTON_LEFT);
        connection.waitForServerboundPackets();
        connection.waitForClientboundPackets();
        require(server.computeOnServer(instance -> serverMenu(connection).getInputStack().isEmpty()
                && serverMenu(connection).getCarried().is(Items.DIRT)
                && serverMenu(connection).getCarried().getCount() == 1),
                "Accepted diamond bet allowed a second input or consumed its rejected dirt stack");
        context.runOnClient(client -> require(screen(client).getMenu().getInputStack().isEmpty()
                && screen(client).getMenu().getCarried().is(Items.DIRT)
                && screen(client).getMenu().snapshot().stake().is(Items.DIAMOND),
                "Rejected post-bet click replaced the visible diamond stake or lost the cursor stack"));
        clickRelative(context, origin[0], origin[1], InputConstants.MOUSE_BUTTON_LEFT);
        connection.waitForServerboundPackets();
        connection.waitForClientboundPackets();
        server.waitFor(instance -> serverMenu(connection).getCarried().isEmpty()
                && serverMenu(connection).getSlot(dirtSlot).getItem().is(Items.DIRT));
        context.waitFor(client -> screen(client).getMenu().getCarried().isEmpty()
                && screen(client).getMenu().getSlot(dirtSlot).getItem().is(Items.DIRT));
        require(clientSnapshot(context).stake().getCount() == 1
                && clientSnapshot(context).status() == BetStatus.QUEUED,
                "Rejected post-bet input modified the accepted stake or status");
        evidence.add("PASS: real dirt pickup and attempted input placement after the accepted diamond bet"
                + " are rejected on client/server; original stake survives and the one dirt returns to its inventory slot.");
    }

    private static void placeOneDiamond(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, int remainingInventory) {
        moveOneDiamondIntoInput(context, server, connection, remainingInventory);
        clickRelative(context, 83, 140, InputConstants.MOUSE_BUTTON_LEFT);
        awaitStatus(context, server, connection, BetStatus.QUEUED);
        require(clientSnapshot(context).stake().is(Items.DIAMOND)
                && clientSnapshot(context).stake().getCount() == 1, "Accepted bet snapshot lost its one diamond");
        assertEmptyInputAndCursor(context, server, connection);
        require(inventoryDiamonds(server, connection) == remainingInventory, "Bet consumed inventory a second time");
    }

    private static void moveOneDiamondIntoInput(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, int remainingInventory) {
        // Find the real native inventory slot containing diamonds (payouts may merge into the hotbar).
        int slot = context.computeOnClient(client -> {
            var menu = screen(client).getMenu();
            for (int index = 1; index < menu.slots.size(); index++) {
                if (menu.getSlot(index).getItem().is(Items.DIAMOND)) return index;
            }
            throw new AssertionError("No diamond inventory slot available for the native stake click");
        });
        int[] position = context.computeOnClient(client -> new int[] {
                screen(client).getMenu().getSlot(slot).x + 8, screen(client).getMenu().getSlot(slot).y + 8});
        clickRelative(context, position[0], position[1], InputConstants.MOUSE_BUTTON_LEFT);
        clickRelative(context, 56, 78, InputConstants.MOUSE_BUTTON_RIGHT);
        clickRelative(context, position[0], position[1], InputConstants.MOUSE_BUTTON_LEFT);
        connection.waitForServerboundPackets();
        server.waitFor(instance -> serverMenu(connection).getInputStack().is(Items.DIAMOND)
                && serverMenu(connection).getInputStack().getCount() == 1
                && serverMenu(connection).getCarried().isEmpty());
        connection.waitForClientboundPackets();
        context.waitFor(client -> screen(client).getMenu().getInputStack().is(Items.DIAMOND)
                && screen(client).getMenu().getInputStack().getCount() == 1
                && screen(client).getMenu().getCarried().isEmpty());
        require(inventoryDiamonds(server, connection) == remainingInventory,
                "Native pickup/right-deposit/return clicks did not move exactly one diamond");
    }

    private static void nextRound(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection) {
        long previous = clientSnapshot(context).roundId();
        server.runOnServer(instance -> {
            beginFixtureRound(CrashDrop.game(instance), Integer.toUnsignedLong(instance.getTickCount()));
            serverMenu(connection).broadcastChanges();
        });
        connection.waitForClientboundPackets();
        context.waitFor(client -> screen(client).getMenu().snapshot().roundId() > previous
                && screen(client).getMenu().snapshot().phase() == Phase.BETTING);
    }

    private static void startRunning(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, int cents) {
        server.runOnServer(instance -> {
            CrashDropGame game = CrashDrop.game(instance);
            long tick = Integer.toUnsignedLong(instance.getTickCount());
            require(field(game, CrashDropGame.class, "phase", Phase.class) == Phase.BETTING,
                    "Running fixture must start with an accepted betting phase");
            setField(game, "phaseStartTick", tick - CrashDropGame.BETTING_TICKS);
            game.tick();
            setRunningMultiplier(game, tick, cents);
            serverMenu(connection).broadcastChanges();
        });
        connection.waitForClientboundPackets();
        context.waitFor(client -> screen(client).getMenu().snapshot().phase() == Phase.RUNNING
                && screen(client).getMenu().snapshot().multiplierCents() == cents
                && screen(client).getMenu().snapshot().status() == BetStatus.ACTIVE);
    }

    private static void forceMultiplier(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, int cents, boolean crash) {
        server.runOnServer(instance -> {
            CrashDropGame game = CrashDrop.game(instance);
            if (crash) setField(game, "crashCents", cents);
            setRunningMultiplier(game, Integer.toUnsignedLong(instance.getTickCount()), cents);
            // Keep the real terminal phase visible for both screenshots even on slow render hardware.
            if (crash) setField(game, "phaseStartTick", Integer.toUnsignedLong(instance.getTickCount()) + 100_000L);
            serverMenu(connection).broadcastChanges();
        });
        connection.waitForClientboundPackets();
        context.waitFor(client -> screen(client).getMenu().snapshot().multiplierCents() == cents
                && screen(client).getMenu().snapshot().phase() == (crash ? Phase.CRASHED : Phase.RUNNING));
        context.waitFor(client -> field(screen(client), CrashDropScreen.class, "observed", CrashDropSnapshot.class)
                != null && field(screen(client), CrashDropScreen.class, "observed", CrashDropSnapshot.class)
                .multiplierCents() == cents);
    }

    private static void setRunningMultiplier(CrashDropGame game, long tick, int cents) {
        CrashDropConfig config = field(game, CrashDropGame.class, "roundConfiguration", CrashDropConfig.class);
        long elapsed = (long) Math.ceil(StrictMath.log(cents / 100.0) / config.growthPerSecond * 20);
        while (RoundMath.multiplierCentsAtTick(elapsed, config) < cents) elapsed++;
        setField(game, "phaseStartTick", tick - elapsed);
        game.tick();
    }

    private static void beginFixtureRound(CrashDropGame game, long tick) {
        invoke(game, "beginRound", new Class<?>[] {long.class}, tick + 100_000L);
        CrashDropConfig round = field(game, CrashDropGame.class, "roundConfiguration", CrashDropConfig.class);
        // Only this disposable server's current round changes; installed JSON/global defaults are untouched.
        round.growthPerSecond = FIXTURE_GROWTH;
        setField(game, "crashCents", 5000);
    }

    private static void awaitStatus(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, BetStatus status) {
        connection.waitForServerboundPackets();
        server.waitFor(instance -> serverMenu(connection).snapshot().status() == status);
        connection.waitForClientboundPackets();
        context.waitFor(client -> screen(client).getMenu().snapshot().status() == status);
    }

    private static void openTerminal(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection) {
        server.runOnServer(instance -> CrashDrop.TERMINAL.use(connection.getServerLevel(),
                connection.getServerPlayer(), InteractionHand.MAIN_HAND));
        connection.waitForClientboundPackets();
        context.waitFor(client -> client.gui.screen() instanceof CrashDropScreen
                && screen(client).getMenu().snapshot().roundId() > 0);
    }

    private static int inventoryDiamonds(TestServerContext server, TestServerConnection connection) {
        return server.computeOnServer(instance -> {
            int count = 0;
            var inventory = connection.getServerPlayer().getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.is(Items.DIAMOND)) count += stack.getCount();
            }
            return count;
        });
    }

    private static void assertEmptyInputAndCursor(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection) {
        require(server.computeOnServer(instance -> serverMenu(connection).getInputStack().isEmpty()
                        && serverMenu(connection).getCarried().isEmpty()),
                "Server kept a duplicate input or cursor stack after committing");
        context.runOnClient(client -> require(screen(client).getMenu().getInputStack().isEmpty()
                && screen(client).getMenu().getCarried().isEmpty(), "Client kept a duplicate input or cursor stack"));
    }

    private static void clickRelative(ClientGameTestContext context, int x, int y, int button) {
        double[] cursor = context.computeOnClient(client -> {
            var gui = screen(client);
            int left = field(gui, AbstractContainerScreen.class, "leftPos", Integer.class);
            int top = field(gui, AbstractContainerScreen.class, "topPos", Integer.class);
            var window = client.getWindow();
            return new double[] {(left + x) * (double) window.getScreenWidth() / window.getGuiScaledWidth(),
                    (top + y) * (double) window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(cursor[0], cursor[1]);
        context.getInput().pressMouse(button);
        context.waitTick();
    }

    private static void screenshot(ClientGameTestContext context, List<String> evidence, String name) {
        evidence.add("Screenshot: " + context.takeScreenshot(TestScreenshotOptions.of(name)
                .disableCounterPrefix()).toAbsolutePath());
    }

    private static CrashDropSnapshot clientSnapshot(ClientGameTestContext context) {
        return context.computeOnClient(client -> screen(client).getMenu().snapshot());
    }

    private static CrashDropScreen screen(Minecraft client) {
        require(client.gui.screen() instanceof CrashDropScreen, "Expected the real CrashDrop screen");
        return (CrashDropScreen) client.gui.screen();
    }

    private static CrashDropMenu serverMenu(TestServerConnection connection) {
        require(connection.getServerPlayer().containerMenu instanceof CrashDropMenu,
                "Expected the real server CrashDrop menu");
        return (CrashDropMenu) connection.getServerPlayer().containerMenu;
    }

    private static double displayed(CrashDropScreen screen, long time) {
        return (Double) invoke(screen, "displayedMultiplier", new Class<?>[] {long.class}, time);
    }

    private static Object invoke(Object instance, String name, Class<?>[] parameters, Object... arguments) {
        try {
            Method method = instance.getClass().getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method.invoke(instance, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot invoke disposable CrashDrop fixture " + name, failure);
        }
    }

    private static <T> T field(Object instance, Class<?> owner, String name, Class<T> type) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(instance));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot inspect CrashDrop fixture field " + name, failure);
        }
    }

    private static void setField(Object instance, String name, Object value) {
        try {
            Field field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(instance, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot prepare disposable CrashDrop fixture field " + name, failure);
        }
    }

    private static void reloadLanguage(ClientGameTestContext context, String selected, String option) {
        CompletableFuture<Void> reload = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected(selected);
            client.options.languageCode = option;
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> reload.isDone(), 1200);
        reload.join();
    }

    private static void saveEvidence(Path directory, List<String> evidence) {
        try {
            Files.write(directory.resolve("crashdrop-validation.txt"), evidence);
        } catch (java.io.IOException failure) {
            throw new AssertionError("Could not save CrashDrop client evidence", failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record SavedPlayer(List<ItemStack> inventory, int selected, GameType mode,
            double x, double y, double z, float yaw, float pitch) { }
}
