# aircompressor (zstd-only fork)

Pure-Java 17, `sun.misc.Unsafe`-free, zstd-only fork of [airlift/aircompressor](https://github.com/airlift/aircompressor) `2.0.3` with raw-content prefix (`zstd --patch-from`) support and reusable streaming decoders.

- **Zero runtime dependencies** (`jdeps`: `java.base` only)
- **No `sun.misc.Unsafe`** — runs cleanly on Java 17–25+ and under `--sun-misc-unsafe-memory-access=deny` without `--add-opens`
- **Raw-content prefix (`--patch-from`)** compression and decompression on `byte[]`, heap `ByteBuffer`, direct `ByteBuffer`, and `MappedByteBuffer`
- **Reusable streaming decoder** (`ZstdInputStream(InputStream, ZstdIncrementalFrameDecompressor)`) with configurable `maxWindowSize` and observable `retainedWindowBytes()`

See [UPSTREAM.md](UPSTREAM.md) for the exact diff and upstream backports.

## Gradle

```kotlin
repositories {
    maven("https://skidamek.github.io/maven/")
}

dependencies {
    implementation("pl.skidam:aircompressor:2.0.3-1")
}
```

## Public API

Package `io.airlift.compress` / `io.airlift.compress.zstd`:

- **`ZstdDecompressor`**: `decompress(...)`, `decompressWithPrefix(...)` (`byte[]` and `ByteBuffer`), `getDecompressedSize(...)`
- **`ZstdCompressor`**: `compress(...)`, `compressWithPrefix(...)`, `maxCompressedLength(...)`
- **`ZstdInputStream`**: `ZstdInputStream(in)`, `ZstdInputStream(in, maxWindowSize)`, `ZstdInputStream(in, ZstdIncrementalFrameDecompressor)`
- **`ZstdIncrementalFrameDecompressor`**: `reset()`, `retainedWindowBytes()`, `partialDecompress(...)`
- **`ZstdOutputStream`**: streaming zstd encoder

## Building

Requires Java 17+:

```sh
./gradlew build
```

## License

Apache License 2.0. See [license.txt](license.txt), [notice.md](notice.md), and [UPSTREAM.md](UPSTREAM.md).
