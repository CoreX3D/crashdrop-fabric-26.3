package dev.core.crashdrop;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** One authoritative round for the entire server. All mutations run on its main thread. */
public final class CrashDropGame {
    public static final int BETTING_TICKS = 200;
    public static final int CRASHED_TICKS = 80;

    private final MinecraftServer server;
    private final CrashDropConfig configuration;
    private final CrashDropLedger ledger;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Bet> bets = new HashMap<>();
    private final List<Integer> history = new ArrayList<>();
    private CrashDropConfig roundConfiguration;
    private CrashDropSnapshot.Phase phase = CrashDropSnapshot.Phase.BETTING;
    private long roundId;
    private long phaseStartTick;
    /** Hidden until CRASHED. Never include this field in a running-round packet. */
    private int crashCents;
    private boolean stopped;

    public CrashDropGame(MinecraftServer server, CrashDropConfig configuration) {
        this.server = server;
        this.configuration = configuration.copy();
        this.ledger = CrashDropLedger.load(server);
        // Unfinished escrow from a previous server session is refunded, never replayed.
        ledger.recoverEscrow();
        beginRound(now());
    }

    public void tick() {
        advanceTo(now());
    }

    public void stop() {
        if (stopped) return;
        // Shutdown is cancellation rather than an artificial crash or a free cashout.
        ledger.recoverEscrow();
        stopped = true;
    }

    public long roundId() {
        advanceTo(now());
        return roundId;
    }

    public boolean canBet(ServerPlayer player) {
        advanceTo(now());
        return validPlayer(player) && phase == CrashDropSnapshot.Phase.BETTING
                && !bets.containsKey(player.getUUID()) && !ledger.hasEscrow(player.getUUID());
    }

    public boolean isAllowedStake(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getCount() > roundConfiguration.maxStakeItems
                || stack.getCount() > stack.getMaxStackSize()) return false;
        return !roundConfiguration.blacklist.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    /**
     * Takes ownership of the supplied live menu stack only after accepting the wager. A failed or
     * duplicate request leaves it intact. The durable escrow always preserves every component.
     */
    public boolean placeBet(ServerPlayer player, ItemStack stack) {
        if (!canBet(player) || !isAllowedStake(stack)) return false;
        UUID owner = player.getUUID();
        ItemStack wager = stack.copy();
        ledger.hold(owner, wager);
        stack.setCount(0);
        bets.put(owner, new Bet(wager, CrashDropSnapshot.BetStatus.QUEUED));
        return true;
    }

    public boolean cashOut(ServerPlayer player, long expectedRoundId) {
        // Network actions can arrive before END_SERVER_TICK: resolve this exact server tick first.
        advanceTo(now());
        if (!validPlayer(player) || expectedRoundId != roundId
                || phase != CrashDropSnapshot.Phase.RUNNING) return false;
        Bet bet = bets.get(player.getUUID());
        if (bet == null || bet.status != CrashDropSnapshot.BetStatus.ACTIVE
                || !ledger.hasEscrow(player.getUUID())) return false;
        int multiplier = currentMultiplier(now());
        // Equality is a crash, including the configured maximum boundary.
        if (multiplier >= crashCents) return false;
        RoundMath.Payout payout = ledger.settle(player.getUUID(), multiplier, roundConfiguration.maxPayoutItems);
        bet.status = CrashDropSnapshot.BetStatus.CASHED_OUT;
        bet.paidItems = payout.items();
        bet.remainderCents = payout.remainderCents();
        ledger.claim(player);
        announceCashout(player, bet, multiplier);
        playTerminalSound(player, SoundEvents.PLAYER_LEVELUP, 0.7F, 1.15F);
        return true;
    }

    public int claim(ServerPlayer player) {
        if (!validPlayer(player)) return 0;
        return ledger.claim(player);
    }

    public CrashDropSnapshot snapshotFor(ServerPlayer player, ItemStack preview) {
        long tick = now();
        advanceTo(tick);
        Bet bet = bets.get(player.getUUID());
        ItemStack shown = bet == null ? preview.copy() : bet.stake.copy();
        int remainder = shown.isEmpty() ? 0 : ledger.remainder(player.getUUID(), shown);
        return new CrashDropSnapshot(roundId, tick, phase, elapsedInPhase(tick), phaseLength(),
                currentMultiplier(tick), phase == CrashDropSnapshot.Phase.CRASHED ? crashCents : 0,
                shown, bet == null ? CrashDropSnapshot.BetStatus.NONE : bet.status,
                bet == null ? 0 : bet.paidItems, remainder, ledger.pendingCount(player.getUUID()),
                List.copyOf(history));
    }

    private boolean validPlayer(ServerPlayer player) {
        return !stopped && player != null && player.level().getServer() == server
                && server.getPlayerList().getPlayer(player.getUUID()) == player
                && !player.hasDisconnected() && player.isAlive() && !player.isSpectator();
    }

