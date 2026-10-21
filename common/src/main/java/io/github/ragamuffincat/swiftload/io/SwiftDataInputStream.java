package io.github.ragamuffincat.swiftload.io;

import java.io.DataInputStream;

// The DataInputStream vanilla builds around its decompressing stream, around ours instead. Mods calling
// RegionFile.getChunkDataInputStream see a normal stream; NbtIoMixin recognizes it and parses the array in one pass.
public final class SwiftDataInputStream extends DataInputStream {

    private final LazyInflaterInputStream source;

    public SwiftDataInputStream(LazyInflaterInputStream source) {
        super(source);
        this.source = source;
    }

    public LazyInflaterInputStream source() {
        return source;
    }
}
