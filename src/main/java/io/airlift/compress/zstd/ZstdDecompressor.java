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

import io.airlift.compress.Decompressor;
import io.airlift.compress.MalformedInputException;

import java.nio.ByteBuffer;

import static io.airlift.compress.zstd.Memory.ARRAY_BYTE_BASE_OFFSET;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * One-shot zstd decoder. Reusing one instance across calls avoids re-allocating its ~150 KB of tables and buffers.
 * Instances are not thread safe. Heap arrays and heap buffers, direct buffers and {@link java.nio.MappedByteBuffer}s are
 * all accessed with JVM bounds checks; corrupt input fails with {@link MalformedInputException}.
 */
public class ZstdDecompressor
        implements Decompressor
{
    private final ZstdFrameDecompressor decompressor = new ZstdFrameDecompressor();

    @Override
    public int decompress(byte[] input, int inputOffset, int inputLength, byte[] output, int outputOffset, int maxOutputLength)
            throws MalformedInputException
    {
        verifyRange(input, inputOffset, inputLength);
        verifyRange(output, outputOffset, maxOutputLength);

        long inputAddress = ARRAY_BYTE_BASE_OFFSET + inputOffset;
        long inputLimit = inputAddress + inputLength;
        long outputAddress = ARRAY_BYTE_BASE_OFFSET + outputOffset;
        long outputLimit = outputAddress + maxOutputLength;

        return decompressor.decompress(input, inputAddress, inputLimit, output, outputAddress, outputLimit);
    }

    @Override
    public void decompress(ByteBuffer inputBuffer, ByteBuffer outputBuffer)
            throws MalformedInputException
    {
        int written = decompressor.decompress(
                Memory.base(inputBuffer), Memory.address(inputBuffer, inputBuffer.position()), Memory.address(inputBuffer, inputBuffer.limit()),
                Memory.base(outputBuffer), Memory.address(outputBuffer, outputBuffer.position()), Memory.address(outputBuffer, outputBuffer.limit()));
        outputBuffer.position(outputBuffer.position() + written);
    }

    /**
     * Decompresses frames that were compressed against a raw content dictionary (a "prefix"), e.g. by
     * {@code zstd --patch-from=prefix}: match offsets may reach back past the start of each frame into the
     * prefix. Frames that carry a non-zero dictionary ID (trained dictionaries) are rejected.
     */
    public int decompressWithPrefix(byte[] input, int inputOffset, int inputLength, byte[] output, int outputOffset, int maxOutputLength, byte[] prefix, int prefixOffset, int prefixLength)
            throws MalformedInputException
    {
        verifyRange(input, inputOffset, inputLength);
        verifyRange(output, outputOffset, maxOutputLength);
        verifyRange(prefix, prefixOffset, prefixLength);
        if (inputLength == 0) {
            // a delta is never empty; stock semantics (zero frames decode to zero bytes) would hide a lost payload
            throw new MalformedInputException(0, "No zstd frame");
        }

        long inputAddress = ARRAY_BYTE_BASE_OFFSET + inputOffset;
        long outputAddress = ARRAY_BYTE_BASE_OFFSET + outputOffset;
        long prefixAddress = ARRAY_BYTE_BASE_OFFSET + prefixOffset;
        return decompressor.decompress(
                input, inputAddress, inputAddress + inputLength,
                output, outputAddress, outputAddress + maxOutputLength,
                prefix, prefixAddress, prefixAddress + prefixLength);
    }

    /**
     * ByteBuffer variant of {@link #decompressWithPrefix(byte[], int, int, byte[], int, int, byte[], int, int)}. Any buffer may be
     * direct, including a {@link java.nio.MappedByteBuffer}, so an old file can serve as the prefix and the new file as the output
     * without copying either onto the heap. The prefix is the region between its position and limit; its position is not changed.
     */
    public void decompressWithPrefix(ByteBuffer inputBuffer, ByteBuffer outputBuffer, ByteBuffer prefixBuffer)
            throws MalformedInputException
    {
        if (inputBuffer.remaining() == 0) {
            throw new MalformedInputException(0, "No zstd frame");
        }
        int written = decompressor.decompress(
                Memory.base(inputBuffer), Memory.address(inputBuffer, inputBuffer.position()), Memory.address(inputBuffer, inputBuffer.limit()),
                Memory.base(outputBuffer), Memory.address(outputBuffer, outputBuffer.position()), Memory.address(outputBuffer, outputBuffer.limit()),
                Memory.base(prefixBuffer), Memory.address(prefixBuffer, prefixBuffer.position()), Memory.address(prefixBuffer, prefixBuffer.limit()));
        outputBuffer.position(outputBuffer.position() + written);
    }

    public static long getDecompressedSize(byte[] input, int offset, int length)
    {
        verifyRange(input, offset, length);
        long baseAddress = ARRAY_BYTE_BASE_OFFSET + offset;
        try {
            return ZstdFrameDecompressor.getDecompressedSize(input, baseAddress, baseAddress + length);
        }
        catch (IndexOutOfBoundsException e) {
            throw new MalformedInputException(0, "Input is corrupted", e);
        }
    }

    private static void verifyRange(byte[] data, int offset, int length)
    {
        requireNonNull(data, "data is null");
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IllegalArgumentException(format("Invalid offset or length (%s, %s) in array of length %s", offset, length, data.length));
        }
    }
}
