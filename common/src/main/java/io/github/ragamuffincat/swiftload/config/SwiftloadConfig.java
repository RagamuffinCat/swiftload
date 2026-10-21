package io.github.ragamuffincat.swiftload.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;

// config/swiftload.properties, one switch per fast path; -Dswiftload.<key>=true|false overrides it.
// Bad keys or values are reported and replaced by the default. An existing file is never rewritten.
public final class SwiftloadConfig {

    public enum Key {
        natives(true, "Use the bundled libdeflate. Off means java.util.zip everywhere, same results but slower."),
        regionInflate(true, "Decompress region chunks with libdeflate (needs natives)."),
        fastNbt(true, "Parse chunks and gzip files in one pass."),
        fastPalette(true, "Decode block and biome palettes directly instead of through DataFixerUpper codecs."),
        gzipFiles(true, "Decompress level.dat, player data, maps and structure templates with libdeflate (needs natives)."),
        fastWrite(true, "Compress region chunks and gzip files with libdeflate when saving (needs natives)."),
        debugStats(false, "Log hits, fallbacks and time per step when the server stops.");

        final boolean defaultValue;
        final String comment;

        Key(boolean defaultValue, String comment) {
            this.defaultValue = defaultValue;
            this.comment = comment;
        }

        public boolean defaultValue() {
            return defaultValue;
        }

        public String property() {
            return "swiftload." + name();
        }
    }

    public static final String FILE_NAME = "swiftload.properties";

    private final EnumMap<Key, Boolean> values;
    private final EnumMap<Key, String> sources;

    private SwiftloadConfig(EnumMap<Key, Boolean> values, EnumMap<Key, String> sources) {
        this.values = values;
        this.sources = sources;
    }

    public boolean get(Key key) {
        return values.get(key);
    }

    // "default", "file" or "jvm"
    public String source(Key key) {
        return sources.get(key);
    }

    // For the startup line, e.g. "fastNbt=false (jvm)"
    public List<String> nonDefaults() {
        List<String> out = new ArrayList<>();
        for (Key k : Key.values()) {
            if (values.get(k) != k.defaultValue) {
                out.add(k.name() + "=" + values.get(k) + " (" + sources.get(k) + ")");
            }
        }
        return out;
    }

    public static SwiftloadConfig defaults() {
        EnumMap<Key, Boolean> v = new EnumMap<>(Key.class);
        EnumMap<Key, String> s = new EnumMap<>(Key.class);
        for (Key k : Key.values()) {
            v.put(k, k.defaultValue);
            s.put(k, "default");
        }
        return new SwiftloadConfig(v, s);
    }

    // Plain unit tests have no loader, so defaults plus JVM overrides
    public static SwiftloadConfig fromSystemProperties(Function<String, String> systemProperty, Consumer<String> warn) {
        return resolve(new Properties(), "<no file>", systemProperty, warn);
    }

    // Creates the file with commented defaults if it's missing. warn gets each problem once, as a sentence.
    public static SwiftloadConfig load(Path file, Function<String, String> systemProperty, Consumer<String> warn) {
        Properties fromFile = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                fromFile.load(r);
            } catch (IOException | IllegalArgumentException e) {
                warn.accept("Could not read " + file + " (" + e + "); using defaults.");
                fromFile.clear();
            }
        } else if (!Files.exists(file)) {
            try {
                writeDefaults(file);
            } catch (IOException e) {
                warn.accept("Could not create " + file + " (" + e + "); using defaults.");
            }
        }
        return resolve(fromFile, file.toString(), systemProperty, warn);
    }

    private static SwiftloadConfig resolve(Properties fromFile, String file, Function<String, String> systemProperty,
                                           Consumer<String> warn) {
        TreeSet<String> unknown = new TreeSet<>(fromFile.stringPropertyNames());
        EnumMap<Key, Boolean> values = new EnumMap<>(Key.class);
        EnumMap<Key, String> sources = new EnumMap<>(Key.class);
        for (Key k : Key.values()) {
            unknown.remove(k.name());
            boolean value = k.defaultValue;
            String source = "default";

            String raw = fromFile.getProperty(k.name());
            if (raw != null) {
                Boolean parsed = parse(raw);
                if (parsed == null) {
                    warn.accept("Invalid value '" + raw.trim() + "' for " + k.name() + " in " + file
                            + " (expected true or false); using " + k.defaultValue + ".");
                } else {
                    value = parsed;
                    source = "file";
                }
            }

            String jvm = systemProperty.apply(k.property());
            if (jvm != null) {
                Boolean parsed = parse(jvm);
                if (parsed == null) {
                    warn.accept("Invalid value '" + jvm.trim() + "' for -D" + k.property()
                            + " (expected true or false); ignoring it.");
                } else {
                    value = parsed;
                    source = "jvm";
                }
            }
            values.put(k, value);
            sources.put(k, source);
        }
        if (!unknown.isEmpty()) {
            warn.accept("Unknown key(s) " + unknown + " in " + file + " ignored. Known keys: " + knownKeys() + ".");
        }
        return new SwiftloadConfig(values, sources);
    }

    static Boolean parse(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static String knownKeys() {
        List<String> names = new ArrayList<>();
        for (Key k : Key.values()) {
            names.add(k.name());
        }
        return String.join(", ", names);
    }

    static void writeDefaults(Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Swiftload settings. Nothing here needs changing, a switch that's off runs Minecraft's own code.\n");
        sb.append("# -Dswiftload.<key>=false on the command line overrides this file. Restart after editing.\n");
        for (Key k : Key.values()) {
            sb.append('\n').append("# ").append(k.comment).append('\n');
            sb.append(k.name()).append('=').append(k.defaultValue).append('\n');
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
        try {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            // Fine if another instance created it first
            if (!Files.exists(file)) {
                throw e;
            }
        }
    }

    public Map<Key, Boolean> asMap() {
        return new EnumMap<>(values);
    }
}
