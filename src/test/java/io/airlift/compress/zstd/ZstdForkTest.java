/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.airlift.compress.zstd;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import io.airlift.compress.MalformedInputException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZstdForkTest
{
    @Test
    void emptyOutputBufferValidatesInput()
    {
        ZstdDecompressor decompressor = new ZstdDecompressor();
        byte[] garbage = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
        assertThrows(MalformedInputException.class, () -> decompressor.decompress(garbage, 0, garbage.length, new byte[0], 0, 0));
        assertThrows(MalformedInputException.class, () -> decompressor.decompressWithPrefix(garbage, 0, garbage.length, new byte[0], 0, 0, new byte[0], 0, 0));

        byte[] payload = "non-empty payload".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = compress(payload);
        assertThrows(MalformedInputException.class, () -> decompressor.decompress(compressed, 0, compressed.length, new byte[0], 0, 0));

        byte[] emptyCompressed = compress(new byte[0]);
        assertEquals(0, decompressor.decompress(emptyCompressed, 0, emptyCompressed.length, new byte[0], 0, 0));

        byte[] corrupt = compressed.clone();
        corrupt[0] ^= 1;
        byte[] combined = concat(emptyCompressed, corrupt);
        assertThrows(MalformedInputException.class, () -> decompressor.decompress(combined, 0, combined.length, new byte[0], 0, 0));
    }

    @Test
    void twoByteCompressedBlockAndZeroDictId()
            throws Exception
    {
        ZstdDecompressor decompressor = new ZstdDecompressor();
        String[] frames = {
                // dictionary ID 0 is equivalent to an omitted dictionary ID
                "28b52ffd230000000000010000",
                // compressed block with 2-byte body: empty raw literals and 0 sequences
                "28b52ffd00001500000000"
        };
        for (String hex : frames) {
            byte[] frame = HexFormat.of().parseHex(hex);
            for (int outLen : new int[] {0, 16}) {
                assertEquals(0, decompressor.decompress(frame, 0, frame.length, new byte[outLen], 0, outLen));
            }
            assertArrayEquals(new byte[0], decompressStream(frame));
        }
    }

    @Test
    void skippableFramesOneShotAndStreaming()
            throws Exception
    {
        ZstdDecompressor decompressor = new ZstdDecompressor();
        byte[] emptySkippable = HexFormat.of().parseHex("502a4d1800000000");
        for (int outLen : new int[] {0, 16}) {
            assertEquals(0, decompressor.decompress(emptySkippable, 0, emptySkippable.length, new byte[outLen], 0, outLen));
        }
        assertArrayEquals(new byte[0], decompressStream(emptySkippable));

        byte[] first = "first frame ".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second frame".getBytes(StandardCharsets.UTF_8);
        byte[] skippable = HexFormat.of().parseHex("5f2a4d180400000001020304");
        byte[] combined = concat(compress(first), skippable, compress(second));
        byte[] expected = concat(first, second);

        byte[] out = new byte[expected.length];
        int written = decompressor.decompress(combined, 0, combined.length, out, 0, out.length);
        assertEquals(expected.length, written);
        assertArrayEquals(expected, out);

        assertArrayEquals(expected, decompressStreamChunked(combined, 1));
    }

    @Test
    void truncatedSkippableFramesRejected()
    {
        ZstdDecompressor decompressor = new ZstdDecompressor();
        byte[] skippable = HexFormat.of().parseHex("5f2a4d180400000001020304");
        byte[] out = new byte[16];
        for (int len = 1; len < skippable.length; len++) {
            int truncated = len;
            assertThrows(MalformedInputException.class, () -> decompressor.decompress(skippable, 0, truncated, out, 0, out.length));
            assertThrows(IOException.class, () -> decompressStream(Arrays.copyOf(skippable, truncated)));
        }
    }

    @Test
    void overflowingArrayRangeChecks()
    {
        ZstdCompressor compressor = new ZstdCompressor();
        ZstdDecompressor decompressor = new ZstdDecompressor();
        byte[] buf = new byte[64];

        assertThrows(IllegalArgumentException.class, () -> compressor.compress(buf, 1, Integer.MAX_VALUE, buf, 0, buf.length));
        assertThrows(IllegalArgumentException.class, () -> compressor.compress(buf, 0, buf.length, buf, 1, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> decompressor.decompress(buf, 1, Integer.MAX_VALUE, buf, 0, buf.length));
        assertThrows(IllegalArgumentException.class, () -> decompressor.decompress(buf, 0, buf.length, buf, 1, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> ZstdDecompressor.getDecompressedSize(buf, 1, Integer.MAX_VALUE));

        byte[] compressed = compress(buf);
        assertThrows(IndexOutOfBoundsException.class, () -> {
            try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(compressed))) {
                in.read(buf, 1, Integer.MAX_VALUE);
            }
        });
        assertThrows(IndexOutOfBoundsException.class, () -> {
            try (ZstdOutputStream out = new ZstdOutputStream(new ByteArrayOutputStream())) {
                out.write(buf, 1, Integer.MAX_VALUE);
            }
        });
    }

    @Test
    void prefixRoundTripHeapDirectAndLibzstdCrossCheck()
    {
        Random rng = new Random(42);
        byte[] prefix = new byte[64 * 1024];
        rng.nextBytes(prefix);
        byte[] target = prefix.clone();
        for (int i = 1000; i < 1200; i++) {
            target[i] = (byte) (i ^ 0x5A);
        }
        target = concat(target, "tail-bytes".getBytes(StandardCharsets.UTF_8));

        ZstdCompressor compressor = new ZstdCompressor();
        ZstdDecompressor decompressor = new ZstdDecompressor();
        byte[] deltaBuf = new byte[compressor.maxCompressedLength(target.length)];
        int deltaLen = compressor.compressWithPrefix(target, 0, target.length, deltaBuf, 0, deltaBuf.length, prefix, 0, prefix.length, 0);
        assertTrue(deltaLen < target.length / 4, "prefix delta should compress well, got " + deltaLen);

        // heap byte[] decode
        byte[] decoded = new byte[target.length];
        int decodedLen = decompressor.decompressWithPrefix(deltaBuf, 0, deltaLen, decoded, 0, decoded.length, prefix, 0, prefix.length);
        assertEquals(target.length, decodedLen);
        assertArrayEquals(target, decoded);

        // direct ByteBuffer decode
        ByteBuffer inBb = ByteBuffer.allocateDirect(deltaLen);
        inBb.put(deltaBuf, 0, deltaLen).flip();
        ByteBuffer prefixBb = ByteBuffer.allocateDirect(prefix.length);
        prefixBb.put(prefix).flip();
        ByteBuffer outBb = ByteBuffer.allocateDirect(target.length);
        decompressor.decompressWithPrefix(inBb, outBb, prefixBb);
        assertEquals(target.length, outBb.position());
        assertEquals(0, prefixBb.position());
        outBb.flip();
        byte[] directDecoded = new byte[target.length];
        outBb.get(directDecoded);
        assertArrayEquals(target, directDecoded);

        // reference libzstd decodes our prefix frame
        try (ZstdDecompressCtx dctx = new ZstdDecompressCtx()) {
            dctx.loadDict(prefix);
            byte[] jniDecoded = new byte[target.length];
            int n = dctx.decompressByteArray(jniDecoded, 0, jniDecoded.length, deltaBuf, 0, deltaLen);
            assertEquals(target.length, n);
            assertArrayEquals(target, jniDecoded);
        }

        // our decoder decodes a reference libzstd --patch-from prefix frame
        byte[] jniDelta;
        try (ZstdCompressCtx cctx = new ZstdCompressCtx()) {
            cctx.setLevel(6);
            cctx.setChecksum(true);
            cctx.loadDict(prefix);
            jniDelta = cctx.compress(target);
        }
        byte[] decodedFromJni = new byte[target.length];
        int n = decompressor.decompressWithPrefix(jniDelta, 0, jniDelta.length, decodedFromJni, 0, decodedFromJni.length, prefix, 0, prefix.length);
        assertEquals(target.length, n);
        assertArrayEquals(target, decodedFromJni);
    }

    @Test
    void oneShotAndStreamingCrossCheckWithLibzstd()
            throws Exception
    {
        Random rng = new Random(123);
        byte[] payload = new byte[200_000];
        // mix compressible and random segments
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (i % 1000 < 700) ? (byte) ("hello zstd world! ".charAt(i % 18)) : (byte) rng.nextInt(256);
        }

        // Java compress -> libzstd decompress
        byte[] javaCompressed = compress(payload);
        assertArrayEquals(payload, Zstd.decompress(javaCompressed, payload.length));

        // libzstd compress (multiple levels) -> Java one-shot & streaming decompress
        ZstdDecompressor decompressor = new ZstdDecompressor();
        for (int level : new int[] {1, 3, 9, 19}) {
            byte[] jniCompressed = Zstd.compress(payload, level);
            byte[] out = new byte[payload.length];
            int written = decompressor.decompress(jniCompressed, 0, jniCompressed.length, out, 0, out.length);
            assertEquals(payload.length, written);
            assertArrayEquals(payload, out);
            assertArrayEquals(payload, decompressStream(jniCompressed));
        }
    }

    @Test
    void prefixEmptyInputRejected()
    {
        ZstdDecompressor decompressor = new ZstdDecompressor();
        assertThrows(MalformedInputException.class, () -> decompressor.decompressWithPrefix(new byte[0], 0, 0, new byte[16], 0, 16, new byte[8], 0, 8));
        assertThrows(MalformedInputException.class, () -> decompressor.decompressWithPrefix(ByteBuffer.allocate(0), ByteBuffer.allocate(16), ByteBuffer.allocate(8)));
    }

    @Test
    void compressWithPrefixEmptyAndHashLogValidation()
    {
        ZstdCompressor compressor = new ZstdCompressor();
        ZstdDecompressor decompressor = new ZstdDecompressor();
        byte[] out = new byte[64];
        int len = compressor.compressWithPrefix(new byte[0], 0, 0, out, 0, out.length, new byte[0], 0, 0, 0);
        assertTrue(len > 0, "empty frame must produce bytes");
        assertEquals(0, decompressor.decompressWithPrefix(out, 0, len, new byte[0], 0, 0, new byte[0], 0, 0));

        assertThrows(IllegalArgumentException.class, () -> compressor.compressWithPrefix(new byte[16], 0, 16, out, 0, out.length, new byte[16], 0, 16, 6));
        assertThrows(IllegalArgumentException.class, () -> compressor.compressWithPrefix(new byte[16], 0, 16, out, 0, out.length, new byte[16], 0, 16, 27));
    }

    @Test
    void concatenatedFramesIsolated()
    {
        ZstdDecompressor decompressor = new ZstdDecompressor();
        byte[] part1 = "hello world! ".repeat(10).getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "second frame".getBytes(StandardCharsets.UTF_8);
        byte[] combined = concat(compress(part1), compress(part2));
        byte[] expected = concat(part1, part2);
        byte[] out = new byte[expected.length];
        int written = decompressor.decompress(combined, 0, combined.length, out, 0, out.length);
        assertEquals(expected.length, written);
        assertArrayEquals(expected, out);
    }

    @Test
    void streamingSingleSegmentEnforcesMaxWindowSize()
    {
        // Single-segment frame header declaring contentSize = 16 MiB (> default 8 MiB maxWindowSize)
        byte[] frame = HexFormat.of().parseHex("28b52ffda000000001010000");
        assertThrows(MalformedInputException.class, () -> decompressStream(frame));

        byte[] frame256k = HexFormat.of().parseHex("28b52ffda000000400010000");
        assertThrows(MalformedInputException.class, () -> {
            try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(frame256k), 128 * 1024)) {
                in.readAllBytes();
            }
        });
    }

    @Test
    void streamingContentSizeMismatchRejected()
    {
        byte[] underflowFrame = HexFormat.of().parseHex("28b52ffd200a190000616263");
        assertThrows(MalformedInputException.class, () -> decompressStream(underflowFrame));

        byte[] overflowFrame = HexFormat.of().parseHex("28b52ffd2002190000616263");
        assertThrows(MalformedInputException.class, () -> decompressStream(overflowFrame));
    }

    @Test
    void streamingMaxWindowSize1GiBNoOverflow()
            throws Exception
    {
        byte[] data = "abc".repeat(50_000).getBytes(StandardCharsets.UTF_8);
        byte[] compressed = compress(data);
        try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(compressed), 1 << 30)) {
            assertArrayEquals(data, in.readAllBytes());
        }
    }

    @Test
    void reusableStreamDecoderOwnershipAndClose()
            throws Exception
    {
        byte[] data1 = "first stream payload ".repeat(200).getBytes(StandardCharsets.UTF_8);
        byte[] data2 = "second stream payload ".repeat(200).getBytes(StandardCharsets.UTF_8);
        byte[] c1 = compress(data1);
        byte[] c2 = compress(data2);

        ZstdIncrementalFrameDecompressor decoder = new ZstdIncrementalFrameDecompressor();
        ZstdInputStream s1 = new ZstdInputStream(new ByteArrayInputStream(c1), decoder);
        byte[] part = new byte[10];
        assertEquals(10, s1.read(part));
        assertTrue(decoder.retainedWindowBytes() > 0);

        ZstdInputStream s2 = new ZstdInputStream(new ByteArrayInputStream(c2), decoder);
        assertEquals(0, s1.available());
        assertThrows(IOException.class, () -> s1.read(part));
        assertArrayEquals(data2, s2.readAllBytes());

        s2.close();
        assertNull(decoder.owner);
    }

    @Test
    void streamingThreeByteBlockHeaderAtBufferEnd()
            throws Exception
    {
        byte[] data = new byte[300_000];
        new Random(7).nextBytes(data);
        byte[] compressed = compress(data);
        assertArrayEquals(data, decompressStreamChunked(compressed, 3));
    }

    @Test
    void zstdOutputStreamSlidingWindowRoundTrip()
            throws Exception
    {
        byte[] block = "The quick brown fox jumps over the lazy dog. 0123456789\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (ZstdOutputStream zout = new ZstdOutputStream(compressed)) {
            for (int i = 0; i < 90_000; i++) {
                zout.write(block);
                raw.write(block);
            }
        }
        byte[] compressedBytes = compressed.toByteArray();
        byte[] rawBytes = raw.toByteArray();
        assertTrue(compressedBytes.length < rawBytes.length / 20, "sliding window should keep high compression ratio");
        assertArrayEquals(rawBytes, decompressStream(compressedBytes));
        assertArrayEquals(rawBytes, Zstd.decompress(compressedBytes, rawBytes.length));
    }

    private static byte[] compress(byte[] input)
    {
        ZstdCompressor c = new ZstdCompressor();
        byte[] out = new byte[c.maxCompressedLength(input.length)];
        int len = c.compress(input, 0, input.length, out, 0, out.length);
        return Arrays.copyOf(out, len);
    }

    private static byte[] decompressStream(byte[] compressed)
            throws IOException
    {
        try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(compressed))) {
            return in.readAllBytes();
        }
    }

    private static byte[] decompressStreamChunked(byte[] compressed, int chunkSize)
            throws IOException
    {
        ByteArrayInputStream base = new ByteArrayInputStream(compressed)
        {
            @Override
            public synchronized int read(byte[] b, int off, int len)
            {
                return super.read(b, off, Math.min(len, chunkSize));
            }
        };
        try (ZstdInputStream in = new ZstdInputStream(base)) {
            return in.readAllBytes();
        }
    }

    private static byte[] concat(byte[]... parts)
    {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }
}
