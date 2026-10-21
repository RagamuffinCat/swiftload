package io.github.ragamuffincat.swiftload.testdata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// Chunk NBT laid out like vanilla's. Terrain comes from value noise, so palettes are small and in first-occurrence
// order, bit storage uses vanilla's widths and light and heightmaps are smooth. Random filler compresses far worse
// than real chunks.
// Compound = LinkedHashMap, list = ArrayList, boxed numbers, String, byte[] / int[] / long[].
public final class RealChunkNbt {

    // "name" or "name[k=v,k=v]" with properties sorted by name, the order vanilla writes them in
    static String st(String name, String... kv) {
        if (kv.length == 0) {
            return name;
        }
        TreeMap<String, String> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append(']').toString();
    }

    // Default states of the blocks with properties that this generator emits
    static final Map<String, Map<String, String>> DEFAULT_PROPERTIES = Map.of(
        "minecraft:grass_block", Map.of("snowy", "false"),
        "minecraft:water", Map.of("level", "0"),
        "minecraft:deepslate", Map.of("axis", "y"),
        "minecraft:oak_log", Map.of("axis", "y"),
        "minecraft:oak_leaves", Map.of("distance", "7", "persistent", "false", "waterlogged", "false"),
        "minecraft:snow", Map.of("layers", "1"),
        "minecraft:redstone_ore", Map.of("lit", "false"),
        "minecraft:deepslate_redstone_ore", Map.of("lit", "false"));

    static boolean isDefaultKey(String key) {
        int b = key.indexOf('[');
        if (b < 0) {
            return true;
        }
        Map<String, String> def = DEFAULT_PROPERTIES.get(key.substring(0, b));
        if (def == null) {
            throw new IllegalStateException("no default-state table for " + key);
        }
        for (String kv : key.substring(b + 1, key.length() - 1).split(",")) {
            int eq = kv.indexOf('=');
            if (!kv.substring(eq + 1).equals(def.get(kv.substring(0, eq)))) {
                return false;
            }
        }
        return true;
    }

