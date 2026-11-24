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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Bounds-checked memory accessors (they replace the JDK-internal unsafe accessors upstream used). A "base" is either a {@code byte[]} or a
 * {@link ByteBuffer} (direct, mapped or read-only heap); an "address" is the absolute index into it. Every access is
 * checked by the JVM, so a bad offset can only throw {@link IndexOutOfBoundsException}, never touch other memory.
 * Multi-byte values are always little endian, independent of platform and buffer byte order.
 */
final class Memory
{
    /** Addresses are plain array indexes, so the array base offset is zero. */
    static final int ARRAY_BYTE_BASE_OFFSET = 0;

    private static final VarHandle SHORT_ARRAY = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    static final VarHandle INT_ARRAY = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    static final VarHandle LONG_ARRAY = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle SHORT_BUFFER = MethodHandles.byteBufferViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_BUFFER = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_BUFFER = MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private Memory() {}

    static byte getByte(Object base, long address)
    {
        if (base instanceof byte[]) {
            return ((byte[]) base)[(int) address];
        }
        return ((ByteBuffer) base).get((int) address);
    }

    static short getShort(Object base, long address)
    {
        if (base instanceof byte[]) {
            return (short) SHORT_ARRAY.get((byte[]) base, (int) address);
        }
        return (short) SHORT_BUFFER.get((ByteBuffer) base, (int) address);
    }

    static int getInt(Object base, long address)
    {
        if (base instanceof byte[]) {
            return (int) INT_ARRAY.get((byte[]) base, (int) address);
        }
        return (int) INT_BUFFER.get((ByteBuffer) base, (int) address);
    }

    static long getLong(Object base, long address)
    {
        if (base instanceof byte[]) {
            return (long) LONG_ARRAY.get((byte[]) base, (int) address);
        }
        return (long) LONG_BUFFER.get((ByteBuffer) base, (int) address);
    }

    static void putByte(Object base, long address, byte value)
    {
        if (base instanceof byte[]) {
            ((byte[]) base)[(int) address] = value;
            return;
        }
        ((ByteBuffer) base).put((int) address, value);
    }

    static void putShort(Object base, long address, short value)
    {
        if (base instanceof byte[]) {
            SHORT_ARRAY.set((byte[]) base, (int) address, value);
            return;
        }
        SHORT_BUFFER.set((ByteBuffer) base, (int) address, value);
    }

    static void putInt(Object base, long address, int value)
    {
        if (base instanceof byte[]) {
            INT_ARRAY.set((byte[]) base, (int) address, value);
            return;
        }
        INT_BUFFER.set((ByteBuffer) base, (int) address, value);
    }

    static void putLong(Object base, long address, long value)
    {
        if (base instanceof byte[]) {
            LONG_ARRAY.set((byte[]) base, (int) address, value);
            return;
        }
        LONG_BUFFER.set((ByteBuffer) base, (int) address, value);
    }

    /** Copies {@code length} bytes; overlapping ranges in one array behave like {@link System#arraycopy}. */
    static void copyMemory(Object sourceBase, long sourceAddress, Object targetBase, long targetAddress, long length)
    {
        int len = Math.toIntExact(length);
        if (sourceBase instanceof byte[]) {
            if (targetBase instanceof byte[]) {
                System.arraycopy((byte[]) sourceBase, (int) sourceAddress, (byte[]) targetBase, (int) targetAddress, len);
            }
            else {
                ((ByteBuffer) targetBase).put((int) targetAddress, (byte[]) sourceBase, (int) sourceAddress, len);
            }
        }
        else if (targetBase instanceof byte[]) {
            ((ByteBuffer) sourceBase).get((int) sourceAddress, (byte[]) targetBase, (int) targetAddress, len);
        }
        else {
            ((ByteBuffer) targetBase).put((int) targetAddress, (ByteBuffer) sourceBase, (int) sourceAddress, len);
        }
    }

    /** The base and start address for the region between a buffer's position and limit. */
    static Object base(ByteBuffer buffer)
    {
        if (buffer.hasArray()) {
            return buffer.array();
        }
        return buffer;
    }

    static long address(ByteBuffer buffer, int index)
    {
        if (buffer.hasArray()) {
            return (long) buffer.arrayOffset() + index;
        }
        return index;
    }
}
