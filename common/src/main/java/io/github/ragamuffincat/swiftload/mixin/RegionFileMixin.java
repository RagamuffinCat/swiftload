package io.github.ragamuffincat.swiftload.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.ragamuffincat.swiftload.Features;
import io.github.ragamuffincat.swiftload.io.LazyInflaterInputStream;
import io.github.ragamuffincat.swiftload.io.SwiftDataInputStream;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.InputStream;

@Mixin(RegionFile.class)
public abstract class RegionFileMixin {

    // Deflate chunks stored inside the region file arrive as a ByteArrayInputStream. External .mcc chunks and other
    // compression types stay vanilla.
    // TODO .mcc too? Only chunks over 1 MiB compressed go there, the test world has none
    @WrapOperation(method = "createChunkInputStream", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/RegionFileVersion;wrap(Ljava/io/InputStream;)Ljava/io/InputStream;"))
    private InputStream swiftload$lazyInflate(RegionFileVersion version, InputStream in, Operation<InputStream> original) {
        if (Features.REGION_INFLATE && version == RegionFileVersion.VERSION_DEFLATE && in instanceof ByteArrayInputStream) {
            return new LazyInflaterInputStream(in, LazyInflaterInputStream.Format.ZLIB, compressed -> original.call(version, compressed));
        }
        return original.call(version, in);
    }

    // Both new DataInputStream sites; only our stream is changed
    @WrapOperation(method = "createChunkInputStream", at = @At(value = "NEW", target = "(Ljava/io/InputStream;)Ljava/io/DataInputStream;"))
    private DataInputStream swiftload$dataStream(InputStream in, Operation<DataInputStream> original) {
        return in instanceof LazyInflaterInputStream lazy ? new SwiftDataInputStream(lazy) : original.call(in);
    }
}
