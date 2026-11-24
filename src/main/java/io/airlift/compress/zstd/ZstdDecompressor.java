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

import java.nio.Buffer;
import java.nio.ByteBuffer;

import static io.airlift.compress.zstd.UnsafeUtil.getAddress;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static sun.misc.Unsafe.ARRAY_BYTE_BASE_OFFSET;

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
        // Java 9+ added an overload of various methods in ByteBuffer. When compiling with Java 11+ and targeting Java 8 bytecode
        // the resulting signatures are invalid for JDK 8, so accesses below result in NoSuchMethodError. Accessing the
        // methods through the interface class works around the problem
        // Sidenote: we can't target "javac --release 8" because Unsafe is not available in the signature data for that profile
        Buffer input = inputBuffer;
        Buffer output = outputBuffer;

        Object inputBase;
        long inputAddress;
        long inputLimit;
        if (input.isDirect()) {
            inputBase = null;
            long address = getAddress(input);
            inputAddress = address + input.position();
            inputLimit = address + input.limit();
        }
        else if (input.hasArray()) {
            inputBase = input.array();
            inputAddress = ARRAY_BYTE_BASE_OFFSET + input.arrayOffset() + input.position();
            inputLimit = ARRAY_BYTE_BASE_OFFSET + input.arrayOffset() + input.limit();
        }
        else {
            throw new IllegalArgumentException("Unsupported input ByteBuffer implementation " + input.getClass().getName());
        }

        Object outputBase;
        long outputAddress;
        long outputLimit;
        if (output.isDirect()) {
            outputBase = null;
            long address = getAddress(output);
            outputAddress = address + output.position();
            outputLimit = address + output.limit();
        }
        else if (output.hasArray()) {
            outputBase = output.array();
            outputAddress = ARRAY_BYTE_BASE_OFFSET + output.arrayOffset() + output.position();
            outputLimit = ARRAY_BYTE_BASE_OFFSET + output.arrayOffset() + output.limit();
        }
        else {
            throw new IllegalArgumentException("Unsupported output ByteBuffer implementation " + output.getClass().getName());
        }

        // HACK: Assure JVM does not collect Slice wrappers while decompressing, since the
        // collection may trigger freeing of the underlying memory resulting in a segfault
        // There is no other known way to signal to the JVM that an object should not be
        // collected in a block, and technically, the JVM is allowed to eliminate these locks.
        synchronized (input) {
            synchronized (output) {
                int written = decompressor.decompress(inputBase, inputAddress, inputLimit, outputBase, outputAddress, outputLimit);
                output.position(output.position() + written);
            }
        }
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
        Buffer input = inputBuffer;
        Buffer output = outputBuffer;
        Buffer prefix = prefixBuffer;
        Object inputBase = base(input);
        long inputAddress = address(input) + input.position();
        long inputLimit = address(input) + input.limit();
        Object outputBase = base(output);
        long outputAddress = address(output) + output.position();
        long outputLimit = address(output) + output.limit();
        Object prefixBase = base(prefix);
        long prefixAddress = address(prefix) + prefix.position();
        long prefixLimit = address(prefix) + prefix.limit();
        if (inputAddress == inputLimit) {
            throw new MalformedInputException(0, "No zstd frame");
        }

        // see decompress(ByteBuffer, ByteBuffer): keep the buffers reachable while their memory is accessed
        synchronized (input) {
            synchronized (output) {
                synchronized (prefix) {
                    int written = decompressor.decompress(inputBase, inputAddress, inputLimit, outputBase, outputAddress, outputLimit, prefixBase, prefixAddress, prefixLimit);
                    output.position(output.position() + written);
                }
            }
        }
    }

    private static Object base(Buffer buffer)
    {
        if (buffer.isDirect()) {
            return null;
        }
        if (buffer.hasArray()) {
            return buffer.array();
        }
        throw new IllegalArgumentException("Unsupported ByteBuffer implementation " + buffer.getClass().getName());
    }

    private static long address(Buffer buffer)
    {
        if (buffer.isDirect()) {
            return getAddress(buffer);
        }
        if (buffer.isReadOnly()) {
            throw new IllegalArgumentException("Read-only heap ByteBuffers are not supported");
        }
        return ARRAY_BYTE_BASE_OFFSET + buffer.arrayOffset();
    }

    public static long getDecompressedSize(byte[] input, int offset, int length)
    {
        int baseAddress = ARRAY_BYTE_BASE_OFFSET + offset;
        return ZstdFrameDecompressor.getDecompressedSize(input, baseAddress, baseAddress + length);
    }

    private static void verifyRange(byte[] data, int offset, int length)
    {
        requireNonNull(data, "data is null");
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException(format("Invalid offset or length (%s, %s) in array of length %s", offset, length, data.length));
        }
    }
}
