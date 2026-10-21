package io.github.ragamuffincat.swiftload.gametest;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

// Registries have to be bootstrapped before Level and friends are touched
final class Boot {

    private Boot() {
    }

    static synchronized void init() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
}
