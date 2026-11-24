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

import io.airlift.compress.MalformedInputException;

import java.util.Arrays;

import static io.airlift.compress.zstd.Constants.COMPRESSED_BLOCK;
import static io.airlift.compress.zstd.Constants.MAX_BLOCK_SIZE;
import static io.airlift.compress.zstd.Constants.RAW_BLOCK;
import static io.airlift.compress.zstd.Constants.RLE_BLOCK;
import static io.airlift.compress.zstd.Constants.SIZE_OF_BLOCK_HEADER;
import static io.airlift.compress.zstd.Constants.SIZE_OF_INT;
import static io.airlift.compress.zstd.Util.checkArgument;
import static io.airlift.compress.zstd.Util.checkState;
import static io.airlift.compress.zstd.Util.fail;
import static io.airlift.compress.zstd.Util.verify;
import static io.airlift.compress.zstd.ZstdFrameDecompressor.MAX_WINDOW_SIZE;
import static io.airlift.compress.zstd.ZstdFrameDecompressor.SKIPPABLE_FRAME_HEADER_SIZE;
import static io.airlift.compress.zstd.ZstdFrameDecompressor.SKIPPABLE_MAGIC_NUMBER;
import static io.airlift.compress.zstd.ZstdFrameDecompressor.SKIPPABLE_MAGIC_NUMBER_MASK;
import static io.airlift.compress.zstd.ZstdFrameDecompressor.decodeRawBlock;
import static io.airlift.compress.zstd.ZstdFrameDecompressor.decodeRleBlock;
import static io.airlift.compress.zstd.ZstdFrameDecompressor.readFrameHeader;
import static io.airlift.compress.zstd.ZstdFrameDecompressor.verifyMagic;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.toIntExact;
import static java.lang.String.format;
import static io.airlift.compress.zstd.Memory.ARRAY_BYTE_BASE_OFFSET;

public class ZstdIncrementalFrameDecompressor
{
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    private enum State {
        INITIAL,
        READ_FRAME_MAGIC,
        SKIP_FRAME,
        READ_FRAME_HEADER,
        READ_BLOCK_HEADER,
        READ_BLOCK,
        READ_BLOCK_CHECKSUM,
        FLUSH_OUTPUT
    }

    private final ZstdFrameDecompressor frameDecompressor = new ZstdFrameDecompressor();

    private State state = State.INITIAL;
    private FrameHeader frameHeader;
    private int blockHeader = -1;
    private long decodedFrameSize;
    private long skippableBytesRemaining;

    private int inputConsumed;
    private int outputBufferUsed;

    private int inputRequired;
    private int requestedOutputSize;

    // ZstdInputStream's input buffer, kept here so a reused decoder also reuses it
    byte[] streamInputBuffer = new byte[0];
    // the ZstdInputStream currently using this decoder; a stream whose decoder was taken over refuses to read
    Object owner;

    /**
     * Returns this decoder to its initial state, keeping its window buffer, entropy tables and input buffer, so one
     * instance can decode one stream after another without re-allocating them. Not thread safe.
     */
    public void reset()
    {
        state = State.INITIAL;
        frameHeader = null;
        blockHeader = -1;
        decodedFrameSize = 0;
        skippableBytesRemaining = 0;
        inputConsumed = 0;
        outputBufferUsed = 0;
        inputRequired = 0;
        requestedOutputSize = 0;
        partialHash = null;
        resetFrame();
    }

    // largest window this decoder accepts; its window buffer grows up to about twice this (only as output arrives)
    private final int maxWindowSize;

    public ZstdIncrementalFrameDecompressor()
    {
        this(MAX_WINDOW_SIZE);
    }

    public ZstdIncrementalFrameDecompressor(int maxWindowSize)
    {
        checkArgument(maxWindowSize >= MAX_BLOCK_SIZE && maxWindowSize <= (1 << 30), "maxWindowSize must be between 128 KiB and 1 GiB");
        this.maxWindowSize = maxWindowSize;
    }

