package io.github.ragamuffincat.swiftload.gametest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

// Reads raw chunk payloads from region files without Minecraft's classes, and so without Swiftload's hooks
final class RegionScan {

    record Chunk(Path regionFile, int x, int z, byte version, byte[] payload) {
    }

    record Storage(Path folder, String type, List<Chunk> chunks, int external) {
    }

    private static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private RegionScan() {
    }

    // Every folder with .mca files: chunks, entities and poi of every dimension
    static List<Storage> scanWorld(Path world) throws IOException {
        List<Path> folders;
        try (Stream<Path> s = Files.walk(world)) {
            folders = s.filter(p -> p.getFileName().toString().endsWith(".mca")).map(Path::getParent).distinct().sorted().toList();
        }
        List<Storage> out = new ArrayList<>();
        for (Path folder : folders) {
            List<Chunk> chunks = new ArrayList<>();
            int external = 0;
            try (Stream<Path> s = Files.list(folder)) {
                for (Path f : s.sorted().toList()) {
                    Matcher m = NAME.matcher(f.getFileName().toString());
                    if (!m.matches() || Files.size(f) < 8192) {
                        continue;
                    }
                    external += read(f, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), chunks);
                }
            }
            out.add(new Storage(folder, folder.getFileName().toString(), chunks, external));
        }
        return out;
    }

    // External chunks are skipped
    static List<Chunk> readFile(Path file) throws IOException {
        Matcher m = NAME.matcher(file.getFileName().toString());
        if (!m.matches()) {
            throw new IllegalArgumentException(file.toString());
        }
        List<Chunk> out = new ArrayList<>();
        read(file, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), out);
        return out;
    }

    private static int read(Path file, int rx, int rz, List<Chunk> out) throws IOException {
        int external = 0;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(4096);
            ch.read(header, 0);
            header.flip();
            for (int i = 0; i < 1024; i++) {
                int offset = header.getInt(i * 4);
                if (offset == 0) {
                    continue;
                }
                int sector = offset >>> 8, count = offset & 0xFF;
                ByteBuffer data = ByteBuffer.allocate(count * 4096);
                ch.read(data, (long) sector * 4096);
                data.flip();
                if (data.remaining() < 5) {
                    continue;
                }
                int length = data.getInt();
                byte version = data.get();
                if ((version & 0x80) != 0) {
                    external++;
                    continue;
                }
                if (length - 1 > data.remaining() || length <= 0) {
                    continue;
                }
                byte[] payload = new byte[length - 1];
                data.get(payload);
                out.add(new Chunk(file, rx * 32 + (i & 31), rz * 32 + (i >> 5), version, payload));
            }
        }
        return external;
    }
}