    private long now() {
        return Integer.toUnsignedLong(server.getTickCount());
    }

    private void beginRound(long tick) {
        roundConfiguration = configuration.copy();
        roundId = ledger.nextRoundId();
        crashCents = RoundMath.sampleCrashCents(random.nextDouble(), roundConfiguration);
        phase = CrashDropSnapshot.Phase.BETTING;
        phaseStartTick = tick;
        bets.clear();
    }

    private void advanceTo(long tick) {
        if (stopped) return;
        if (phase == CrashDropSnapshot.Phase.BETTING && tick - phaseStartTick >= BETTING_TICKS) {
            phaseStartTick += BETTING_TICKS;
            phase = CrashDropSnapshot.Phase.RUNNING;
            for (Bet bet : bets.values()) {
                if (bet.status == CrashDropSnapshot.BetStatus.QUEUED) bet.status = CrashDropSnapshot.BetStatus.ACTIVE;
            }
        }
        if (phase == CrashDropSnapshot.Phase.RUNNING && currentMultiplier(tick) >= crashCents) {
            crash(tick);
        }
        if (phase == CrashDropSnapshot.Phase.CRASHED && tick - phaseStartTick >= CRASHED_TICKS) {
            beginRound(tick);
        }
    }

    private void crash(long tick) {
        phase = CrashDropSnapshot.Phase.CRASHED;
        phaseStartTick = tick;
        history.addFirst(crashCents);
        if (history.size() > 8) history.removeLast();
        for (Map.Entry<UUID, Bet> entry : bets.entrySet()) {
            Bet bet = entry.getValue();
            if (bet.status != CrashDropSnapshot.BetStatus.ACTIVE
                    && bet.status != CrashDropSnapshot.BetStatus.QUEUED) continue;
            ledger.lose(entry.getKey());
            bet.status = CrashDropSnapshot.BetStatus.LOST;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null && !player.hasDisconnected()) {
                player.sendSystemMessage(Component.translatable("crashdrop.chat.prefix").withStyle(ChatFormatting.DARK_PURPLE)
                        .append(Component.translatable("crashdrop.chat.lost", formatMultiplier(crashCents))
                                .withStyle(ChatFormatting.RED)));
                playTerminalSound(player, SoundEvents.ANVIL_LAND, 0.45F, 0.8F);
            }
        }
    }

    private int currentMultiplier(long tick) {
        return switch (phase) {
            case BETTING -> RoundMath.ONE;
            case RUNNING -> RoundMath.multiplierCentsAtTick(Math.max(0, tick - phaseStartTick), roundConfiguration);
            case CRASHED -> crashCents;
        };
    }

    private int elapsedInPhase(long tick) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, tick - phaseStartTick));
    }

    private int phaseLength() {
        return switch (phase) {
            case BETTING -> BETTING_TICKS;
            case CRASHED -> CRASHED_TICKS;
            // The maximum duration is public configuration, never the hidden crash time.
            case RUNNING -> (int) Math.ceil(Math.log(roundConfiguration.maxMultiplierCents() / 100.0)
                    / roundConfiguration.growthPerSecond * RoundMath.TICKS_PER_SECOND);
        };
    }

    private void announceCashout(ServerPlayer player, Bet bet, int multiplier) {
        MutableComponent text = Component.translatable("crashdrop.chat.prefix").withStyle(ChatFormatting.DARK_PURPLE)
                .append(Component.translatable("crashdrop.chat.cashout",
                        player.getDisplayName().copy().withStyle(ChatFormatting.AQUA),
                        Component.literal(Integer.toString(bet.paidItems)).withStyle(ChatFormatting.GREEN),
                        bet.stake.getHoverName().copy().withStyle(ChatFormatting.AQUA),
                        Component.literal(formatMultiplier(multiplier)).withStyle(ChatFormatting.GOLD),
                        bet.stake.getCount()).withStyle(ChatFormatting.GRAY));
        if (bet.remainderCents > 0) {
            text.append(Component.translatable("crashdrop.chat.fraction",
                    String.format(Locale.ROOT, "%.2f", bet.remainderCents / 100.0))
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        server.getPlayerList().broadcastSystemMessage(text, false);
    }

    private void playTerminalSound(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
        // One server-issued packet to this player; the screen never creates a second result sound.
        player.connection.send(new ClientboundSoundPacket(Holder.direct(sound), SoundSource.PLAYERS,
                player.getX(), player.getY(), player.getZ(), volume, pitch, random.nextLong()));
    }

    private static String formatMultiplier(int cents) {
        return String.format(Locale.ROOT, "%.2f×", cents / 100.0);
    }

    private static final class Bet {
        private final ItemStack stake;
        private CrashDropSnapshot.BetStatus status;
        private int paidItems;
        private int remainderCents;

        private Bet(ItemStack stake, CrashDropSnapshot.BetStatus status) {
            this.stake = stake.copy();
            this.status = status;
        }
    }
}
