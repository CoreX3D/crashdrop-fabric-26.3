package dev.core.crashdrop.client;

import dev.core.crashdrop.CrashDropMenu;
import dev.core.crashdrop.CrashDropSnapshot;
import dev.core.crashdrop.CrashDropSnapshot.BetStatus;
import dev.core.crashdrop.CrashDropSnapshot.Phase;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Original vector UI: native container slots and authoritative shared-round snapshots. */
public final class CrashDropScreen extends AbstractContainerScreen<CrashDropMenu> {
    public static final int WIDTH = 320;
    public static final int HEIGHT = 240;
    private static final int WHITE = 0xFFF0F2FF;
    private static final int MUTED = 0xFF9CA7C2;
    private static final int TEAL = 0xFF52E4CB;
    private static final int PURPLE = 0xFFB28AFF;
    private static final int RED = 0xFFFF648C;
    private static final long INTERPOLATION_NANOS = 100_000_000L;
    private static final long CRASH_EFFECT_NANOS = 600_000_000L;

    private NeonButton betButton;
    private NeonButton withdrawButton;
    private CrashDropSnapshot observed;
    private double fromMultiplier = 100;
    private double toMultiplier = 100;
    private long receivedNanos;
    private long crashedNanos;

    public CrashDropScreen(CrashDropMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
    }

    @Override
    protected void init() {
        super.init();
        betButton = addRenderableWidget(new NeonButton(leftPos + 12, topPos + 130, 142, 20,
                text("bet"), false, () -> CrashDropClient.sendAction(menu, CrashDropMenu.BET)));
        withdrawButton = addRenderableWidget(new NeonButton(leftPos + 166, topPos + 130, 142, 20,
                text("withdraw"), true, () -> {
                    CrashDropSnapshot snapshot = menu.snapshot();
                    boolean active = snapshot.phase() == Phase.RUNNING && snapshot.status() == BetStatus.ACTIVE;
                    CrashDropClient.sendAction(menu, active ? CrashDropMenu.WITHDRAW : CrashDropMenu.CLAIM);
                }));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        CrashDropSnapshot snapshot = menu.snapshot();
        long now = System.nanoTime();
        observe(snapshot, now);
        betButton.active = snapshot.roundId() > 0 && snapshot.phase() == Phase.BETTING && snapshot.status() == BetStatus.NONE
                && !menu.getInputStack().isEmpty();
        boolean active = snapshot.phase() == Phase.RUNNING && snapshot.status() == BetStatus.ACTIVE;
        withdrawButton.active = active || snapshot.pendingItems() > 0;
        withdrawButton.setMessage(!active && snapshot.pendingItems() > 0
                ? text("claim", snapshot.pendingItems()) : text("withdraw"));
        drawPanel(graphics, snapshot, now);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (snapshot.status() != BetStatus.NONE && !snapshot.stake().isEmpty()
                && isHovering(43, 65, 26, 26, mouseX, mouseY)) {
            graphics.setTooltipForNextFrame(font, snapshot.stake(), mouseX, mouseY);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // All labels are placed with the panel; the native method still draws and handles slots.
    }

    @Override
    protected void extractSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY) {
        // Accepted wagers always show the committed stack, including during ordinary slot updates.
        if (slot == menu.getSlot(0) && menu.snapshot().status() != BetStatus.NONE) return;
        super.extractSlot(graphics, slot, mouseX, mouseY);
    }

    private void observe(CrashDropSnapshot snapshot, long now) {
        if (observed == snapshot) return;
        boolean newRound = observed == null || observed.roundId() != snapshot.roundId();
        double current = displayedMultiplier(now);
        if (newRound || snapshot.phase() != Phase.RUNNING) {
            fromMultiplier = toMultiplier = snapshot.multiplierCents();
        } else {
            fromMultiplier = current;
            toMultiplier = snapshot.multiplierCents();
        }
        receivedNanos = now;
        if (snapshot.phase() == Phase.CRASHED
                && (observed == null || observed.phase() != Phase.CRASHED || newRound)) {
            crashedNanos = now;
            fromMultiplier = toMultiplier = snapshot.crashCents();
        }
        if (snapshot.phase() != Phase.CRASHED) crashedNanos = 0;
        observed = snapshot;
    }

