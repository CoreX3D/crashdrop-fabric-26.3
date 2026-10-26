package dev.core.crashdrop;

import java.util.List;
import java.util.Objects;
import net.minecraft.world.item.ItemStack;

/** Public state only: the future crash point never appears in a running snapshot. */
public record CrashDropSnapshot(long roundId, long serverTick, Phase phase, int phaseTicks,
        int phaseLength, int multiplierCents, int crashCents, ItemStack stake, BetStatus status,
        int paidItems, int remainderCents, int pendingItems, List<Integer> history) {
    public enum Phase { BETTING, RUNNING, CRASHED }
    public enum BetStatus { NONE, QUEUED, ACTIVE, CASHED_OUT, LOST }
    public static final CrashDropSnapshot EMPTY = new CrashDropSnapshot(0, 0, Phase.BETTING,
            0, 200, 100, 0, ItemStack.EMPTY, BetStatus.NONE, 0, 0, 0, List.of());

    public CrashDropSnapshot {
        Objects.requireNonNull(phase);
        Objects.requireNonNull(status);
        stake = Objects.requireNonNull(stake).copy();
        history = List.copyOf(history);
        if (phaseTicks < 0 || phaseLength < 0 || multiplierCents < 100 || multiplierCents > 10_000
                || remainderCents < 0 || remainderCents >= 100 || paidItems < 0 || pendingItems < 0
                || history.size() > 8 || (phase != Phase.CRASHED && crashCents != 0)) {
            throw new IllegalArgumentException("Invalid CrashDrop public state");
        }
    }
    @Override public ItemStack stake() { return stake.copy(); }
}