    // current window buffer
    private byte[] windowBase = new byte[0];
    private long windowAddress = ARRAY_BYTE_BASE_OFFSET;
    private long windowLimit = ARRAY_BYTE_BASE_OFFSET;
    private long windowPosition = ARRAY_BYTE_BASE_OFFSET;

    private XxHash64 partialHash;

    public boolean isAtStoppingPoint()
    {
        return state == State.READ_FRAME_MAGIC;
    }

    public int getInputConsumed()
    {
        return inputConsumed;
    }

    public int getOutputBufferUsed()
    {
        return outputBufferUsed;
    }

    public int getInputRequired()
    {
        return inputRequired;
    }

    public int getRequestedOutputSize()
    {
        return requestedOutputSize;
    }

    /**
     * Bytes of window buffer this decoder holds right now. The buffer is sized from the frame header's window (up to
     * {@code maxWindowSize}), not from the output it produced, so a pool that reuses decoders between streams must charge
     * each one for this instead of for the bytes it decoded.
     */
    public int retainedWindowBytes()
    {
        return windowBase.length;
    }

    public void partialDecompress(
            final Object inputBase,
            final long inputAddress,
            final long inputLimit,
            final byte[] outputArray,
            final int outputOffset,
            final int outputLimit)
    {
        try {
            decompressPartial(inputBase, inputAddress, inputLimit, outputArray, outputOffset, outputLimit);
        }
        catch (IndexOutOfBoundsException e) {
            // every access is bounds checked; a corrupt frame that slips past the explicit checks lands here instead of in other memory
            throw new MalformedInputException(inputAddress, "Input is corrupted", e);
        }
    }

