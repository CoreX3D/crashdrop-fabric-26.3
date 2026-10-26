package dev.core.crashdrop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Server settings. Take a {@link #copy()} when a round starts. */
public final class CrashDropConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("crashdrop");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public double houseEdge = 0.03;
    public double maxMultiplier = 64.0;
    public double growthPerSecond = 0.06;
    public int maxStakeItems = 64;
    public int maxPayoutItems = 4096;
    public Set<String> blacklist = new LinkedHashSet<>(List.of(
            "crashdrop:terminal",
            "minecraft:command_block",
            "minecraft:structure_block",
            "minecraft:barrier",
            "ic2:creative_generator",
            "ic2:debug_item"
    ));

    public static CrashDropConfig load() {
        return load(FabricLoader.getInstance().getConfigDir().resolve("crashdrop.json"));
    }

    public static CrashDropConfig load(Path path) {
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                CrashDropConfig loaded = GSON.fromJson(reader, CrashDropConfig.class);
                if (loaded == null) throw new JsonParseException("The configuration is empty");
                return loaded.normalizedCopy();
            } catch (IOException | JsonParseException | IllegalStateException exception) {
                LOGGER.warn("Cannot read CrashDrop settings at {}. Using defaults and preserving the existing file.",
                        path, exception);
                return new CrashDropConfig().normalize();
            }
        }

        CrashDropConfig defaults = new CrashDropConfig().normalize();
        try {
            defaults.save(path);
        } catch (IOException exception) {
            LOGGER.warn("Cannot write default CrashDrop settings at {}", path, exception);
        }
        return defaults;
    }

    public void save(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "crashdrop-", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(normalizedCopy()) + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Returns an independent, normalized round snapshot, including an independent blacklist. */
    public CrashDropConfig copy() {
        return normalizedCopy();
    }

    public CrashDropConfig normalizedCopy() {
        CrashDropConfig copy = new CrashDropConfig();
        copy.houseEdge = houseEdge;
        copy.maxMultiplier = maxMultiplier;
        copy.growthPerSecond = growthPerSecond;
        copy.maxStakeItems = maxStakeItems;
        copy.maxPayoutItems = maxPayoutItems;
        copy.blacklist = blacklist == null ? null : new LinkedHashSet<>(blacklist);
        return copy.normalize();
    }

    public CrashDropConfig normalize() {
        houseEdge = finiteClamp(houseEdge, 0.03, 0.0, 0.95);
        maxMultiplier = finiteClamp(maxMultiplier, 64.0, 1.01, 100.0);
        maxMultiplier = Math.round(maxMultiplier * 100.0) / 100.0;
        growthPerSecond = finiteClamp(growthPerSecond, 0.06, 0.005, 1.0);
        maxStakeItems = Math.clamp(maxStakeItems, 1, 64);
        // A valid wager must never be silently capped below its configured maximum return.
        // A previously saved fractional item can round the next successful payout up by one.
        int largestPayout = (int) (((long) maxStakeItems * maxMultiplierCents() + 99L) / 100L);
        maxPayoutItems = Math.clamp(maxPayoutItems, largestPayout, 1_000_000);

        Set<String> cleaned = new LinkedHashSet<>();
        Set<String> configured = blacklist == null ? new CrashDropConfig().blacklist : blacklist;
        for (String item : configured) {
            if (item == null) continue;
            String id = item.strip();
            if (id.length() <= 256 && ITEM_ID.matcher(id).matches()) cleaned.add(id);
            if (cleaned.size() >= 1024) break;
        }
        blacklist = cleaned;
        return this;
    }

    public int maxMultiplierCents() {
        return (int) Math.round(finiteClamp(maxMultiplier, 64.0, 1.01, 100.0) * 100.0);
    }

    private static double finiteClamp(double value, double fallback, double minimum, double maximum) {
        return Double.isFinite(value) ? Math.clamp(value, minimum, maximum) : fallback;
    }
}
