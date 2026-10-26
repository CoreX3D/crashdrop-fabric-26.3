package dev.core.crashdrop.client;

import dev.core.crashdrop.CrashDrop;
import dev.core.crashdrop.CrashDropMenu;
import dev.core.crashdrop.CrashDropNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.MenuScreens;

/** Client packets mirror server state; no result or inventory decision is made here. */
public final class CrashDropClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MenuScreens.register(CrashDrop.MENU, CrashDropScreen::new);
        ClientPlayNetworking.registerGlobalReceiver(CrashDropNetwork.SnapshotPayload.TYPE,
                (payload, context) -> {
                    if (context.player().containerMenu instanceof CrashDropMenu menu
                            && menu.containerId == payload.containerId()) {
                        menu.acceptSnapshot(payload.snapshot());
                    }
                });
    }

    public static void sendAction(CrashDropMenu menu, int action) {
        if (ClientPlayNetworking.canSend(CrashDropNetwork.ActionPayload.TYPE)) {
            ClientPlayNetworking.send(new CrashDropNetwork.ActionPayload(
                    menu.containerId, menu.snapshot().roundId(), action));
        }
    }
}
