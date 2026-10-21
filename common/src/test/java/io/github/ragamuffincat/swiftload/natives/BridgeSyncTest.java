package io.github.ragamuffincat.swiftload.natives;

import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// NativeBridge.bin has to be exactly what its source compiles to. Fix with ./natives/build-bridge.sh.
class BridgeSyncTest {

    // Gradle passes the common project dir; natives/test-natives.sh runs from the repository root
    private static final Path SOURCE = Path.of(System.getProperty("swiftload.commonDir", "common"))
            .resolve("src/bridge/java/io/github/ragamuffincat/swiftload/natives/bridge/NativeBridge.java");

    @Test
    void bridgeMatchesSource() throws IOException {
        byte[] shipped;
        try (InputStream in = BridgeSyncTest.class.getClassLoader().getResourceAsStream(NativeBinder.BRIDGE_RESOURCE)) {
            assertNotNull(in, NativeBinder.BRIDGE_RESOURCE);
            shipped = in.readAllBytes();
        }
        Path out = Files.createTempDirectory("swiftload-bridge");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = javac.run(null, err, err, "--release", "25", "-d", out.toString(), SOURCE.toString());
        assertEquals(0, rc, err.toString());
        byte[] compiled = Files.readAllBytes(out.resolve("io/github/ragamuffincat/swiftload/natives/bridge/NativeBridge.class"));
        assertArrayEquals(compiled, shipped, "NativeBridge.bin is stale: run ./natives/build-bridge.sh (compiled sha256 "
                + sha(compiled) + ", shipped " + sha(shipped) + ")");
    }

    @Test
    void bridgeIsNotAClass() {
        assertFalse(classExists(NativeBinder.BRIDGE_CLASS), "the bridge must only exist as NativeBridge.bin");
    }

    private static boolean classExists(String name) {
        return BridgeSyncTest.class.getClassLoader().getResource(name.replace('.', '/') + ".class") != null;
    }

    private static String sha(byte[] b) {
        return NativeLibrary.sha256(b).substring(0, 16);
    }
}
