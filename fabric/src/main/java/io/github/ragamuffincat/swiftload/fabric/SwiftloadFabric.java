package io.github.ragamuffincat.swiftload.fabric;

import io.github.ragamuffincat.swiftload.Swiftload;
import net.fabricmc.api.ModInitializer;

public final class SwiftloadFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        Swiftload.init();
    }
}