    private void decompressPartial(
            final Object inputBase,
            final long inputAddress,
            final long inputLimit,
            final byte[] outputArray,
            final int outputOffset,
            final int outputLimit)
    {
        if (inputRequired > inputLimit - inputAddress) {
            throw new IllegalArgumentException(format(
                    "Required %s input bytes, but only %s input bytes were supplied",
                    inputRequired,
                    inputLimit - inputAddress));
        }
        if (requestedOutputSize > 0 && outputOffset >= outputLimit) {
            throw new IllegalArgumentException("Not enough space in output buffer to output");
        }

        long input = inputAddress;
        int output = outputOffset;

        while (true) {
            // Flush ready output
            {
                int flushableOutputSize = computeFlushableOutputSize(frameHeader);
                if (flushableOutputSize > 0) {
                    int freeOutputSize = outputLimit - output;
                    if (freeOutputSize > 0) {
                        int copySize = min(freeOutputSize, flushableOutputSize);
                        System.arraycopy(windowBase, toIntExact(windowAddress - ARRAY_BYTE_BASE_OFFSET), outputArray, output, copySize);
                        if (partialHash != null) {
                            partialHash.update(outputArray, output, copySize);
                        }
                        windowAddress += copySize;
                        output += copySize;
                        flushableOutputSize -= copySize;
                    }
                    if (flushableOutputSize > 0) {
                        requestOutput(inputAddress, outputOffset, input, output, flushableOutputSize);
                        return;
                    }
                }
            }
            // verify data was completely flushed
            checkState(computeFlushableOutputSize(frameHeader) == 0, "Expected output to be flushed");

            if (state == State.READ_FRAME_MAGIC || state == State.INITIAL) {
                if (inputLimit - input < SIZE_OF_INT) {
                    inputRequired(inputAddress, outputOffset, input, output, SIZE_OF_INT);
                    return;
                }
                int magic = Memory.getInt(inputBase, input);
                if ((magic & SKIPPABLE_MAGIC_NUMBER_MASK) == SKIPPABLE_MAGIC_NUMBER) {
                    if (inputLimit - input < SKIPPABLE_FRAME_HEADER_SIZE) {
                        inputRequired(inputAddress, outputOffset, input, output, SKIPPABLE_FRAME_HEADER_SIZE);
                        return;
                    }
                    skippableBytesRemaining = Memory.getInt(inputBase, input + SIZE_OF_INT) & 0xFFFF_FFFFL;
                    input += SKIPPABLE_FRAME_HEADER_SIZE;
                    state = skippableBytesRemaining > 0 ? State.SKIP_FRAME : State.READ_FRAME_MAGIC;
                    continue;
                }
                input += verifyMagic(inputBase, input, inputLimit);
                state = State.READ_FRAME_HEADER;
            }

            if (state == State.SKIP_FRAME) {
                long available = inputLimit - input;
                if (available > 0) {
                    long skip = min(available, skippableBytesRemaining);
                    input += skip;
                    skippableBytesRemaining -= skip;
                }
                if (skippableBytesRemaining > 0) {
                    inputRequired(inputAddress, outputOffset, input, output, 1);
                    return;
                }
                state = State.READ_FRAME_MAGIC;
                continue;
            }

            if (state == State.READ_FRAME_HEADER) {
                if (inputLimit - input < 1) {
                    inputRequired(inputAddress, outputOffset, input, output, 1);
                    return;
                }
                int frameHeaderSize = determineFrameHeaderSize(inputBase, input, inputLimit);
                if (inputLimit - input < frameHeaderSize) {
                    inputRequired(inputAddress, outputOffset, input, output, frameHeaderSize);
                    return;
                }
                frameHeader = readFrameHeader(inputBase, input, inputLimit);
                verify(frameHeaderSize == frameHeader.headerSize, input, "Unexpected frame header size");
                // single-segment frames use the content size as the window; enforce maxWindowSize for both regular and single-segment frames
                verify(frameHeader.windowSize <= maxWindowSize, input, "Window size too large (not yet supported)");
                verify(frameHeader.windowSize >= 0 || frameHeader.contentSize <= maxWindowSize, input, "Window size too large (not yet supported)");
                input += frameHeaderSize;
                state = State.READ_BLOCK_HEADER;

                resetFrame();
                if (frameHeader.hasChecksum) {
                    partialHash = new XxHash64();
                }
            }
            else {
                verify(frameHeader != null, input, "Frame header is not set");
            }

            if (state == State.READ_BLOCK_HEADER) {
                long inputBufferSize = inputLimit - input;
                if (inputBufferSize < SIZE_OF_BLOCK_HEADER) {
                    inputRequired(inputAddress, outputOffset, input, output, SIZE_OF_BLOCK_HEADER);
                    return;
                }
                if (inputBufferSize >= SIZE_OF_INT) {
                    blockHeader = Memory.getInt(inputBase, input) & 0xFF_FFFF;
                }
                else {
                    // exactly 3 bytes left: stock re-read them as an int here (a debug check), one byte past the input
                    blockHeader = Memory.getByte(inputBase, input) & 0xFF |
                            (Memory.getByte(inputBase, input + 1) & 0xFF) << 8 |
                            (Memory.getByte(inputBase, input + 2) & 0xFF) << 16;
                }
                input += SIZE_OF_BLOCK_HEADER;
                state = State.READ_BLOCK;
            }
            else {
                verify(blockHeader != -1, input, "Block header is not set");
            }

            boolean lastBlock = (blockHeader & 1) != 0;
            if (state == State.READ_BLOCK) {
                int blockType = (blockHeader >>> 1) & 0b11;
                int blockSize = (blockHeader >>> 3) & 0x1F_FFFF; // 21 bits

                resizeWindowBufferIfNecessary(frameHeader, blockType, blockSize);

                int decodedSize;
                switch (blockType) {
                    case RAW_BLOCK: {
                        if (inputLimit - input < blockSize) {
                            inputRequired(inputAddress, outputOffset, input, output, blockSize);
                            return;
                        }
                        verify(windowLimit - windowPosition >= blockSize, input, "window buffer is too small");
                        decodedSize = decodeRawBlock(inputBase, input, blockSize, windowBase, windowPosition, windowLimit);
                        input += blockSize;
                        break;
                    }
                    case RLE_BLOCK: {
                        if (inputLimit - input < 1) {
                            inputRequired(inputAddress, outputOffset, input, output, 1);
                            return;
                        }
                        verify(windowLimit - windowPosition >= blockSize, input, "window buffer is too small");
                        decodedSize = decodeRleBlock(blockSize, inputBase, input, windowBase, windowPosition, windowLimit);
                        input += 1;
                        break;
                    }
                    case COMPRESSED_BLOCK: {
                        if (inputLimit - input < blockSize) {
                            inputRequired(inputAddress, outputOffset, input, output, blockSize);
                            return;
                        }
                        verify(windowLimit - windowPosition >= MAX_BLOCK_SIZE, input, "window buffer is too small");
                        decodedSize = frameDecompressor.decodeCompressedBlock(inputBase, input, blockSize, windowBase, windowPosition, windowLimit, frameHeader.windowSize, maxWindowSize, windowAddress);
                        input += blockSize;
                        break;
                    }
                    default:
                        throw fail(input, "Invalid block type");
                }
                windowPosition += decodedSize;
                decodedFrameSize += decodedSize;
                if (frameHeader.contentSize >= 0) {
                    verify(decodedFrameSize <= frameHeader.contentSize, input, "Decoded size exceeds the frame content size");
                }
                if (lastBlock) {
                    if (frameHeader.contentSize >= 0) {
                        verify(decodedFrameSize == frameHeader.contentSize, input, "Decoded size does not match the frame content size");
                    }
                    state = State.READ_BLOCK_CHECKSUM;
                }
                else {
                    state = State.READ_BLOCK_HEADER;
                }
            }

            if (state == State.READ_BLOCK_CHECKSUM) {
                if (frameHeader.hasChecksum) {
                    if (inputLimit - input < SIZE_OF_INT) {
                        inputRequired(inputAddress, outputOffset, input, output, SIZE_OF_INT);
                        return;
                    }

                    // read checksum
                    int checksum = Memory.getInt(inputBase, input);
                    input += SIZE_OF_INT;

                    checkState(partialHash != null, "Partial hash not set");

                    // hash remaining frame data
                    int pendingOutputSize = toIntExact(windowPosition - windowAddress);
                    partialHash.update(windowBase, toIntExact(windowAddress - ARRAY_BYTE_BASE_OFFSET), pendingOutputSize);

                    // verify hash
                    long hash = partialHash.hash();
                    if (checksum != (int) hash) {
                        throw new MalformedInputException(input, format("Bad checksum. Expected: %s, actual: %s", Integer.toHexString(checksum), Integer.toHexString((int) hash)));
                    }
                }
                state = State.READ_FRAME_MAGIC;
                frameHeader = null;
                blockHeader = -1;
            }
        }
    }