    // 26.3 format: a default state is the bare block id, any other state {id, properties{...all of them...}}
    static Object paletteEntry26(String key) {
        int b = key.indexOf('[');
        if (isDefaultKey(key)) {
            return b < 0 ? key : key.substring(0, b);
        }
        LinkedHashMap<String, Object> e = new LinkedHashMap<>();
        e.put("id", key.substring(0, b));
        LinkedHashMap<String, Object> p = new LinkedHashMap<>();
        for (String kv : key.substring(b + 1, key.length() - 1).split(",")) {
            int eq = kv.indexOf('=');
            p.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        e.put("properties", p);
        return e;
    }

    // Before 26.3: {Name, Properties{...}?}
    static Map<String, Object> paletteEntry(String key) {
        LinkedHashMap<String, Object> e = new LinkedHashMap<>();
        int b = key.indexOf('[');
        if (b < 0) {
            e.put("Name", key);
            return e;
        }
        e.put("Name", key.substring(0, b));
        LinkedHashMap<String, Object> p = new LinkedHashMap<>();
        for (String kv : key.substring(b + 1, key.length() - 1).split(",")) {
            int eq = kv.indexOf('=');
            p.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        e.put("Properties", p);
        return e;
    }

    static final String AIR = "minecraft:air", STONE = "minecraft:stone", DIRT = "minecraft:dirt",
            SAND = "minecraft:sand", SANDSTONE = "minecraft:sandstone", GRAVEL = "minecraft:gravel",
            BEDROCK = "minecraft:bedrock", ANDESITE = "minecraft:andesite", DIORITE = "minecraft:diorite",
            GRANITE = "minecraft:granite", TUFF = "minecraft:tuff", SHORT_GRASS = "minecraft:short_grass",
            DANDELION = "minecraft:dandelion", POPPY = "minecraft:poppy", SEAGRASS = "minecraft:seagrass";
    static final String DEEPSLATE = st("minecraft:deepslate", "axis", "y");
    static final String WATER = st("minecraft:water", "level", "0");
    static final String GRASS = st("minecraft:grass_block", "snowy", "false");
    static final String GRASS_SNOWY = st("minecraft:grass_block", "snowy", "true");
    static final String SNOW_LAYER = st("minecraft:snow", "layers", "1");
    static final String LOG_Y = st("minecraft:oak_log", "axis", "y");
    static final String[] LEAVES = new String[8];

    static {
        for (int d = 1; d <= 7; d++) {
            LEAVES[d] = st("minecraft:oak_leaves", "distance", String.valueOf(d),
                    "persistent", "false", "waterlogged", "false");
        }
    }

    static final String[] ORES = {
        "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:copper_ore", "minecraft:gold_ore",
        st("minecraft:redstone_ore", "lit", "false"), "minecraft:lapis_ore", "minecraft:diamond_ore"
    };
    static final String[] DEEP_ORES = {
        "minecraft:deepslate_coal_ore", "minecraft:deepslate_iron_ore", "minecraft:deepslate_copper_ore",
        "minecraft:deepslate_gold_ore", st("minecraft:deepslate_redstone_ore", "lit", "false"),
        "minecraft:deepslate_lapis_ore", "minecraft:deepslate_diamond_ore"
    };
    public static final String[] BIOMES = {
        "minecraft:plains", "minecraft:forest", "minecraft:river", "minecraft:dripstone_caves"
    };

    final long seed;
    // false: the pre-26.3 palette format
    final boolean format26;

    public RealChunkNbt(long seed, boolean format26) {
        this.seed = seed;
        this.format26 = format26;
    }

    double hash(int x, int y, int z) {
        long h = seed;
        h = h * 6364136223846793005L + x * 0x9E3779B97F4A7C15L;
        h = h * 6364136223846793005L + y * 0xC2B2AE3D27D4EB4FL;
        h = h * 6364136223846793005L + z * 0x165667B19E3779F9L;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return ((h >>> 11) / (double) (1L << 53)) * 2.0 - 1.0;
    }

    private static double sm(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    double n3(double x, double y, double z) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        double xf = sm(x - xi), yf = sm(y - yi), zf = sm(z - zi);
        return lp(lp(lp(hash(xi, yi, zi), hash(xi + 1, yi, zi), xf),
                        lp(hash(xi, yi + 1, zi), hash(xi + 1, yi + 1, zi), xf), yf),
                lp(lp(hash(xi, yi, zi + 1), hash(xi + 1, yi, zi + 1), xf),
                        lp(hash(xi, yi + 1, zi + 1), hash(xi + 1, yi + 1, zi + 1), xf), yf), zf);
    }

    double fbm(double x, double z, int oct) {
        double s = 0, a = 1, f = 1, n = 0;
        for (int i = 0; i < oct; i++) {
            s += n3(x * f, 0.5, z * f) * a;
            n += a;
            a *= 0.5;
            f *= 2;
        }
        return s / n;
    }

    int surfaceAt(int wx, int wz) {
        return (int) (66 + fbm(wx / 180.0, wz / 180.0, 4) * 42 + fbm(wx / 47.0, wz / 47.0, 3) * 9);
    }

    // About 44% forest over chunks 0..7 at seed 0xC0FFEE, so the corpus has trees: oak leaves are the only
    // palette entries with three properties
    boolean forest(int wx, int wz) {
        return fbm(wx / 120.0 + 11, wz / 120.0 + 11, 2) > 0.0;
    }

    boolean treeAt(int wx, int wz) {
        int gx = Math.floorDiv(wx, 6), gz = Math.floorDiv(wz, 6);
        if (hash(gx, 77, gz) < 0.1) {
            return false;
        }
        int ox = (int) ((hash(gx, 78, gz) + 1) * 2.99), oz = (int) ((hash(gx, 79, gz) + 1) * 2.99);
        if (Math.floorMod(wx, 6) != ox || Math.floorMod(wz, 6) != oz) {
            return false;
        }
        int s = surfaceAt(wx, wz);
        return s > 63 && s <= 96 && forest(wx, wz);
    }

    // 4..6
    int treeHeight(int wx, int wz) {
        return 4 + (int) ((hash(wx, 80, wz) + 1) * 1.49);
    }

    // Trunk or leaves from any tree within 2 columns, null if none
    String treePart(int wx, int y, int wz) {
        String best = null;
        int bestD = 99;
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                int tx = wx + dx, tz = wz + dz;
                if (!treeAt(tx, tz)) {
                    continue;
                }
                int s = surfaceAt(tx, tz), top = s + treeHeight(tx, tz);
                if (dx == 0 && dz == 0 && y > s && y <= top) {
                    return LOG_Y;
                }
                if (y < top - 2 || y > top + 1) {
                    continue;
                }
                int r = (y > top - 1) ? 1 : 2;
                int ax = Math.abs(dx), az = Math.abs(dz);
                if (ax > r || az > r || (ax == 2 && az == 2)) {
                    continue;
                }
                int d = Math.max(1, Math.min(7, Math.max(ax, az) + (y > top ? 1 : 0)));
                if (d < bestD) {
                    bestD = d;
                    best = LEAVES[d];
                }
            }
        }
        return best;
    }

