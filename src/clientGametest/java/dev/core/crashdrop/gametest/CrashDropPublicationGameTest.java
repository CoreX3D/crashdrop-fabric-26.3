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
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Publication photographs of the real production screen; no image generation or drawn mock UI. */
@Environment(EnvType.CLIENT)
@SuppressWarnings("UnstableApiUsage")
public final class CrashDropPublicationGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        Path directory = context.computeOnClient(client -> client.gameDirectory.toPath().toAbsolutePath().normalize());
        require(directory.endsWith(Path.of("build", "client-render-smoke")),
                "Publication scenes must use the disposable build/client-render-smoke world");
        Settings saved = context.computeOnClient(client -> new Settings(
                client.getWindow().getScreenWidth(), client.getWindow().getScreenHeight(),
                client.options.guiScale().get(), client.options.renderDistance().get(),
                client.options.simulationDistance().get(), client.options.menuBackgroundBlurriness().get(),
                client.getLanguageManager().getSelected(), client.options.languageCode,
                client.options.chatVisibility().get(), client.gui.hud.getChat().storeState()));
        List<String> evidence = new ArrayList<>();
        for (String id : System.getProperty("crashdrop.test.requiredMods", "").split(",")) {
            if (id.isBlank()) continue;
            var mod = FabricLoader.getInstance().getModContainer(id);
            require(mod.isPresent(), "Publication client omitted the actual-profile mod " + id);
            evidence.add("Profile mod loaded: " + id + " "
                    + mod.orElseThrow().getMetadata().getVersion().getFriendlyString());
        }
        try {
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.options.renderDistance().set(8);
                client.options.simulationDistance().set(5);
                client.options.menuBackgroundBlurriness().set(0);
                client.options.chatVisibility().set(ChatVisiblity.HIDDEN);
            });
            context.getInput().resizeWindow(1280, 720);
            context.runOnClient(Minecraft::resizeGui);
            reloadLanguage(context, "en_us", "en_us");
            try (var world = context.worldBuilder().setUseConsistentSettings(true).adjustSettings(state -> {
                var normal = state.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET)
                        .getOrThrow(WorldPresets.NORMAL);
                state.setWorldType(new WorldCreationUiState.WorldTypeEntry(normal));
                state.setSeed("8675309");
                state.setGenerateStructures(false);
            }).create()) {
                photograph(context, world.getServer(), world.getConnection(), evidence);
                evidence.add("Disposable normal-world save: " + world.getWorldSave().getSaveDirectory().toAbsolutePath());
            }
            evidence.add("PASS: five unedited native English 1280×720 captures, GUI scale 2, real Minecraft terrain,"
                    + " default 0.06/s growth, genuine item input/bet/cashout and actual shared-round crash.");
            Files.write(directory.resolve("crashdrop-publication-validation.txt"), evidence);
        } catch (RuntimeException | AssertionError failure) {
            evidence.add("FAIL: " + failure);
            try { Files.write(directory.resolve("crashdrop-publication-validation.txt"), evidence); }
            catch (java.io.IOException writeFailure) { failure.addSuppressed(writeFailure); }
            throw failure;
        } catch (java.io.IOException failure) {
            throw new AssertionError("Cannot write publication capture evidence", failure);
        } finally {
            reloadLanguage(context, saved.language(), saved.languageOption());
            context.runOnClient(client -> {
                client.options.guiScale().set(saved.scale());
                client.options.renderDistance().set(saved.distance());
                client.options.simulationDistance().set(saved.simulation());
                client.options.menuBackgroundBlurriness().set(saved.blur());
                client.options.chatVisibility().set(saved.chatVisibility());
                client.gui.hud.getChat().restoreState(saved.chat());
            });
            context.getInput().resizeWindow(saved.width(), saved.height());
            context.runOnClient(Minecraft::resizeGui);
        }
    }

    private static void photograph(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, List<String> evidence) {
        server.runOnServer(instance -> {
            var level = connection.getServerLevel();
            var player = connection.getServerPlayer();
            var clock = level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
            level.clockManager().setTotalTicks(clock, 6000L);
            var weather = level.getWeatherData();
            weather.setClearWeatherTime(100_000);
            weather.setRainTime(0);
            weather.setThunderTime(0);
            weather.setRaining(false);
            weather.setThundering(false);
            int x = player.blockPosition().getX();
            int z = player.blockPosition().getZ();
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            int floor = surface + 4;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    level.setBlock(new BlockPos(x + dx, floor, z + dz), Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
                    for (int dy = 1; dy <= 5; dy++) {
                        level.setBlock(new BlockPos(x + dx, floor + dy, z + dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
            player.teleportTo(level, x + .5, floor + 1, z + .5, Set.of(), 155f, 9f, true);
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            player.getInventory().setSelectedSlot(0);
            player.getInventory().setItem(0, new ItemStack(CrashDrop.TERMINAL));
            player.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 8));
            player.inventoryMenu.broadcastChanges();
            evidence.add("Real terrain camera: " + x + "," + (floor + 1) + "," + z
                    + "; surface=" + surface + "; NORMAL preset seed 8675309; clear noon; oak viewing platform.");
        });
        connection.waitForClientboundPackets();
        // Let actual terrain chunks, clouds and render buffers finish before composing the screen.
        context.waitTicks(100);
        server.runOnServer(instance -> {
            var game = CrashDrop.game(instance);
            for (int cents : new int[] {148, 324, 105, 512, 203, 176, 1275, 241}) {
                beginRound(game, Integer.toUnsignedLong(instance.getTickCount()), cents);
                setField(game, "phaseStartTick", Integer.toUnsignedLong(instance.getTickCount()) - CrashDropGame.BETTING_TICKS);
                game.tick();
                runningAt(game, Integer.toUnsignedLong(instance.getTickCount()), cents);
            }
            beginRound(game, Integer.toUnsignedLong(instance.getTickCount()), 600);
            CrashDrop.TERMINAL.use(connection.getServerLevel(), connection.getServerPlayer(), InteractionHand.MAIN_HAND);
        });
        connection.waitForClientboundPackets();
        context.waitFor(client -> client.gui.screen() instanceof CrashDropScreen
                && screen(client).getMenu().snapshot().roundId() > 0, 1200);
        putOneDiamond(context, server, connection);
        capture(context, evidence, "publication-01-ready");
        click(context, 83, 140, InputConstants.MOUSE_BUTTON_LEFT);
        status(context, server, connection, BetStatus.QUEUED);
        startRunning(context, server, connection, 178);
        capture(context, evidence, "publication-02-running");

        moveRunning(context, server, connection, 240, false);
        click(context, 237, 140, InputConstants.MOUSE_BUTTON_LEFT);
        status(context, server, connection, BetStatus.CASHED_OUT);
        CrashDropSnapshot won = snapshot(context);
        require(won.paidItems() == 2 && won.remainderCents() >= 40,
                "Photographed native cashout did not pay two diamonds with its actual fraction");
        capture(context, evidence, "publication-03-cashed-out");
        evidence.add("Native first win: stake=1 diamond; payout=" + won.paidItems()
                + "; saved fraction=" + won.remainderCents() + "/100; running phaseTicks=" + won.phaseTicks());

        nextRound(context, server, connection, 180);
        putOneDiamond(context, server, connection);
        click(context, 83, 140, InputConstants.MOUSE_BUTTON_LEFT);
        status(context, server, connection, BetStatus.QUEUED);
        startRunning(context, server, connection, 165);
        quiet(context);
        // Real server crash, real screen clock and fragments; no animation timestamp is rewritten.
        moveRunning(context, server, connection, 180, true);
        context.waitTicks(2);
        capture(context, evidence, "publication-04-crash");
        require(snapshot(context).status() == BetStatus.LOST && snapshot(context).paidItems() == 0,
                "Photographed crash must show the actual lost escrow, never a preview-only failure");

        nextRound(context, server, connection, 500);
        putOneDiamond(context, server, connection);
        click(context, 83, 140, InputConstants.MOUSE_BUTTON_LEFT);
        status(context, server, connection, BetStatus.QUEUED);
        startRunning(context, server, connection, 180);
        click(context, 237, 140, InputConstants.MOUSE_BUTTON_LEFT);
        status(context, server, connection, BetStatus.CASHED_OUT);
        require(snapshot(context).paidItems() == 2 && snapshot(context).remainderCents() > 0,
                "Saved exact-item fraction did not combine with the next real cashout");
        capture(context, evidence, "publication-05-fraction");
        evidence.add("Native later win carries the earlier saved fraction; remainder="
                + snapshot(context).remainderCents() + "/100. All photographed phase times use default growth 0.06/s.");
    }

    private static void putOneDiamond(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection) {
        int slot = context.computeOnClient(client -> {
            var menu = screen(client).getMenu();
            for (int index = 1; index < menu.slots.size(); index++) {
                if (menu.getSlot(index).getItem().is(Items.DIAMOND)) return index;
            }
            throw new AssertionError("No actual diamond stack for the publication bet");
        });
        int[] xy = context.computeOnClient(client -> new int[] {
                screen(client).getMenu().getSlot(slot).x + 8, screen(client).getMenu().getSlot(slot).y + 8});
        click(context, xy[0], xy[1], InputConstants.MOUSE_BUTTON_LEFT);
        click(context, 56, 78, InputConstants.MOUSE_BUTTON_RIGHT);
        click(context, xy[0], xy[1], InputConstants.MOUSE_BUTTON_LEFT);
        connection.waitForServerboundPackets();
        server.waitFor(instance -> serverMenu(connection).getInputStack().is(Items.DIAMOND)
                && serverMenu(connection).getInputStack().getCount() == 1
                && serverMenu(connection).getCarried().isEmpty());
        connection.waitForClientboundPackets();
        context.waitFor(client -> screen(client).getMenu().getInputStack().is(Items.DIAMOND)
                && screen(client).getMenu().getCarried().isEmpty());
    }

    private static void nextRound(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, int crash) {
        long previous = snapshot(context).roundId();
        server.runOnServer(instance -> {
            beginRound(CrashDrop.game(instance), Integer.toUnsignedLong(instance.getTickCount()), crash);
            serverMenu(connection).broadcastChanges();
        });
        connection.waitForClientboundPackets();
        context.waitFor(client -> snapshot(client).roundId() > previous && snapshot(client).phase() == Phase.BETTING);
    }

    private static void startRunning(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, int cents) {
        server.runOnServer(instance -> {
            var game = CrashDrop.game(instance);
            long tick = Integer.toUnsignedLong(instance.getTickCount());
            setField(game, "phaseStartTick", tick - CrashDropGame.BETTING_TICKS);
            game.tick();
            runningAt(game, tick, cents);
            serverMenu(connection).broadcastChanges();
        });
        connection.waitForClientboundPackets();
        context.waitFor(client -> snapshot(client).phase() == Phase.RUNNING
                && snapshot(client).status() == BetStatus.ACTIVE && snapshot(client).multiplierCents() >= cents);
        context.waitTicks(3);
    }

    private static void moveRunning(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, int cents, boolean crash) {
        server.runOnServer(instance -> {
            var game = CrashDrop.game(instance);
            if (crash) setField(game, "crashCents", cents);
            runningAt(game, Integer.toUnsignedLong(instance.getTickCount()), cents);
            serverMenu(connection).broadcastChanges();
        });
        connection.waitForClientboundPackets();
        context.waitFor(client -> snapshot(client).phase() == (crash ? Phase.CRASHED : Phase.RUNNING)
                && snapshot(client).multiplierCents() >= cents);
    }

    private static void beginRound(CrashDropGame game, long tick, int crash) {
        invoke(game, "beginRound", new Class<?>[] {long.class}, tick);
        CrashDropConfig config = field(game, CrashDropGame.class, "roundConfiguration", CrashDropConfig.class);
        require(config.growthPerSecond == .06, "Publication scenes must retain default realistic multiplier timing");
        setField(game, "crashCents", crash);
    }

    private static void runningAt(CrashDropGame game, long tick, int cents) {
        CrashDropConfig config = field(game, CrashDropGame.class, "roundConfiguration", CrashDropConfig.class);
        long elapsed = (long) Math.ceil(StrictMath.log(cents / 100.0) / config.growthPerSecond * 20);
        while (RoundMath.multiplierCentsAtTick(elapsed, config) < cents) elapsed++;
        setField(game, "phaseStartTick", tick - elapsed);
        game.tick();
    }

    private static void status(ClientGameTestContext context, TestServerContext server,
            TestServerConnection connection, BetStatus status) {
        connection.waitForServerboundPackets();
        server.waitFor(instance -> serverMenu(connection).snapshot().status() == status);
        connection.waitForClientboundPackets();
        context.waitFor(client -> snapshot(client).status() == status);
    }

    private static void quiet(ClientGameTestContext context) {
        context.runOnClient(client -> {
            client.gui.hud.getChat().clearMessages(false);
            client.gui.toastManager().clear();
        });
        context.getInput().setCursorPos(12, 12);
    }

    private static void capture(ClientGameTestContext context, List<String> evidence, String name) {
        quiet(context);
        evidence.add("Native publication PNG: " + context.takeScreenshot(TestScreenshotOptions.of(name)
                .disableCounterPrefix()).toAbsolutePath());
    }

    private static void click(ClientGameTestContext context, int x, int y, int button) {
        double[] position = context.computeOnClient(client -> {
            var gui = screen(client);
            int left = field(gui, AbstractContainerScreen.class, "leftPos", Integer.class);
            int top = field(gui, AbstractContainerScreen.class, "topPos", Integer.class);
            var window = client.getWindow();
            return new double[] {(left + x) * (double) window.getScreenWidth() / window.getGuiScaledWidth(),
                    (top + y) * (double) window.getScreenHeight() / window.getGuiScaledHeight()};
        });
        context.getInput().setCursorPos(position[0], position[1]);
        context.getInput().pressMouse(button);
        context.waitTick();
    }

    private static CrashDropSnapshot snapshot(ClientGameTestContext context) {
        return context.computeOnClient(CrashDropPublicationGameTest::snapshot);
    }

    private static CrashDropSnapshot snapshot(Minecraft client) { return screen(client).getMenu().snapshot(); }

    private static CrashDropScreen screen(Minecraft client) {
        require(client.gui.screen() instanceof CrashDropScreen, "Expected the actual production CrashDrop screen");
        return (CrashDropScreen) client.gui.screen();
    }

    private static CrashDropMenu serverMenu(TestServerConnection connection) {
        require(connection.getServerPlayer().containerMenu instanceof CrashDropMenu, "Real server menu is not CrashDrop");
        return (CrashDropMenu) connection.getServerPlayer().containerMenu;
    }

    private static Object invoke(Object instance, String name, Class<?>[] parameters, Object... arguments) {
        try {
            Method method = instance.getClass().getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method.invoke(instance, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot prepare disposable publication round " + name, failure);
        }
    }

    private static <T> T field(Object instance, Class<?> owner, String name, Class<T> type) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(instance));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot inspect publication fixture " + name, failure);
        }
    }

    private static void setField(Object instance, String name, Object value) {
        try {
            Field field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(instance, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot prepare disposable publication field " + name, failure);
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

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private record Settings(int width, int height, int scale, int distance, int simulation, int blur,
            String language, String languageOption, ChatVisiblity chatVisibility, ChatComponent.State chat) { }
}
