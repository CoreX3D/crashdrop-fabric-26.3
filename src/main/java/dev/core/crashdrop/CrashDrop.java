package dev.core.crashdrop;

import java.util.IdentityHashMap;
import java.util.Map;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

public final class CrashDrop implements ModInitializer {
    public static final String MOD_ID = "crashdrop";
    public static final MenuType<CrashDropMenu> MENU = Registry.register(BuiltInRegistries.MENU,
            id("terminal"), new MenuType<>(CrashDropMenu::new, FeatureFlags.DEFAULT_FLAGS));
    public static final Item TERMINAL = Registry.register(BuiltInRegistries.ITEM, id("terminal"),
            new Terminal(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id("terminal"))).stacksTo(1)));
    private static final Map<MinecraftServer, CrashDropGame> GAMES = new IdentityHashMap<>();
    private static CrashDropConfig config;

    @Override public void onInitialize() {
        config = CrashDropConfig.load();
        CrashDropNetwork.initialize();
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> output.accept(TERMINAL));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> GAMES.put(server, new CrashDropGame(server, config.copy())));
        ServerTickEvents.END_SERVER_TICK.register(server -> game(server).tick());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { CrashDropGame game = GAMES.get(server); if (game != null) game.stop(); });
        ServerLifecycleEvents.SERVER_STOPPED.register(GAMES::remove);
    }

    public static CrashDropGame game(MinecraftServer server) {
        return GAMES.computeIfAbsent(server, key -> new CrashDropGame(key, config.copy()));
    }
    public static Identifier id(String name) { return Identifier.fromNamespaceAndPath(MOD_ID, name); }

    private static final class Terminal extends Item {
        Terminal(Properties properties) { super(properties); }
        @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new CrashDropMenu(id, inventory),
                        Component.translatable("screen.crashdrop.title")));
            }
            return InteractionResult.SUCCESS;
        }
    }
}