    String blockAt(int wx, int y, int wz, int surface) {
        if (y == -64) {
            return BEDROCK;
        }
        if (y < -59 && (hash(wx, y, wz) * 0.5 + 0.5) < (-59 - y) / 5.0) {
            return BEDROCK;
        }

        if (y > surface) {
            if (y <= 62) {
                return (y == surface + 1 && hash(wx, 5, wz) > 0.55) ? SEAGRASS : WATER;
            }
            if (y > surface + 10) {
                return AIR;
            }
            if (y == surface + 1) {
                if (surface > 96) {
                    return SNOW_LAYER;
                }
                if (treeAt(wx, wz)) {
                    return LOG_Y;
                }
            }
            String t = treePart(wx, y, wz);
            if (t != null) {
                return t;
            }
            if (y == surface + 1 && surface > 63) {
                double v = hash(wx, 3, wz);
                if (v > 0.55) {
                    return SHORT_GRASS;
                }
                if (v < -0.94) {
                    return DANDELION;
                }
                if (v < -0.90) {
                    return POPPY;
                }
            }
            return AIR;
        }

        // Caves
        if (y < surface - 3 && y > -58) {
            double c1 = n3(wx / 34.0, y / 22.0, wz / 34.0);
            double c2 = n3(wx / 19.0 + 100, y / 30.0 + 100, wz / 19.0 + 100);
            if (Math.abs(c1) < 0.075 && Math.abs(c2) < 0.16) {
                return AIR;
            }
        }
        if (y == surface) {
            if (surface <= 63) {
                return SAND;
            }
            if (surface > 96) {
                return GRASS_SNOWY;
            }
            return treeAt(wx, wz) ? DIRT : GRASS;
        }
        if (y > surface - 4) {
            return surface <= 63 ? (y > surface - 2 ? SAND : SANDSTONE) : DIRT;
        }

        boolean deep = y < 0;
        double o = n3(wx / 5.5, y / 5.5, wz / 5.5);
        if (o > 0.70) {
            String[] set = deep ? DEEP_ORES : ORES;
            if (y < -20 && o > 0.86) {
                return set[6];
            }
            if (y < 8) {
                return o > 0.80 ? set[4] : set[5];
            }
            if (y < 40) {
                return o > 0.79 ? set[3] : set[1];
            }
            return o > 0.80 ? set[2] : set[0];
        }
        if (o < -0.72 && y > 20) {
            return GRAVEL;
        }
        String base = deep ? DEEPSLATE : STONE;
        if (!deep) {
            double v = n3(wx / 26.0 + 7, y / 26.0 + 7, wz / 26.0 + 7);
            if (v > 0.42) {
                base = ANDESITE;
            } else if (v < -0.46) {
                base = DIORITE;
            } else if (n3(wx / 31.0 - 5, y / 31.0 - 5, wz / 31.0 - 5) > 0.5) {
                base = GRANITE;
            }
        }
        if (y < 8 && n3(wx / 21.0 + 3, y / 21.0 + 3, wz / 21.0 + 3) > 0.45) {
            base = TUFF;
        }
        return base;
    }