    private void resetFrame()
    {
        frameDecompressor.reset();

        decodedFrameSize = 0;
        windowAddress = ARRAY_BYTE_BASE_OFFSET;
        windowPosition = ARRAY_BYTE_BASE_OFFSET;
    }

    private int computeFlushableOutputSize(FrameHeader frameHeader)
    {
        return max(0, toIntExact(windowPosition - windowAddress - (frameHeader == null ? 0 : frameHeader.computeRequiredOutputBufferLookBackSize())));
    }

    private void resizeWindowBufferIfNecessary(FrameHeader frameHeader, int blockType, int blockSize)
    {
        int maxBlockOutput;
        if (blockType == RAW_BLOCK || blockType == RLE_BLOCK) {
            maxBlockOutput = blockSize;
        }
        else {
            maxBlockOutput = MAX_BLOCK_SIZE;
        }

        // if window buffer is full, move content to head of buffer and continue
        if (windowLimit - windowPosition < MAX_BLOCK_SIZE) {
            // output should have been flushed at the top of this method
            int requiredWindowSize = frameHeader.computeRequiredOutputBufferLookBackSize();
            checkState(windowPosition - windowAddress <= requiredWindowSize, "Expected output to be flushed");

            int windowContentsSize = toIntExact(windowPosition - windowAddress);

            // if window content is currently offset from the array base, move to the front
            if (windowAddress != ARRAY_BYTE_BASE_OFFSET) {
                // copy the window contents to the head of the window buffer
                System.arraycopy(windowBase, toIntExact(windowAddress - ARRAY_BYTE_BASE_OFFSET), windowBase, 0, windowContentsSize);
                windowAddress = ARRAY_BYTE_BASE_OFFSET;
                windowPosition = windowAddress + windowContentsSize;
            }
            checkState(windowAddress == ARRAY_BYTE_BASE_OFFSET, "Window should be packed");

            // if window free space is still too small, grow array
            if (windowLimit - windowPosition < maxBlockOutput) {
                long needed = (long) windowContentsSize + maxBlockOutput;
                // double the current necessary window size
                long newSize = needed * 2;
                // limit to 4x the required window size (or block size if larger)
                newSize = min(newSize, (long) max(requiredWindowSize, MAX_BLOCK_SIZE) * 4);
                // limit to twice the max window size plus one max sized block. With only window + block (as upstream had it),
                // a frame whose window fills the buffer compacts (moves the whole window) before every block: O(window)
                // copying per 128 KiB block. With 2x, compaction runs once per window's worth of output (one extra copy per byte).
                newSize = min(newSize, 2L * maxWindowSize + MAX_BLOCK_SIZE);
                // must allocate at least enough space for a max sized block
                newSize = max(needed, newSize);
                newSize = min(newSize, MAX_ARRAY_SIZE);
                checkState(needed <= newSize, "Computed new window size buffer is not large enough");

                int newWindowSize = toIntExact(newSize);
                windowBase = Arrays.copyOf(windowBase, newWindowSize);
                windowLimit = newWindowSize + ARRAY_BYTE_BASE_OFFSET;
            }

            checkState(windowLimit - windowPosition >= maxBlockOutput, "window buffer is too small");
        }
    }

