package io.github.ragamuffincat.swiftload.config;

import io.github.ragamuffincat.swiftload.config.SwiftloadConfig.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwiftloadConfigTest {

    private static final Function<String, String> NO_JVM = k -> null;

    @TempDir
    Path dir;

    @Test
    void firstStartWritesDefaults() throws IOException {
        Path file = dir.resolve("config").resolve(SwiftloadConfig.FILE_NAME);
        List<String> warnings = new ArrayList<>();
        SwiftloadConfig c = SwiftloadConfig.load(file, NO_JVM, warnings::add);

        assertTrue(Files.isRegularFile(file));
        assertEquals(List.of(), warnings);
        for (Key k : Key.values()) {
            assertEquals(k.defaultValue(), c.get(k), k.name());
            assertEquals("default", c.source(k));
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        for (Key k : Key.values()) {
            assertTrue(text.contains("\n" + k.name() + "=" + k.defaultValue() + "\n"), k.name());
        }
        assertTrue(text.lines().filter(l -> l.startsWith("#")).count() >= Key.values().length, "every key is commented");

        // The written file reads back to the same values, as file values, without warnings
        SwiftloadConfig again = SwiftloadConfig.load(file, NO_JVM, warnings::add);
        assertEquals(List.of(), warnings);
        assertEquals(c.asMap(), again.asMap());
        assertEquals("file", again.source(Key.fastNbt));
        assertEquals(List.of(), again.nonDefaults());
    }

    @Test
    void allOnExceptDebugStats() {
        Map<Key, Boolean> d = SwiftloadConfig.defaults().asMap();
        assertEquals(Map.of(Key.natives, true, Key.regionInflate, true, Key.fastNbt, true, Key.fastPalette, true,
                Key.gzipFiles, true, Key.fastWrite, true, Key.debugStats, false), d);
    }

    @Test
    void editedFileIsNotRewritten() throws IOException {
        Path file = dir.resolve(SwiftloadConfig.FILE_NAME);
        String text = "# mine\nfastNbt = false\ndebugStats=TRUE\n";
        Files.writeString(file, text);
        List<String> warnings = new ArrayList<>();
        SwiftloadConfig c = SwiftloadConfig.load(file, NO_JVM, warnings::add);

        assertEquals(List.of(), warnings);
        assertFalse(c.get(Key.fastNbt));
        assertTrue(c.get(Key.debugStats));
        assertTrue(c.get(Key.regionInflate), "missing keys keep their default");
        assertEquals(text, Files.readString(file), "existing file untouched");
        assertEquals(List.of("fastNbt=false (file)", "debugStats=true (file)"), c.nonDefaults());
    }

    @Test
    void jvmPropertiesOverrideTheFile() throws IOException {
        Path file = dir.resolve(SwiftloadConfig.FILE_NAME);
        Files.writeString(file, "fastPalette=false\ngzipFiles=false\n");
        Properties jvm = new Properties();
        jvm.setProperty("swiftload.fastPalette", "true");
        jvm.setProperty("swiftload.natives", " False ");
        SwiftloadConfig c = SwiftloadConfig.load(file, jvm::getProperty, w -> {
            throw new AssertionError(w);
        });

        assertTrue(c.get(Key.fastPalette));
        assertEquals("jvm", c.source(Key.fastPalette));
        assertFalse(c.get(Key.natives));
        assertFalse(c.get(Key.gzipFiles));
        assertEquals("file", c.source(Key.gzipFiles));
    }

    @Test
    void badValuesAndUnknownKeys() throws IOException {
        Path file = dir.resolve(SwiftloadConfig.FILE_NAME);
        Files.writeString(file, "fastNbt=yes\nfastWrite=1\nturbo=true\nnatives=false\n");
        Properties jvm = new Properties();
        jvm.setProperty("swiftload.regionInflate", "maybe");
        List<String> warnings = new ArrayList<>();
        SwiftloadConfig c = SwiftloadConfig.load(file, jvm::getProperty, warnings::add);

        assertTrue(c.get(Key.fastNbt));
        assertTrue(c.get(Key.fastWrite), "invalid value: the default (true)");
        assertTrue(c.get(Key.regionInflate));
        assertFalse(c.get(Key.natives), "valid keys still apply");
        assertEquals(4, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("'yes'") && w.contains("fastNbt")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("'1'") && w.contains("fastWrite")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("-Dswiftload.regionInflate")), warnings.toString());
        assertTrue(warnings.get(3).contains("[turbo]"), warnings.get(3));
    }

    @Test
    void unwritableLocation() throws IOException {
        Path blocker = dir.resolve("not-a-dir");
        Files.writeString(blocker, "x");
        List<String> warnings = new ArrayList<>();
        SwiftloadConfig c = SwiftloadConfig.load(blocker.resolve(SwiftloadConfig.FILE_NAME), NO_JVM, warnings::add);
        assertEquals(1, warnings.size(), warnings.toString());
        assertEquals(SwiftloadConfig.defaults().asMap(), c.asMap());
    }

    @Test
    void malformedFile() throws IOException {
        Path file = dir.resolve(SwiftloadConfig.FILE_NAME);
        // A malformed unicode escape makes Properties.load throw IllegalArgumentException
        Files.writeString(file, "fastNbt=false\nbad=\\u12\n");
        List<String> warnings = new ArrayList<>();
        SwiftloadConfig c = SwiftloadConfig.load(file, NO_JVM, warnings::add);
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(c.get(Key.fastNbt));
    }
}
