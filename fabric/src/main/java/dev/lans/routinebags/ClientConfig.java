package dev.lans.routinebags;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Function;

import net.fabricmc.loader.api.FabricLoader;

public final class ClientConfig {
    public static final Value<Integer> OPS_PER_TICK = integer("opsPerTick", 2, 1, 10);
    public static final Value<Integer> STEP_DELAY_TICKS = integer("stepDelayTicks", 1, 0, 10);
    public static final Value<Integer> MAX_SORT_STEPS = integer("maxSortSteps", 400, 50, 5000);
    public static final Value<Boolean> SHOW_READ_ONLY = bool("showReadOnlyContainers", true);
    public static final Value<Boolean> MOUNT_IN_CONTAINER_SCREENS = bool("mountInContainerScreens", true);
    public static final Value<Boolean> MOUNTED_PANEL_OPEN_BY_DEFAULT = bool("mountedPanelOpenByDefault", false);
    public static final Value<SortMode> SORT_MODE = value("sortMode", SortMode.BY_CREATIVE,
            raw -> SortMode.valueOf(raw.toUpperCase(Locale.ROOT)));

    private static final Properties VALUES = new Properties();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("routinebags-client.properties");

    public static void load() {
        if (Files.isRegularFile(PATH)) {
            try (Reader reader = Files.newBufferedReader(PATH)) {
                VALUES.load(reader);
            } catch (IOException e) {
                RoutineBags.LOGGER.warn("Could not read Fabric client config {}", PATH, e);
            }
        }

        boolean changed = false;
        for (Value<?> value : Value.ALL) {
            changed |= value.load(VALUES);
        }
        if (changed || !Files.isRegularFile(PATH)) {
            save();
        }
    }

    private static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(PATH)) {
                VALUES.store(writer, "Routine Bags Fabric client config");
            }
        } catch (IOException e) {
            RoutineBags.LOGGER.warn("Could not write Fabric client config {}", PATH, e);
        }
    }

    private static Value<Integer> integer(String key, int fallback, int min, int max) {
        return value(key, fallback, raw -> Math.clamp(Integer.parseInt(raw), min, max));
    }

    private static Value<Boolean> bool(String key, boolean fallback) {
        return value(key, fallback, raw -> switch (raw.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Not a boolean: " + raw);
        });
    }

    private static <T> Value<T> value(String key, T fallback, Function<String, T> parser) {
        return new Value<>(key, fallback, parser);
    }

    public static final class Value<T> {
        private static final java.util.List<Value<?>> ALL = new java.util.ArrayList<>();

        private final String key;
        private final T fallback;
        private final Function<String, T> parser;
        private T current;

        private Value(String key, T fallback, Function<String, T> parser) {
            this.key = key;
            this.fallback = fallback;
            this.parser = parser;
            this.current = fallback;
            ALL.add(this);
        }

        public T get() {
            return this.current;
        }

        private boolean load(Properties properties) {
            String raw = properties.getProperty(this.key);
            boolean changed = raw == null;
            if (raw != null) {
                try {
                    this.current = this.parser.apply(raw.trim());
                } catch (RuntimeException e) {
                    RoutineBags.LOGGER.warn("Invalid value '{}' for {}; using {}", raw, this.key, this.fallback);
                    this.current = this.fallback;
                    changed = true;
                }
            }
            String normalized = String.valueOf(this.current);
            changed |= !normalized.equals(raw == null ? null : raw.trim());
            properties.setProperty(this.key, normalized);
            return changed;
        }
    }

    private ClientConfig() {}
}
