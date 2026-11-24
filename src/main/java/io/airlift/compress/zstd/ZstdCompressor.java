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

import io.airlift.compress.Compressor;

import java.nio.ByteBuffer;

import static io.airlift.compress.zstd.Constants.MAX_BLOCK_SIZE;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static io.airlift.compress.zstd.Memory.ARRAY_BYTE_BASE_OFFSET;

public class ZstdCompressor
        implements Compressor
{
    @Override
    public int maxCompressedLength(int uncompressedSize)
    {
        int result = uncompressedSize + (uncompressedSize >>> 8);

        if (uncompressedSize < MAX_BLOCK_SIZE) {
            result += (MAX_BLOCK_SIZE - uncompressedSize) >>> 11;
        }

        return result;
    }

    @Override
    public int compress(byte[] input, int inputOffset, int inputLength, byte[] output, int outputOffset, int maxOutputLength)
    {
        verifyRange(input, inputOffset, inputLength);
        verifyRange(output, outputOffset, maxOutputLength);

        long inputAddress = ARRAY_BYTE_BASE_OFFSET + inputOffset;
        long outputAddress = ARRAY_BYTE_BASE_OFFSET + outputOffset;

        return ZstdFrameCompressor.compress(input, inputAddress, inputAddress + inputLength, output, outputAddress, outputAddress + maxOutputLength, CompressionParameters.DEFAULT_COMPRESSION_LEVEL);
    }

    /** Largest prefix + input this encoder accepts: offsets must stay within the 28-bit offset codes every decoder's default tables cover. */
    public static final int MAX_PREFIX_WINDOW = 1 << 28;

    /**
     * Compresses {@code input} against {@code prefix} (a raw content dictionary), producing a frame equivalent to
     * {@code zstd --patch-from=prefix}. Any zstd decoder that is given the same prefix can decode it, including
     * the reference library ({@code ZSTD_DCtx_refPrefix}) and CLI ({@code zstd -d --patch-from=prefix}).
     *
     * @param hashLog log2 of the match-finder tables (two int tables of 2^hashLog and 2^(hashLog-1) entries);
     *        0 picks one from the prefix and input sizes
     */
    public int compressWithPrefix(byte[] input, int inputOffset, int inputLength, byte[] output, int outputOffset, int maxOutputLength, byte[] prefix, int prefixOffset, int prefixLength, int hashLog)
    {
        verifyRange(input, inputOffset, inputLength);
        verifyRange(output, outputOffset, maxOutputLength);
        verifyRange(prefix, prefixOffset, prefixLength);
        long total = (long) prefixLength + inputLength;
        if (total > MAX_PREFIX_WINDOW) {
            throw new IllegalArgumentException("prefix + input exceed " + MAX_PREFIX_WINDOW + " bytes");
        }
        // the match finder needs prefix and input in one address range
        byte[] joined = new byte[(int) total];
        System.arraycopy(prefix, prefixOffset, joined, 0, prefixLength);
        System.arraycopy(input, inputOffset, joined, prefixLength, inputLength);

        int windowLog = total <= 1
                ? Constants.MIN_WINDOW_LOG
                : Math.max(Constants.MIN_WINDOW_LOG, 64 - Long.numberOfLeadingZeros(total - 1));
        if (hashLog <= 0) {
            hashLog = Math.max(17, Math.min(22, windowLog + 1));
        }
        else if (hashLog < 7 || hashLog > 26) {
            throw new IllegalArgumentException("hashLog must be between 7 and 26 (or <= 0 for automatic): " + hashLog);
        }
        CompressionParameters parameters = new CompressionParameters(windowLog, hashLog - 1, hashLog, 1, 5, 1, CompressionParameters.Strategy.DFAST);

        long base = ARRAY_BYTE_BASE_OFFSET;
        long outputAddress = ARRAY_BYTE_BASE_OFFSET + outputOffset;
        return ZstdFrameCompressor.compressWithPrefix(joined, base, base + prefixLength, base + total, output, outputAddress, outputAddress + maxOutputLength, parameters);
    }

    @Override
    public void compress(ByteBuffer inputBuffer, ByteBuffer outputBuffer)
    {
        // Heap buffers are accessed through their array, direct and mapped buffers through bounds-checked absolute access.
        Object inputBase = Memory.base(inputBuffer);
        long inputAddress = Memory.address(inputBuffer, inputBuffer.position());
        long inputLimit = Memory.address(inputBuffer, inputBuffer.limit());
        Object outputBase = Memory.base(outputBuffer);
        long outputAddress = Memory.address(outputBuffer, outputBuffer.position());
        long outputLimit = Memory.address(outputBuffer, outputBuffer.limit());

        int written = ZstdFrameCompressor.compress(
                inputBase,
                inputAddress,
                inputLimit,
                outputBase,
                outputAddress,
                outputLimit,
                CompressionParameters.DEFAULT_COMPRESSION_LEVEL);
        outputBuffer.position(outputBuffer.position() + written);
    }

    private static void verifyRange(byte[] data, int offset, int length)
    {
        requireNonNull(data, "data is null");
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IllegalArgumentException(format("Invalid offset or length (%s, %s) in array of length %s", offset, length, data.length));
        }
    }
}