    static int ceilLog2(int n) {
        return n <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(n - 1);
    }

    // SimpleBitStorage's layout: entries never span two longs
    static long[] pack(int[] e, int bits) {
        int per = 64 / bits;
        long[] out = new long[(e.length + per - 1) / per];
        long mask = (1L << bits) - 1;
        for (int i = 0; i < e.length; i++) {
            out[i / per] |= (e[i] & mask) << ((i % per) * bits);
        }
        return out;
    }

    static void setNibble(byte[] a, int i, int v) {
        int bi = i >> 1, shift = (i & 1) * 4;
        a[bi] = (byte) ((a[bi] & ~(15 << shift)) | ((v & 15) << shift));
    }

    public Map<String, Object> buildChunk(int cx, int cz) {
        LinkedHashMap<String, Object> c = new LinkedHashMap<>();
        c.put("DataVersion", 4556);
        c.put("xPos", cx);
        c.put("yPos", -4);
        c.put("zPos", cz);
        c.put("Status", "minecraft:full");
        c.put("LastUpdate", 1_234_567L + cx * 31L + cz);
        c.put("InhabitedTime", (long) (cx * 97 + cz * 13));
        c.put("isLightOn", (byte) 1);

        int[] surf = new int[256];
        int maxSurf = Integer.MIN_VALUE;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                surf[z * 16 + x] = surfaceAt(cx * 16 + x, cz * 16 + z);
                maxSurf = Math.max(maxSurf, surf[z * 16 + x]);
            }
        }

        List<Object> sections = new ArrayList<>();
        String[] cells = new String[4096];
        for (int sy = -4; sy < 20; sy++) {
            LinkedHashMap<String, Object> s = new LinkedHashMap<>();
            s.put("Y", (byte) sy);
            int y0 = sy * 16;

            // Block states: index = (y<<8)|(z<<4)|x, palette in first-occurrence order
            LinkedHashMap<String, Integer> pal = new LinkedHashMap<>();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        String b = blockAt(cx * 16 + x, y0 + y, cz * 16 + z, surf[z * 16 + x]);
                        cells[(y << 8) | (z << 4) | x] = b;
                        pal.putIfAbsent(b, pal.size());
                    }
                }
            }
            List<Object> palette = new ArrayList<>(pal.size());
            for (String key : pal.keySet()) {
                palette.add(format26 ? paletteEntry26(key) : paletteEntry(key));
            }
            LinkedHashMap<String, Object> bs = new LinkedHashMap<>();
            bs.put("palette", palette);
            if (pal.size() > 1) {
                if (pal.size() > 256) {
                    throw new IllegalStateException("global palette not modeled");
                }
                int bits = Math.max(4, ceilLog2(pal.size()));
                int[] idx = new int[4096];
                for (int i = 0; i < 4096; i++) {
                    idx[i] = pal.get(cells[i]);
                }
                bs.put("data", pack(idx, bits));
            }
            s.put("block_states", bs);

            // Biomes: 4x4x4, index = (y<<4)|(z<<2)|x, linear palette at ceilLog2(n) bits
            LinkedHashMap<String, Integer> bpal = new LinkedHashMap<>();
            String[] bcells = new String[64];
            for (int i = 0; i < 64; i++) {
                int bx = (i & 3) * 4, bz = ((i >> 2) & 3) * 4, by = (i >> 4) * 4;
                int wx = cx * 16 + bx, wz = cz * 16 + bz, wy = y0 + by;
                String biome;
                if (wy < 0 && n3(wx / 96.0 + 40, wy / 64.0, wz / 96.0 + 40) > 0.25) {
                    biome = BIOMES[3];
                } else if (surfaceAt(wx, wz) <= 63) {
                    biome = BIOMES[2];
                } else {
                    biome = forest(wx, wz) ? BIOMES[1] : BIOMES[0];
                }
                bcells[i] = biome;
                bpal.putIfAbsent(biome, bpal.size());
            }
            LinkedHashMap<String, Object> bio = new LinkedHashMap<>();
            bio.put("palette", new ArrayList<Object>(bpal.keySet()));
            if (bpal.size() > 1) {
                int[] bi = new int[64];
                for (int i = 0; i < 64; i++) {
                    bi[i] = bpal.get(bcells[i]);
                }
                bio.put("data", pack(bi, ceilLog2(bpal.size())));
            }
            s.put("biomes", bio);

            // Sky light up to just above the terrain, block light only where it isn't zero
            if (y0 <= maxSurf + 16) {
                byte[] sl = new byte[2048];
                for (int i = 0; i < 4096; i++) {
                    int y = i >> 8, z = (i >> 4) & 15, x = i & 15, wy = y0 + y, top = surf[z * 16 + x];
                    setNibble(sl, i, wy > top ? 15 : Math.max(0, 15 - (top - wy)));
                }
                s.put("SkyLight", sl);
            }
            if (y0 < 0 && y0 > -48) {
                byte[] bl = new byte[2048];
                for (int i = 0; i < 4096; i++) {
                    int y = i >> 8;
                    setNibble(bl, i, Math.max(0, 7 - Math.abs(y - 8)));
                }
                s.put("BlockLight", bl);
            }
            sections.add(s);
        }
        c.put("sections", sections);

        // Heightmaps: first air above + 64 (minY), below 512 so 9 bits
        int[] mb = new int[256], ws = new int[256], of = new int[256], mbnl = new int[256];
        for (int i = 0; i < 256; i++) {
            int x = i & 15, z = i >> 4, wx = cx * 16 + x, wz = cz * 16 + z, s = surf[i];
            int top = Math.max(s, 62), treeTop = top;
            for (int y = s + 1; y <= s + 10; y++) {
                if (treePart(wx, y, wz) != null) {
                    treeTop = Math.max(treeTop, y);
                }
            }
            ws[i] = treeTop + 65;
            mb[i] = treeTop + 65;
            mbnl[i] = (treeAt(wx, wz) ? s + treeHeight(wx, wz) : top) + 65;
            of[i] = s + 65;
        }
        LinkedHashMap<String, Object> hm = new LinkedHashMap<>();
        hm.put("MOTION_BLOCKING", pack(mb, 9));
        hm.put("MOTION_BLOCKING_NO_LEAVES", pack(mbnl, 9));
        hm.put("OCEAN_FLOOR", pack(of, 9));
        hm.put("WORLD_SURFACE", pack(ws, 9));
        c.put("Heightmaps", hm);

        List<Object> be = new ArrayList<>();
        if (hash(cx, 90, cz) > 0.3) {
            LinkedHashMap<String, Object> e = new LinkedHashMap<>();
            e.put("id", "minecraft:chest");
            e.put("x", cx * 16 + 3);
            e.put("y", 20);
            e.put("z", cz * 16 + 7);
            e.put("keepPacked", (byte) 0);
            List<Object> items = new ArrayList<>();
            for (int j = 0; j < 5; j++) {
                LinkedHashMap<String, Object> it = new LinkedHashMap<>();
                it.put("Slot", (byte) j);
                it.put("id", "minecraft:iron_ingot");
                it.put("count", 8 + j);
                items.add(it);
            }
            e.put("Items", items);
            be.add(e);
        }
        c.put("block_entities", be);
        c.put("block_ticks", new ArrayList<>());
        c.put("fluid_ticks", new ArrayList<>());
        // One list per section, empty or not, like SerializableChunkData.packOffsets
        List<Object> postProcessing = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            postProcessing.add(new ArrayList<>());
        }
        c.put("PostProcessing", postProcessing);
        LinkedHashMap<String, Object> structures = new LinkedHashMap<>();
        structures.put("References", new LinkedHashMap<String, Object>());
        structures.put("starts", new LinkedHashMap<String, Object>());
        c.put("structures", structures);
        return c;
    }
}
