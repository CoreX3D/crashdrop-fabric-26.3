package dev.core.crashdrop;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class CrashDropNetwork {
    private CrashDropNetwork() { }

    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SnapshotPayload.TYPE, SnapshotPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (payload.action() < 0 || payload.action() > 2 || payload.roundId() <= 0) return;
            if (player.containerMenu instanceof CrashDropMenu menu
                    && menu.containerId == payload.containerId() && menu.stillValid(player)) {
                menu.handleAction(player, payload.roundId(), payload.action());
            }
        });
    }

    public static void sendSnapshot(ServerPlayer player, int containerId, CrashDropSnapshot snapshot) {
        if (ServerPlayNetworking.canSend(player, SnapshotPayload.TYPE)) {
            ServerPlayNetworking.send(player, new SnapshotPayload(containerId, snapshot));
        }
    }

    private static Identifier id(String name) { return Identifier.fromNamespaceAndPath("crashdrop", name); }

    public record ActionPayload(int containerId, long roundId, int action) implements CustomPacketPayload {
        public static final Type<ActionPayload> TYPE = new Type<>(id("action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC = CustomPacketPayload.codec(ActionPayload::write, ActionPayload::new);
        private ActionPayload(RegistryFriendlyByteBuf buffer) { this(buffer.readVarInt(), buffer.readVarLong(), buffer.readVarInt()); }
        private void write(RegistryFriendlyByteBuf buffer) { buffer.writeVarInt(containerId); buffer.writeVarLong(roundId); buffer.writeVarInt(action); }
        @Override public Type<ActionPayload> type() { return TYPE; }
    }

    public record SnapshotPayload(int containerId, CrashDropSnapshot snapshot) implements CustomPacketPayload {
        public static final Type<SnapshotPayload> TYPE = new Type<>(id("snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotPayload> CODEC = CustomPacketPayload.codec(SnapshotPayload::write, SnapshotPayload::new);
        private SnapshotPayload(RegistryFriendlyByteBuf buffer) { this(buffer.readVarInt(), readSnapshot(buffer)); }
        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(containerId);
            buffer.writeVarLong(snapshot.roundId()); buffer.writeVarLong(snapshot.serverTick());
            buffer.writeEnum(snapshot.phase()); buffer.writeVarInt(snapshot.phaseTicks()); buffer.writeVarInt(snapshot.phaseLength());
            buffer.writeVarInt(snapshot.multiplierCents()); buffer.writeVarInt(snapshot.crashCents());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, snapshot.stake());
            buffer.writeEnum(snapshot.status()); buffer.writeVarInt(snapshot.paidItems());
            buffer.writeVarInt(snapshot.remainderCents()); buffer.writeVarInt(snapshot.pendingItems());
            buffer.writeVarInt(snapshot.history().size()); for (int n : snapshot.history()) buffer.writeVarInt(n);
        }
        private static CrashDropSnapshot readSnapshot(RegistryFriendlyByteBuf buffer) {
            long round = buffer.readVarLong(), tick = buffer.readVarLong();
            var phase = buffer.readEnum(CrashDropSnapshot.Phase.class);
            int elapsed = buffer.readVarInt(), length = buffer.readVarInt(), multiplier = buffer.readVarInt(), crash = buffer.readVarInt();
            var stake = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
            var status = buffer.readEnum(CrashDropSnapshot.BetStatus.class);
            int paid = buffer.readVarInt(), remainder = buffer.readVarInt(), pending = buffer.readVarInt(), size = buffer.readVarInt();
            if (size < 0 || size > 8) throw new IllegalArgumentException("Invalid CrashDrop history length");
            List<Integer> history = new ArrayList<>(size); for (int n = 0; n < size; n++) history.add(buffer.readVarInt());
            return new CrashDropSnapshot(round, tick, phase, elapsed, length, multiplier, crash, stake, status, paid, remainder, pending, history);
        }
        @Override public Type<SnapshotPayload> type() { return TYPE; }
    }
}
