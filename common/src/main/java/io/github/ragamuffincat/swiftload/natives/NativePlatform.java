package io.github.ragamuffincat.swiftload.natives;

import java.util.Locale;
import java.util.Optional;

// The folders under natives/ in the jar. 64-bit only, like Minecraft itself; anything else uses java.util.zip.
// TODO musl (Alpine) also lands on linux-*, the glibc build fails to load there and it ends up on java.util.zip anyway
public enum NativePlatform {

    WINDOWS_X86_64("windows-x86_64", "deflate.dll"),
    WINDOWS_AARCH64("windows-aarch64", "deflate.dll"),
    LINUX_X86_64("linux-x86_64", "libdeflate.so"),
    LINUX_AARCH64("linux-aarch64", "libdeflate.so"),
    MACOS_X86_64("macos-x86_64", "libdeflate.dylib"),
    MACOS_AARCH64("macos-aarch64", "libdeflate.dylib");

    private final String id;
    private final String libraryFileName;

    NativePlatform(String id, String libraryFileName) {
        this.id = id;
        this.libraryFileName = libraryFileName;
    }

    public String id() {
        return id;
    }

    public String libraryFileName() {
        return libraryFileName;
    }

    public String resourcePath() {
        return "natives/" + id + "/" + libraryFileName;
    }

    public static Optional<NativePlatform> current() {
        return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static Optional<NativePlatform> detect(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);

        boolean x64 = arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        if (!x64 && !arm64) {
            return Optional.empty();
        }
        if (os.startsWith("windows")) {
            return Optional.of(x64 ? WINDOWS_X86_64 : WINDOWS_AARCH64);
        }
        if (os.startsWith("linux")) {
            return Optional.of(x64 ? LINUX_X86_64 : LINUX_AARCH64);
        }
        if (os.startsWith("mac") || os.startsWith("darwin")) {
            return Optional.of(x64 ? MACOS_X86_64 : MACOS_AARCH64);
        }
        return Optional.empty();
    }
}
