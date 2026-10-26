package dev.core.crashdrop;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoundMathTest {
    @Test
    void defaultBalanceSupportsTheLargestStakeWithoutCappingIt() {
        CrashDropConfig config = new CrashDropConfig().normalize();
        assertEquals(6400, config.maxMultiplierCents());
        assertEquals(4096, config.maxPayoutItems);
        assertEquals(4096, RoundMath.payoutItems(config.maxStakeItems,
                config.maxMultiplierCents(), config.maxPayoutItems));
    }

    @Test
    void crashSamplesIncludeInstantCrashAndStayBoundedAtTheUpperEndpoint() {
        CrashDropConfig config = new CrashDropConfig().normalize();
        assertEquals(100, RoundMath.sampleCrashCents(0.0, config));
        assertEquals(100, RoundMath.sampleCrashCents(0.03, config));
        assertEquals(101, RoundMath.sampleCrashCents(Math.nextUp(0.03), config));
        assertEquals(6400, RoundMath.sampleCrashCents(Math.nextDown(1.0), config));
    }

    @Test
    void decimalBoundaryNeighborsDoNotGainAPhantomHundredth() {
        CrashDropConfig config = new CrashDropConfig().normalize();
        assertEquals(167, RoundMath.sampleCrashCents(0.5, config));
        assertEquals(284, RoundMath.sampleCrashCents(0.75, config));
        double fourBoundary = 1.0 - (1.0 - config.houseEdge) / StrictMath.pow(4.0, 1.3);
        assertEquals(400, RoundMath.sampleCrashCents(fourBoundary - 1.0e-10, config));
        assertEquals(401, RoundMath.sampleCrashCents(fourBoundary + 1.0e-10, config));
        assertEquals(575, RoundMath.sampleCrashCents(0.9, config));
    }

    @Test
    void invalidRandomDrawsAreRejectedInsteadOfCreatingAWinnableRound() {
        CrashDropConfig config = new CrashDropConfig().normalize();
        for (double draw : new double[]{-0.01, 1.0, Double.NaN,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> RoundMath.sampleCrashCents(draw, config));
        }
    }

    @Test
    void crashThresholdIsMonotonicInTheSecretRandomDraw() {
        CrashDropConfig config = new CrashDropConfig().normalize();
        int previous = 100;
        for (int index = 0; index < 10000; index++) {
            int threshold = RoundMath.sampleCrashCents(index / 10000.0, config);
            assertTrue(threshold >= previous);
            assertTrue(threshold <= config.maxMultiplierCents());
            previous = threshold;
        }
    }

    @Test
    void discreteThresholdKeepsConfiguredInstantCrashAndSurvivalProbabilities() {
        CrashDropConfig config = new CrashDropConfig().normalize();
        int samples = 10000;
        int instant = 0;
        int reachesTwo = 0;
        int reachesTen = 0;
        for (int index = 0; index < samples; index++) {
            int crash = RoundMath.sampleCrashCents((index + 0.5) / samples, config);
            if (crash == 100) instant++;
            // Equality is already a crash, so a cashout must be strictly below the threshold.
            if (crash > 200) reachesTwo++;
            if (crash > 1000) reachesTen++;
        }
        assertEquals(config.houseEdge, instant / (double) samples, 1.0 / samples);
        assertEquals((1.0 - config.houseEdge) / StrictMath.pow(2.0, 1.3),
                reachesTwo / (double) samples, 1.0 / samples);
        assertEquals((1.0 - config.houseEdge) / StrictMath.pow(10.0, 1.3),
                reachesTen / (double) samples, 1.0 / samples);
    }

    @Test
    void defaultSixtyFourCeilingIsReachableAndHigherCrashMultipliersAreRarer() {
        CrashDropConfig config = new CrashDropConfig().normalize();
        assertEquals(6400, RoundMath.sampleCrashCents(0.999, config));
        assertEquals(6400, RoundMath.sampleCrashCents(Math.nextDown(1.0), config));
        int samples = 10000;
        int atLeastTwo = 0;
        int atLeastEight = 0;
        int atSixtyFourCeiling = 0;
        for (int index = 0; index < samples; index++) {
            int crash = RoundMath.sampleCrashCents((index + 0.5) / samples, config);
            if (crash >= 200) atLeastTwo++;
            if (crash >= 800) atLeastEight++;
            if (crash >= 6400) atSixtyFourCeiling++;
        }
        assertTrue(atLeastTwo > atLeastEight);
        assertTrue(atLeastEight > atSixtyFourCeiling);
        assertTrue(atSixtyFourCeiling > 0);
        // ceil(raw cents) >= k means raw cents > k - 1, including the capped final bucket.
        // This is the exact cent-grid version of (1 - houseEdge) / multiplier^1.3.
        assertEquals((1.0 - config.houseEdge) / StrictMath.pow(1.99, 1.3),
                atLeastTwo / (double) samples, 1.0 / samples);
        assertEquals((1.0 - config.houseEdge) / StrictMath.pow(7.99, 1.3),
                atLeastEight / (double) samples, 1.0 / samples);
        assertEquals((1.0 - config.houseEdge) / StrictMath.pow(63.99, 1.3),
                atSixtyFourCeiling / (double) samples, 1.0 / samples);
    }

    @Test
    void serverConfigurationCanStillRaiseTheCeilingToOneHundred() {
        CrashDropConfig config = new CrashDropConfig();
        config.maxMultiplier = 100.0;
        config.normalize();
        assertEquals(10000, config.maxMultiplierCents());
        assertEquals(6400, config.maxPayoutItems);
        assertEquals(10000, RoundMath.sampleCrashCents(Math.nextDown(1.0), config));
    }

    @Test
    void multiplierUsesServerTicksAndNeverMovesBackwards() {
        CrashDropConfig config = new CrashDropConfig().normalize();
        assertEquals(100, RoundMath.multiplierCentsAtTick(0, config));
        assertEquals(100, RoundMath.multiplierCentsAtTick(-1, config));
        assertEquals(106, RoundMath.multiplierCentsAtTick(20, config));
        assertEquals(182, RoundMath.multiplierCentsAtTick(200, config));
        assertTrue(RoundMath.multiplierCentsAtTick(230, config) < 200);
        assertTrue(RoundMath.multiplierCentsAtTick(232, config) >= 200);
        int previous = 100;
        for (long tick = 0; tick <= 3000; tick++) {
            int current = RoundMath.multiplierCentsAtTick(tick, config);
            assertTrue(current >= previous);
            assertTrue(current <= config.maxMultiplierCents());
            previous = current;
        }
    }

    @Test
    void elapsedTickOverflowCannotWrapTheMultiplier() {
        CrashDropConfig config = new CrashDropConfig().normalize();
        assertEquals(6400, RoundMath.multiplierCentsAtTick(Long.MAX_VALUE, config));
    }

    @Test
    void payoutIncludesTheStakeAndUsesExactIntegerHundredths() {
        assertEquals(1, RoundMath.payoutItems(1, 100, 6400));
        assertEquals(1, RoundMath.payoutItems(1, 199, 6400));
        assertEquals(2, RoundMath.payoutItems(1, 200, 6400));
        assertEquals(3, RoundMath.payoutItems(2, 150, 6400));
        assertEquals(99, RoundMath.payoutItems(3, 3333, 6400));
        assertEquals(6400, RoundMath.payoutItems(64, 10000, 6400));
    }

    @Test
    void payoutLimitAndArithmeticOverflowCannotCreateNegativeOrExcessiveItems() {
        assertEquals(4096, RoundMath.payoutItems(64, 10000, 4096));
        assertEquals(6400, RoundMath.payoutItems(Integer.MAX_VALUE, Long.MAX_VALUE, 6400));
        assertThrows(IllegalArgumentException.class, () -> RoundMath.payoutItems(0, 100, 6400));
        assertThrows(IllegalArgumentException.class, () -> RoundMath.payoutItems(1, 99, 6400));
        assertThrows(IllegalArgumentException.class, () -> RoundMath.payoutItems(1, 100, 0));
    }

    @Test
    void invalidConfigurationIsNormalizedToFiniteSafeBounds() {
        CrashDropConfig config = new CrashDropConfig();
        config.houseEdge = Double.NaN;
        config.maxMultiplier = Double.POSITIVE_INFINITY;
        config.growthPerSecond = Double.NEGATIVE_INFINITY;
        config.maxStakeItems = Integer.MAX_VALUE;
        config.maxPayoutItems = -1;
        config.normalize();
        assertEquals(0.03, config.houseEdge);
        assertEquals(64.0, config.maxMultiplier);
        assertEquals(0.06, config.growthPerSecond);
        assertEquals(64, config.maxStakeItems);
        assertEquals(4096, config.maxPayoutItems);
    }

    @Test
    void normalizedLimitsPreserveConfiguredPayoutForAllAllowedStakes() {
        CrashDropConfig config = new CrashDropConfig();
        config.maxMultiplier = 2.5;
        config.maxStakeItems = 17;
        config.maxPayoutItems = 1;
        config.normalize();
        assertEquals(43, config.maxPayoutItems);
        for (int stake = 1; stake <= config.maxStakeItems; stake++) {
            assertEquals(stake * 250 / 100, RoundMath.payoutItems(stake,
                    config.maxMultiplierCents(), config.maxPayoutItems));
        }
    }

    @Test
    void normalizedSnapshotCannotBeChangedThroughTheSourceConfiguration() {
        CrashDropConfig source = new CrashDropConfig();
        CrashDropConfig snapshot = source.copy();
        source.houseEdge = 0.8;
        source.growthPerSecond = 1.0;
        source.blacklist.clear();
        assertEquals(0.03, snapshot.houseEdge);
        assertEquals(0.06, snapshot.growthPerSecond);
        assertTrue(snapshot.blacklist.contains("crashdrop:terminal"));
    }

    @Test
    void blacklistKeepsNamespacedItemIdsAndAllowsAnExplicitEmptyList() {
        CrashDropConfig config = new CrashDropConfig();
        config.blacklist = new LinkedHashSet<>(List.of(" minecraft:diamond ", "MINECRAFT:STONE", "bad id", "ic2:tool/drill"));
        config.normalize();
        assertEquals(new LinkedHashSet<>(List.of("minecraft:diamond", "ic2:tool/drill")), config.blacklist);
        config.blacklist.clear();
        assertTrue(config.copy().blacklist.isEmpty());
        config.blacklist = null;
        assertTrue(config.copy().blacklist.contains("crashdrop:terminal"));
    }

    @Test
    void successfulCashoutsCarryFractionsIntoTheNextExactItemPayout() {
        RoundMath.Payout first = RoundMath.payoutWithRemainder(1, 150, 0, 6400);
        assertEquals(new RoundMath.Payout(1, 50), first);
        RoundMath.Payout second = RoundMath.payoutWithRemainder(1, 150, first.remainderCents(), 6400);
        assertEquals(new RoundMath.Payout(2, 0), second);
        assertEquals(new RoundMath.Payout(4, 25), RoundMath.payoutWithRemainder(2, 175, 75, 6400));
    }

    @Test
    void repeatedFractionalCashoutsConserveTheExactTotal() {
        int items = 0;
        int remainder = 0;
        for (int round = 0; round < 1000; round++) {
            RoundMath.Payout result = RoundMath.payoutWithRemainder(1, 133, remainder, 6400);
            items += result.items();
            remainder = result.remainderCents();
        }
        assertEquals(1330, items);
        assertEquals(0, remainder);
    }

    @Test
    void fractionalSettlementRejectsInvalidRemaindersAndInsufficientLimits() {
        assertThrows(IllegalArgumentException.class, () -> RoundMath.payoutWithRemainder(1, 150, -1, 6400));
        assertThrows(IllegalArgumentException.class, () -> RoundMath.payoutWithRemainder(1, 150, 100, 6400));
        assertThrows(ArithmeticException.class, () -> RoundMath.payoutWithRemainder(1, 199, 99, 1));
        assertThrows(ArithmeticException.class, () -> RoundMath.payoutWithRemainder(
                Integer.MAX_VALUE, Integer.MAX_VALUE, 99, 6400));
    }

    @Test
    void configuredPayoutLimitIncludesTheLargestPossibleSavedFraction() {
        CrashDropConfig config = new CrashDropConfig();
        config.maxMultiplier = 2.5;
        config.maxStakeItems = 17;
        config.maxPayoutItems = 1;
        config.normalize();
        assertEquals(new RoundMath.Payout(43, 49), RoundMath.payoutWithRemainder(
                config.maxStakeItems, config.maxMultiplierCents(), 99, config.maxPayoutItems));
    }
}
