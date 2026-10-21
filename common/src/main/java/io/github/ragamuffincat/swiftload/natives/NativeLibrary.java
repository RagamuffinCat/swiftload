package io.github.ragamuffincat.swiftload.natives;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Properties;

// Extracts the bundled libdeflate to <cacheRoot>/<first 16 hex of its SHA-256>/ and checks it against
// natives.properties, in the jar and on disk. One folder per build, since Windows can't replace a DLL another game
// instance has loaded, and the same build is reused across launches.
public final class NativeLibrary {

    public static final String MANIFEST_RESOURCE = "natives/natives.properties";

    public sealed interface Located permits Found, Unavailable {
    }

    // source is override, cache or extracted
    public record Found(Path path, String platform, String version, String source) implements Located {
    }

    public record Unavailable(String reason) implements Located {
    }

    private NativeLibrary() {
    }

    public static Located locate(Path cacheRoot, ClassLoader resources) {
        String override = System.getProperty("swiftload.natives.path");
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            return Files.isRegularFile(p)
                    ? new Found(p, "override", "unknown", "override")
                    : new Unavailable("-Dswiftload.natives.path points to a missing file: " + p);
        }

        Optional<NativePlatform> detected = NativePlatform.current();
        if (detected.isEmpty()) {
            return new Unavailable("no bundled library for " + System.getProperty("os.name")
                    + " / " + System.getProperty("os.arch"));
        }
        NativePlatform platform = detected.get();

        try {
            Properties manifest = new Properties();
            try (InputStream in = resources.getResourceAsStream(MANIFEST_RESOURCE)) {
                if (in == null) {
                    return new Unavailable("missing " + MANIFEST_RESOURCE);
                }
                manifest.load(in);
            }
            String entry = manifest.getProperty(platform.id());
            String version = manifest.getProperty("libdeflate.version", "unknown");
            if (entry == null) {
                return new Unavailable("no manifest entry for " + platform.id());
            }
            String expected = parseSha256(entry);
            if (expected == null) {
                return new Unavailable("malformed manifest entry for " + platform.id());
            }

            byte[] bytes;
            try (InputStream in = resources.getResourceAsStream(platform.resourcePath())) {
                if (in == null) {
                    return new Unavailable("missing resource " + platform.resourcePath());
                }
                bytes = in.readAllBytes();
            }
            if (!expected.equals(sha256(bytes))) {
                return new Unavailable("bundled " + platform.resourcePath() + " failed its SHA-256 check");
            }

            Path dir = cacheRoot.resolve(expected.substring(0, 16));
            Path target = dir.resolve(platform.libraryFileName());
            if (isIntact(target, expected)) {
                return new Found(target, platform.id(), version, "cache");
            }

            Files.createDirectories(dir);
            Path part = Files.createTempFile(dir, platform.libraryFileName(), ".part");
            try {
                Files.write(part, bytes);
                try {
                    Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (FileAlreadyExistsException | AccessDeniedException raced) {
                    // Another instance extracted (and maybe loaded) it first
                    if (!isIntact(target, expected)) {
                        throw raced;
                    }
                }
            } finally {
                Files.deleteIfExists(part);
            }
            if (!isIntact(target, expected)) {
                return new Unavailable("extracted file failed verification: " + target);
            }
            return new Found(target, platform.id(), version, "extracted");
        } catch (IOException | RuntimeException e) {
            return new Unavailable("extraction failed: " + e);
        }
    }

    private static boolean isIntact(Path file, String expectedSha256) {
        try {
            return Files.isRegularFile(file) && expectedSha256.equals(sha256(Files.readAllBytes(file)));
        } catch (IOException e) {
            return false;
        }
    }

    // "libdeflate.so sha256=<hex>"
    static String parseSha256(String entry) {
        int i = entry.indexOf("sha256=");
        if (i < 0) {
            return null;
        }
        String hex = entry.substring(i + 7).trim();
        return hex.matches("[0-9a-f]{64}") ? hex : null;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