    /** Interpolates only already received values, never extrapolating past the server. */
    private double displayedMultiplier(long now) {
        double fraction = Math.clamp((double) (now - receivedNanos) / INTERPOLATION_NANOS, 0, 1);
        return fromMultiplier + (toMultiplier - fromMultiplier) * fraction;
    }

    private void drawPanel(GuiGraphicsExtractor graphics, CrashDropSnapshot snapshot, long now) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x + 3, y + 4, x + WIDTH + 3, y + HEIGHT + 4, 0x88000000);
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xFF101321);
        graphics.outline(x, y, WIDTH, HEIGHT, 0xFF414862);
        graphics.fillGradient(x + 1, y + 1, x + WIDTH - 1, y + 31, 0xFF292142, 0xFF171929);
        graphics.fill(x + 12, y + 8, x + 16, y + 20, PURPLE);
        graphics.text(font, "CRASHDROP", x + 23, y + 8, WHITE, false);
        graphics.text(font, text("subtitle"), x + 23, y + 20, MUTED, false);
        Component phaseLabel = phaseLabel(snapshot);
        int phaseColor = snapshot.phase() == Phase.CRASHED ? RED : TEAL;
        graphics.text(font, phaseLabel, x + WIDTH - 12 - font.width(phaseLabel), y + 8, phaseColor, false);
        drawHistory(graphics, snapshot.history());
        drawStake(graphics, snapshot);
        drawChart(graphics, snapshot, now);
        drawInventory(graphics);
        graphics.textWithWordWrap(font, text("shared_hint"), x + 12, y + 166, 56, MUTED, false);
        graphics.text(font, text("fraction_balance"), x + 250, y + 166, MUTED, false);
        graphics.text(font, String.format(Locale.ROOT, "%.2f", snapshot.remainderCents() / 100.0),
                x + 250, y + 179, TEAL, false);
        graphics.textWithWordWrap(font, text("fraction_hint"), x + 250, y + 195, 58, MUTED, false);
    }

    private Component phaseLabel(CrashDropSnapshot snapshot) {
        return switch (snapshot.phase()) {
            case BETTING -> text("countdown", String.format(Locale.ROOT, "%.1f",
                    Math.max(0, snapshot.phaseLength() - snapshot.phaseTicks()) / 20.0));
            case RUNNING -> text("running");
            case CRASHED -> text("crashed");
        };
    }

    private void drawHistory(GuiGraphicsExtractor graphics, List<Integer> history) {
        int x = leftPos + 12;
        int y = topPos + 34;
        if (history.isEmpty()) {
            graphics.text(font, text("history_empty"), x + 1, y + 3, MUTED, false);
            return;
        }
        int first = Math.max(0, history.size() - 8);
        for (int i = first; i < history.size(); i++) {
            int value = history.get(i);
            int cellX = x + (i - first) * 37;
            int tint = value >= 1000 ? PURPLE : value >= 200 ? TEAL : RED;
            graphics.fill(cellX, y, cellX + 34, y + 13, 0xFF1D2235);
            graphics.fill(cellX, y + 12, cellX + 34, y + 13, tint);
            graphics.centeredText(font, value >= 10_000
                    ? String.format(Locale.ROOT, "%.0f×", value / 100.0)
                    : value >= 1000 ? String.format(Locale.ROOT, "%.1f×", value / 100.0) : multiplier(value),
                    cellX + 17, y + 2, tint);
        }
    }

    private void drawStake(GuiGraphicsExtractor graphics, CrashDropSnapshot snapshot) {
        int x = leftPos;
        int y = topPos;
        card(graphics, x + 12, y + 50, 88, 76);
        graphics.centeredText(font, text("your_bet"), x + 56, y + 55, MUTED);
        graphics.fill(x + 43, y + 65, x + 69, y + 91, 0xFF0D1220);
        graphics.outline(x + 43, y + 65, 26, 26,
                snapshot.status() == BetStatus.ACTIVE ? PURPLE : 0xFF64708D);
        ItemStack shown = snapshot.status() == BetStatus.NONE ? menu.getInputStack() : snapshot.stake();
        if (snapshot.status() != BetStatus.NONE && !shown.isEmpty()) {
            graphics.fakeItem(shown, x + 48, y + 70);
            graphics.itemDecorations(font, shown, x + 48, y + 70);
        }
        if (shown.isEmpty()) {
            graphics.centeredText(font, "+", x + 56, y + 74, MUTED);
            graphics.centeredText(font, text("empty_bet"), x + 56, y + 94, MUTED);
        } else {
            graphics.centeredText(font, text("quantity", shown.getCount()), x + 56, y + 94, WHITE);
        }
        Component payoutLabel = snapshot.status() == BetStatus.CASHED_OUT ? text("paid") : text("preview");
        graphics.centeredText(font, payoutLabel, x + 56, y + 107, MUTED);
        long preview = switch (snapshot.status()) {
            case CASHED_OUT -> snapshot.paidItems();
            case ACTIVE -> ((long) snapshot.stake().getCount() * snapshot.multiplierCents()
                    + snapshot.remainderCents()) / 100;
            default -> 0;
        };
        int payoutColor = snapshot.status() == BetStatus.LOST ? RED : TEAL;
        graphics.centeredText(font, snapshot.status() == BetStatus.NONE ? "—" : Long.toString(preview),
                x + 56, y + 118, payoutColor);
    }

    private void drawChart(GuiGraphicsExtractor graphics, CrashDropSnapshot snapshot, long now) {
        int x = leftPos + 112;
        int y = topPos + 50;
        int width = 196;
        int height = 76;
        card(graphics, x, y, width, height);
        int plotLeft = x + 11;
        int plotTop = y + 11;
        int plotRight = x + width - 9;
        int plotBottom = y + height - 14;
        for (int line = 0; line < 4; line++) {
            int gridY = plotBottom - (plotBottom - plotTop) * line / 3;
            graphics.horizontalLine(plotLeft, plotRight, gridY, 0xFF272D42);
        }
        for (int line = 0; line < 6; line++) {
            int gridX = plotLeft + (plotRight - plotLeft) * line / 5;
            graphics.verticalLine(gridX, plotTop, plotBottom, 0xFF272D42);
        }
        boolean crashed = snapshot.phase() == Phase.CRASHED;
        int tint = crashed ? RED : TEAL;
        double cents = snapshot.phase() == Phase.BETTING ? 0 : Math.max(100, displayedMultiplier(now));
        if (snapshot.phase() != Phase.BETTING) {
            double value = cents / 100.0;
            double horizontalProgress = Math.clamp(Math.log(Math.max(1, value)) / Math.log(12), .02, 1);
            double axisMax = Math.max(2, value * 1.2);
            int endX = plotLeft + (int) ((plotRight - plotLeft) * horizontalProgress);
            int oldY = plotBottom;
            for (int px = plotLeft; px <= endX; px++) {
                double fraction = (double) (px - plotLeft) / Math.max(1, endX - plotLeft);
                double point = Math.expm1(Math.log(value) * fraction) / (axisMax - 1);
                int nextY = plotBottom - (int) Math.round(point * (plotBottom - plotTop));
                graphics.fill(px, nextY, px + 1, plotBottom + 1, crashed ? 0x245C2845 : 0x243C3973);
                graphics.fill(px, Math.min(oldY, nextY) - 1, px + 1, Math.max(oldY, nextY) + 2, tint);
                oldY = nextY;
            }
            graphics.fill(endX - 2, oldY - 2, endX + 3, oldY + 3, tint);
            if (crashed && crashedNanos != 0) {
                double effect = Math.clamp((double) (now - crashedNanos) / CRASH_EFFECT_NANOS, 0, 1);
                if (effect < 1) {
                    int alpha = (int) ((1 - effect) * 150);
                    for (int particle = 0; particle < 10; particle++) {
                        double angle = particle * Math.PI * 2 / 10;
                        int particleX = endX + (int) (Math.cos(angle) * effect * 26);
                        int particleY = oldY + (int) (Math.sin(angle) * effect * 16 + effect * effect * 16);
                        if (particleX > x + 2 && particleX < x + width - 3
                                && particleY > y + 2 && particleY < y + height - 3) {
                            graphics.fill(particleX, particleY, particleX + 2, particleY + 2,
                                    (alpha << 24) | 0xFF648C);
                        }
                    }
                    graphics.outline(x + 1, y + 1, width - 2, height - 2, (alpha << 24) | 0xFF648C);
                }
            }
        }
        String label = multiplier(cents);
        int labelWidth = font.width(label) * 2;
        graphics.fill(x + width / 2 - labelWidth / 2 - 5, y + 14,
                x + width / 2 + labelWidth / 2 + 5, y + 38, 0xD0181B2D);
        graphics.pose().pushMatrix();
        graphics.pose().translate((float) (x + width / 2), (float) (y + 18));
        graphics.pose().scale(2, 2);
        graphics.centeredText(font, label, 0, 0, snapshot.phase() == Phase.BETTING ? MUTED : tint);
        graphics.pose().popMatrix();
        Component status = switch (snapshot.status()) {
            case NONE -> text(snapshot.phase() == Phase.BETTING ? "ready" : "next_round");
            case QUEUED -> text("queued");
            case ACTIVE -> text("active");
            case CASHED_OUT -> text("won");
            case LOST -> text("lost");
        };
        graphics.centeredText(font, status, x + width / 2, y + 48,
                snapshot.status() == BetStatus.CASHED_OUT ? TEAL : snapshot.status() == BetStatus.LOST ? RED : MUTED);
        graphics.text(font, "0", plotLeft, y + height - 10, MUTED, false);
        graphics.text(font, text("seconds", String.format(Locale.ROOT, "%.1f",
                Math.max(0, snapshot.phaseTicks()) / 20.0)), plotRight - 37, y + height - 10, MUTED, false);
    }

    private void drawInventory(GuiGraphicsExtractor graphics) {
        int x = leftPos;
        int y = topPos;
        graphics.text(font, playerInventoryTitle, x + 79, y + 153, MUTED, false);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) slotFrame(graphics, x + 79 + column * 18, y + 164 + row * 18);
        }
        for (int column = 0; column < 9; column++) slotFrame(graphics, x + 79 + column * 18, y + 222);
    }

    private static void slotFrame(GuiGraphicsExtractor graphics, int iconX, int iconY) {
        graphics.fill(iconX - 1, iconY - 1, iconX + 17, iconY + 17, 0xFF4A526A);
        graphics.fill(iconX, iconY, iconX + 16, iconY + 16, 0xFF191E30);
    }

    private static void card(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xFF171C2D);
        graphics.outline(x, y, width, height, 0xFF353D57);
    }

    private static String multiplier(double cents) {
        return String.format(Locale.ROOT, "%.2f×", Math.max(0, cents) / 100.0);
    }

    private static Component text(String suffix, Object... arguments) {
        return Component.translatable("screen.crashdrop." + suffix, arguments);
    }

    private final class NeonButton extends AbstractButton {
        private final boolean teal;
        private final Runnable action;

        private NeonButton(int x, int y, int width, int height, Component message, boolean teal, Runnable action) {
            super(x, y, width, height, message);
            this.teal = teal;
            this.action = action;
        }

        @Override
        public void onPress(InputWithModifiers input) {
            if (active) action.run();
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            int accent = teal ? TEAL : PURPLE;
            int fill = !active ? 0xFF242A3D : teal ? 0xFF155647 : 0xFF56358B;
            if (active && isHoveredOrFocused()) fill = teal ? 0xFF207963 : 0xFF7245B8;
            graphics.fill(getX(), getY(), getRight(), getBottom(), fill);
            graphics.outline(getX(), getY(), getWidth(), getHeight(), active ? accent : 0xFF434B63);
            graphics.centeredText(font, getMessage(), getX() + getWidth() / 2,
                    getY() + (getHeight() - font.lineHeight) / 2, active ? WHITE : MUTED);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narration) {
            defaultButtonNarrationText(narration);
        }
    }
}
