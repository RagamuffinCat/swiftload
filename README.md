# Swiftload

A Minecraft 26.3 mod for Fabric and NeoForge that makes reading and saving chunks faster without changing the
result. Put it on the client, the server or both. There's nothing to configure and no other mods are needed.

## Why

The mods that speed up chunk loading mostly run the work on more threads (C2ME, Moonrise) or switch to another
region file format. The work itself stays the same: every chunk still goes through `java.util.zip`, a stream-based
NBT parser and DataFixerUpper codecs for its block palettes, and saving does all of it backwards. On a server with
a weak CPU, that's where the time goes. Swiftload makes those steps cheaper instead - libdeflate for compression,
an NBT reader that parses a chunk in one pass, a palette decoder that skips the codecs - and runs fine next to C2ME
and Moonrise. Anything unusual is handed to Minecraft's own code on the same bytes, so worlds come out the same as
without the mod and still open after removing it.

## Requirements

Minecraft 26.3 and Java 25. Fabric needs Fabric Loader 0.19.5 or newer, Fabric API isn't required. NeoForge needs
26.3.0.69-beta or newer (tested up to 26.3.0.71).

## What gets faster

| | Vanilla | Swiftload |
|---|---:|---:|
| Saving 9,216 changed chunks (`/save-all flush`) | 9.1 s | 4.5 s |
| Rewriting a 29,557-chunk world (`--forceUpgrade --recreateRegionFiles`) | 23 - 24 s | 15 s |
| Reading one chunk (decompress and parse) | 113 µs | 35 µs |
| Decoding one chunk's block and biome palettes | 51 µs | 19 µs |
| Reading all 1,511 vanilla structure templates | 276 ms | 122 ms |

Measured on a 4-core Windows 11 desktop with Java 25 and a 188 MB test world. The first two rows are dedicated
servers, the rest come from the game tests below, which run inside the game with the mod's hooks applied.

Saves (autosave, `/save-all`, leaving a world, stopping a server) take about half as long. Reading a chunk costs
the chunk I/O thread about a third of what it did, which helps where that thread is the bottleneck: weak CPUs and
busy servers. Don't expect chunks to show up sooner on a good desktop, the I/O thread was never the slow part
there. With the server limited to two CPU cores, loading the same 9,216 chunks took 5 - 7 % less time.

Saved files are not bigger. After rewriting the test world, its region files took 192.4 MB on disk against
193.0 MB with vanilla, and no single file grew by more than 0.3 %. Gzip files (`level.dat`, player data,
structure templates) came out 12 % smaller.

## Same results

- Every chunk of the test world (29,557 chunks, 644,024 sections) reads back the same as with vanilla: same tags,
  same NBT size accounting, same palettes. Vanilla reads everything Swiftload writes.
- A corrupt chunk, an unknown block or anything else unusual goes to Minecraft's own code, so errors and log lines
  are vanilla's.
- The NBT reader checks itself against the game's own reader on first use and turns itself off if they disagree,
  for example because another mod changed how NBT is read.

## Compatibility

Swiftload adds no network traffic and no content. A server with it accepts players without it and the other way
round.

| Mod | Fabric | NeoForge |
|---|---|---|
| Lithium | Tested | Tested |
| C2ME | Tested | - |
| Moonrise | Tested | Tested |
| ScalableLux | Tested | Tested |
| FerriteCore | Tested | Tested |
| Sodium | Tested | Not tested |
| Distant Horizons | Tested | Tested |
| Chunky | Tested | Tested |
| FastQuit | Tested | - |
| Fabric API | Tested | - |
| ModernFix | Not tested | Not tested |

Tested means worlds load and save with that mod installed; on Fabric, every chunk of the test world was also
compared with vanilla. Under C2ME and Moonrise the work runs on their worker threads. ModernFix has no 26.3 build
yet, and Sodium 0.9.3-alpha.1 doesn't start on NeoForge 26.3.0.69-beta, with or without Swiftload. With FastQuit,
the background save after leaving a world works; rejoining that world while it's still saving hasn't been tried.

## Config

Nothing needs changing. `config/swiftload.properties` is created on first start with one switch per step, and
`-Dswiftload.<key>=false` on the Java command line overrides the file. A switched-off step runs Minecraft's own
code.

| Key | Default | |
|---|---|---|
| `natives` | true | Use the bundled libdeflate. Off means `java.util.zip` everywhere |
| `regionInflate` | true | Decompress region chunks with libdeflate |
| `fastNbt` | true | Parse chunks and gzip files in one pass |
| `fastPalette` | true | Decode block and biome palettes directly |
| `gzipFiles` | true | Decompress `level.dat`, player data, maps and structure templates with libdeflate |
| `fastWrite` | true | Compress region chunks and gzip files with libdeflate when saving |
| `debugStats` | false | Log hits, fallbacks and time per step when the server stops |

## libdeflate

The jar contains libdeflate 1.26 for Windows, Linux and macOS, each for x64 and ARM64. `natives/build-libdeflate.sh`
rebuilds all six from the upstream source with Zig and gets the same bytes (CI checks that), and the mod checks each
file's SHA-256 before loading it from `.swiftload/natives` in the game folder. Elsewhere (musl Linux such as Alpine,
32-bit Java) it falls back to `java.util.zip`, and only the palette decoder applies.

Loading a native library from a NeoForge mod makes Java print a warning, because mods there are named modules
without native access. So the few calls that need it live in `NativeBridge`, which ships as compiled bytes
(`NativeBridge.bin`, built by `natives/build-bridge.sh`, a test checks it against the source) and runs in an
unnamed module. The vanilla launcher and the Modrinth App already allow those with
`--enable-native-access=ALL-UNNAMED`.

`-Dswiftload.natives.bridge=false` skips the bridge, `-Dswiftload.natives.path=<file>` loads a specific library.

## Known limitations

- Chunks too large for the region file (stored as `.mcc`) are read by vanilla's code. Region files that use lz4 or
  no compression are left alone.
- On a fast desktop, loading doesn't get visibly faster. Saving does.

## Building

Building requires JDK 25, which Gradle downloads if it's missing.

```
./gradlew build                      # Both loaders and the unit tests, jars in <loader>/build/libs
./gradlew :fabric:runClient          # Also :neoforge:, and runServer
./gradlew :fabric:test -Pswiftload.testworld=<world folder>    # Game tests on a whole world, also :neoforge:test
./natives/build-libdeflate.sh        # Rebuild the libraries (on Linux or macOS)
./natives/build-bridge.sh            # After changing NativeBridge.java
./natives/test-natives.sh            # Native tests without Gradle
```

`-Pswiftload.fabricMods=lithium,c2me` (or `neoforgeMods`) adds other mods to runs and tests; the list is in each
loader's `build.gradle`. The test world behind the numbers is seed 20261011, pregenerated with Chunky to a radius of
1024 blocks in the overworld and 256 in the Nether and the End.

The project uses the MultiLoader layout. The mod is in `common`, while `fabric` and `neoforge` only start it. The
game tests in `common/src/gametest` run inside both loaders with the mixins applied.

## License

MIT © RagamuffinCat, see [LICENSE](LICENSE). libdeflate is MIT © Eric Biggers, see
[its license](common/src/main/resources/natives/LICENSE-libdeflate.txt).
