package io.github.ragamuffincat.swiftload.natives;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativePlatformTest {

    @Test
    void mapsEveryShippedPlatform() {
        assertEquals(Optional.of(NativePlatform.WINDOWS_X86_64), NativePlatform.detect("Windows 11", "amd64"));
        assertEquals(Optional.of(NativePlatform.WINDOWS_AARCH64), NativePlatform.detect("Windows 11", "aarch64"));
        assertEquals(Optional.of(NativePlatform.LINUX_X86_64), NativePlatform.detect("Linux", "amd64"));
        assertEquals(Optional.of(NativePlatform.LINUX_X86_64), NativePlatform.detect("Linux", "x86_64"));
        assertEquals(Optional.of(NativePlatform.LINUX_AARCH64), NativePlatform.detect("Linux", "aarch64"));
        assertEquals(Optional.of(NativePlatform.MACOS_X86_64), NativePlatform.detect("Mac OS X", "x86_64"));
        assertEquals(Optional.of(NativePlatform.MACOS_AARCH64), NativePlatform.detect("Mac OS X", "aarch64"));
        assertEquals(Optional.of(NativePlatform.MACOS_AARCH64), NativePlatform.detect("Darwin", "arm64"));
    }

    @Test
    void unsupportedPlatformsFallBack() {
        assertEquals(Optional.empty(), NativePlatform.detect("Linux", "riscv64"));
        assertEquals(Optional.empty(), NativePlatform.detect("Linux", "x86"));
        assertEquals(Optional.empty(), NativePlatform.detect("FreeBSD", "amd64"));
        assertEquals(Optional.empty(), NativePlatform.detect("", ""));
    }

    @Test
    void resourcePaths() {
        assertEquals("natives/windows-x86_64/deflate.dll", NativePlatform.WINDOWS_X86_64.resourcePath());
        assertEquals("natives/linux-aarch64/libdeflate.so", NativePlatform.LINUX_AARCH64.resourcePath());
        assertEquals("natives/macos-aarch64/libdeflate.dylib", NativePlatform.MACOS_AARCH64.resourcePath());
    }

    @Test
    void everyPlatformIsShipped() throws Exception {
        Properties props = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(NativeLibrary.MANIFEST_RESOURCE)) {
            props.load(in);
        }
        for (NativePlatform p : NativePlatform.values()) {
            String entry = props.getProperty(p.id());
            assertEquals(true, entry != null && entry.startsWith(p.libraryFileName() + " "), "manifest entry for " + p);
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(p.resourcePath())) {
                assertEquals(true, in != null, "bundled library for " + p);
                assertEquals(NativeLibrary.parseSha256(entry), NativeLibrary.sha256(in.readAllBytes()), "hash of " + p);
            }
        }
    }
}
