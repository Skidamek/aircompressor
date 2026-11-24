# upstream

Fork of [airlift/aircompressor](https://github.com/airlift/aircompressor), Apache-2.0 (`license.txt`, `notice.md`).

Branched from upstream `release-2.x` at `0ad024e` (post tag `2.0.3` / `b98c7d8`). Upstream `master` moved to `io.airlift.compress.v3` (Java 22+ FFM / native libraries), so pure-Java zstd fixes are backported selectively.

## backported from upstream `master` (v3)

- `3498e2b` — `ZstdOutputStream loses compression ratio after sliding window` (`BlockCompressionState.slideWindow` slides `windowBaseOffset`)
- `0dd1968` — `Check zstd input when the output buffer is empty`
- `f9b9667` — `Accept valid zstd input the Java decoder rejected` (zero dictionary ID, 2-byte compressed blocks, skippable frames — extended here to streaming too)
- `00900ea` — `Fix integer overflow in array range checks`
- PR `#302` — `Fix out-of-bounds read in ZstdIncrementalFrameDecompressor` (removed leftover 4-byte debug read on 3-byte block headers at buffer end)

## fork changes

- **Zstd-only, Java 17, Gradle**: Dropped Hadoop adapters and non-zstd codecs (`bzip2`, `deflate`, `gzip`, `lz4`, `lzo`, `snappy`). Zero runtime dependencies (`java.base` only).
- **No `sun.misc.Unsafe`**: Replaced `UnsafeUtil` with bounds-checked `Memory` (`VarHandle` little-endian views on `byte[]` and `ByteBuffer`, including direct and `MappedByteBuffer`). Runs without `--add-opens` and under `--sun-misc-unsafe-memory-access=deny`. Hot decode paths (`XxHash64`, 4-stream `Huffman`, sequence match copy) retuned for checked access.
- **Raw-content prefix (`--patch-from`)**: Added `ZstdDecompressor.decompressWithPrefix` (`byte[]` and `ByteBuffer`) and `ZstdCompressor.compressWithPrefix` (up to 256 MiB prefix + input). Removed the 8 MiB one-shot window cap.
- **Reusable streaming decoder**: Added `ZstdInputStream(InputStream, int maxWindowSize)`, `ZstdInputStream(InputStream, ZstdIncrementalFrameDecompressor)`, `ZstdIncrementalFrameDecompressor.reset()`, and `retainedWindowBytes()`. Streaming window buffer grows up to `2 * maxWindowSize + 128 KiB` to avoid per-block compaction.
- **Hardening**: Unsigned RLE sequence symbols, bounded Huffman weights, strict content-size verification (one-shot and streaming), `maxWindowSize` enforced on single-segment frames, `long` window/buffer sizing math, frame isolation across concatenated frames, `SequenceEncoder` offset table limit fix, and `IndexOutOfBoundsException` mapped to `MalformedInputException`.

## modifications

This section is the modification notice required by Apache-2.0 Section 4(b). This repository is a modified version of `airlift/aircompressor` 2.0.3 (`0ad024e`), changed in 2026. The fork's commits and touched files:

```sh
git log 0ad024e..HEAD
git diff --stat 0ad024e..HEAD
```

## syncing

Upstream `release-2.x` is maintenance-only; new pure-Java zstd fixes land on upstream `master` under `src/main/java/io/airlift/compress/v3/zstd/`. Check for new commits there and port relevant fixes manually:

```sh
git fetch https://github.com/airlift/aircompressor.git master
git log --oneline <last-checked-sha>..FETCH_HEAD -- 'src/main/java/io/airlift/compress/v3/zstd/'
```
