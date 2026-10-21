package io.github.ragamuffincat.swiftload.platform;

import java.nio.file.Path;

public interface PlatformHelper {

    String getPlatformName();

    /** Where config/, mods/ and saves/ live. */
    Path getGameDirectory();

    Path getConfigDirectory();
}
