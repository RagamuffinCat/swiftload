package io.github.ragamuffincat.swiftload.natives;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class NativeLibraryTest {

    private static final ClassLoader CL = NativeLibraryTest.class.getClassLoader();

    @Test
    void extractsOnceThenReusesTheCache(@TempDir Path root) {
        var first = assertInstanceOf(NativeLibrary.Found.class, NativeLibrary.locate(root, CL));
        assertEquals("extracted", first.source());
        var second = assertInstanceOf(NativeLibrary.Found.class, NativeLibrary.locate(root, CL));
        assertEquals("cache", second.source());
        assertEquals(first.path(), second.path());
        // <root>/<16 hex>/<file>
        assertEquals(16, first.path().getParent().getFileName().toString().length());
        assertEquals("v1.26", first.version());
    }

    @Test
    void replacesATamperedCachedFile(@TempDir Path root) throws Exception {
        var found = assertInstanceOf(NativeLibrary.Found.class, NativeLibrary.locate(root, CL));
        Files.write(found.path(), new byte[]{1, 2, 3});
        var again = assertInstanceOf(NativeLibrary.Found.class, NativeLibrary.locate(root, CL));
        assertEquals("extracted", again.source());
        assertEquals(found.path(), again.path());
    }

    @Test
    void leavesNoPartFilesBehind(@TempDir Path root) throws Exception {
        NativeLibrary.locate(root, CL);
        try (var files = Files.walk(root)) {
            assertEquals(0, files.filter(p -> p.toString().endsWith(".part")).count());
        }
    }

    @Test
    void missingManifest(@TempDir Path root) {
        ClassLoader empty = new ClassLoader(null) {
        };
        var u = assertInstanceOf(NativeLibrary.Unavailable.class, NativeLibrary.locate(root, empty));
        assertEquals(true, u.reason().contains("natives.properties"), u.reason());
    }

    @Test
    void parsesManifestEntries() {
        assertEquals("ab".repeat(32), NativeLibrary.parseSha256("libdeflate.so sha256=" + "ab".repeat(32)));
        assertNull(NativeLibrary.parseSha256("libdeflate.so"));
        assertNull(NativeLibrary.parseSha256("libdeflate.so sha256=xyz"));
    }
}