    private static int determineFrameHeaderSize(final Object inputBase, final long inputAddress, final long inputLimit)
    {
        verify(inputAddress < inputLimit, inputAddress, "Not enough input bytes");

        int frameHeaderDescriptor = Memory.getByte(inputBase, inputAddress) & 0xFF;
        boolean singleSegment = (frameHeaderDescriptor & 0b100000) != 0;
        int dictionaryDescriptor = frameHeaderDescriptor & 0b11;
        int contentSizeDescriptor = frameHeaderDescriptor >>> 6;

        return 1 +
                (singleSegment ? 0 : 1) +
                (dictionaryDescriptor == 0 ? 0 : (1 << (dictionaryDescriptor - 1))) +
                (contentSizeDescriptor == 0 ? (singleSegment ? 1 : 0) : (1 << contentSizeDescriptor));
    }

    private void requestOutput(long inputAddress, int outputOffset, long input, int output, int requestedOutputSize)
    {
        updateInputOutputState(inputAddress, outputOffset, input, output);

        checkArgument(requestedOutputSize >= 0, "requestedOutputSize is negative");
        this.requestedOutputSize = requestedOutputSize;

        this.inputRequired = 0;
    }

    private void inputRequired(long inputAddress, int outputOffset, long input, int output, int inputRequired)
    {
        updateInputOutputState(inputAddress, outputOffset, input, output);

        checkState(inputRequired >= 0, "inputRequired is negative");
        this.inputRequired = inputRequired;

        this.requestedOutputSize = 0;
    }

    private void updateInputOutputState(long inputAddress, int outputOffset, long input, int output)
    {
        inputConsumed = (int) (input - inputAddress);
        checkState(inputConsumed >= 0, "inputConsumed is negative");
        outputBufferUsed = output - outputOffset;
        checkState(outputBufferUsed >= 0, "outputBufferUsed is negative");
    }
}
