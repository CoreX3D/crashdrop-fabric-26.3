package dev.core.crashdrop;

import java.math.BigDecimal;
import java.util.Objects;

/** Pure round calculations; all authoritative elapsed time is supplied in server ticks. */
public final class RoundMath {
    public static final int TICKS_PER_SECOND = 20;
    public static final int ONE = 100;

    private RoundMath() {
    }

    /**
     * Samples the hidden crash threshold. The caller supplies a server-side random draw in [0, 1).
     * The round crashes when its current multiplier is greater than or equal to this threshold.
     */
    public static int sampleCrashCents(double uniform, CrashDropConfig config) {
        Objects.requireNonNull(config, "config");
        if (!Double.isFinite(uniform) || uniform < 0.0 || uniform >= 1.0) {
            throw new IllegalArgumentException("The random draw must be finite and in [0, 1)");
        }
        if (!Double.isFinite(config.houseEdge) || config.houseEdge < 0.0 || config.houseEdge >= 1.0) {
            throw new IllegalArgumentException("Use a normalized configuration");
        }
        int ceiling = config.maxMultiplierCents();
        BigDecimal survival = BigDecimal.ONE.subtract(BigDecimal.valueOf(config.houseEdge));
        BigDecimal target = survival.pow(10).movePointRight(26);
        BigDecimal remainingDraw = BigDecimal.ONE.subtract(BigDecimal.valueOf(uniform));
        BigDecimal remainingDrawToTen = remainingDraw.pow(10);
        // Survival falls as (1 - houseEdge) / multiplier^1.3. Find the exact integer ceil
        // of 100 * ((1 - houseEdge) / (1 - uniform))^(10/13) without floating-point roots:
        // n^13 * (1 - uniform)^10 >= (1 - houseEdge)^10 * 100^13.
        int low = ONE;
        int high = ceiling;
        while (low < high) {
            int middle = (low + high) >>> 1;
            BigDecimal candidate = BigDecimal.valueOf(middle).pow(13);
            if (remainingDrawToTen.multiply(candidate).compareTo(target) >= 0) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }

    /** The displayed multiplier, rounded down to hundredths and bounded by the configured ceiling. */
    public static int multiplierCentsAtTick(long elapsedTicks, CrashDropConfig config) {
        Objects.requireNonNull(config, "config");
        if (elapsedTicks <= 0) return ONE;
        if (!Double.isFinite(config.growthPerSecond) || config.growthPerSecond <= 0.0) {
            throw new IllegalArgumentException("Use a normalized configuration");
        }
        int ceiling = config.maxMultiplierCents();
        double exponent = elapsedTicks / (double) TICKS_PER_SECOND * config.growthPerSecond;
        if (exponent >= StrictMath.log(ceiling / (double) ONE)) return ceiling;
        return Math.clamp((int) StrictMath.floor(StrictMath.exp(exponent) * ONE), ONE, ceiling);
    }

    /**
     * Whole items returned by cashing out. The multiplier includes the original wager.
     * Multiplication uses integer hundredths; there is no floating-point rounding of item counts.
     */
    public static int payoutItems(int stakeCount, long multiplierCents, int maxPayoutItems) {
        if (stakeCount < 1 || multiplierCents < ONE || maxPayoutItems < 1) {
            throw new IllegalArgumentException("Stake, multiplier and payout limit must be positive");
        }
        long hundredthItems;
        try {
            hundredthItems = Math.multiplyExact((long) stakeCount, multiplierCents);
        } catch (ArithmeticException overflow) {
            // A positive mathematical result exceeding Long.MAX_VALUE also exceeds any int limit.
            return maxPayoutItems;
        }
        return (int) Math.min(hundredthItems / ONE, (long) maxPayoutItems);
    }

    /**
     * Settles a successful cashout without losing fractional items. The caller stores the remainder
     * separately for the same player and exact item components; failed rounds never call this method.
     * An insufficient payout limit is rejected rather than silently discarding a win or its fraction.
     */
    public static Payout payoutWithRemainder(int stakeCount, int multiplierCents,
                                           int priorRemainderCents, int maxPayoutItems) {
        if (stakeCount < 1 || multiplierCents < ONE || priorRemainderCents < 0
                || priorRemainderCents >= ONE || maxPayoutItems < 1) {
            throw new IllegalArgumentException("Invalid stake, multiplier, remainder or payout limit");
        }
        long hundredthItems = Math.addExact(Math.multiplyExact((long) stakeCount, multiplierCents),
                priorRemainderCents);
        long wholeItems = hundredthItems / ONE;
        if (wholeItems > maxPayoutItems) {
            throw new ArithmeticException("The payout exceeds the configured item limit");
        }
        return new Payout((int) wholeItems, (int) (hundredthItems % ONE));
    }

    public record Payout(int items, int remainderCents) {
    }
}
