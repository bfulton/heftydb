package com.jordanwilliams.heftydb.util;

import sun.misc.Unsafe;

import java.nio.ByteBuffer;

/**
 * Static versions of some {@code com.google.protobuf.CodedInputStream} and
 * {@code com.google.protobuf.CodedOutputStream} methods, modified to work with
 * {@link java.nio.ByteBuffer} and {@link sun.misc.Unsafe}.
 *
 * Original code is Copyright 2008 Google Inc.
 */
public abstract class VarInts {
    /**
     * Compute the number of bytes that would be needed to encode a varint.
     * {@code value} is treated as unsigned, so it won't be sign-extended if
     * negative.
     */
    public static int computeRawVarint32Size(final int value) {
        if ((value & (0xffffffff <<  7)) == 0) return 1;
        if ((value & (0xffffffff << 14)) == 0) return 2;
        if ((value & (0xffffffff << 21)) == 0) return 3;
        if ((value & (0xffffffff << 28)) == 0) return 4;
        return 5;
    }

    /** Compute the number of bytes that would be needed to encode a varint. */
    public static int computeRawVarint64Size(final long value) {
        if ((value & (0xffffffffffffffffL <<  7)) == 0) return 1;
        if ((value & (0xffffffffffffffffL << 14)) == 0) return 2;
        if ((value & (0xffffffffffffffffL << 21)) == 0) return 3;
        if ((value & (0xffffffffffffffffL << 28)) == 0) return 4;
        if ((value & (0xffffffffffffffffL << 35)) == 0) return 5;
        if ((value & (0xffffffffffffffffL << 42)) == 0) return 6;
        if ((value & (0xffffffffffffffffL << 49)) == 0) return 7;
        if ((value & (0xffffffffffffffffL << 56)) == 0) return 8;
        if ((value & (0xffffffffffffffffL << 63)) == 0) return 9;
        return 10;
    }

    /* read {@link int} varint32 from buffer */
    public static int readRawVarint32(Unsafe unsafe, long addr) {
        byte tmp = unsafe.getByte(addr++);
        if (tmp >= 0) {
            return tmp;
        }
        int result = tmp & 0x7f;
        if ((tmp = unsafe.getByte(addr++)) >= 0) {
            result |= tmp << 7;
        } else {
            result |= (tmp & 0x7f) << 7;
            if ((tmp = unsafe.getByte(addr++)) >= 0) {
                result |= tmp << 14;
            } else {
                result |= (tmp & 0x7f) << 14;
                if ((tmp = unsafe.getByte(addr++)) >= 0) {
                    result |= tmp << 21;
                } else {
                    result |= (tmp & 0x7f) << 21;
                    result |= (tmp = unsafe.getByte(addr++)) << 28;
                    if (tmp < 0) {
                        // Discard upper 32 bits.
                        for (int i = 0; i < 5; i++) {
                            if (unsafe.getByte(addr++) >= 0) {
                                return result;
                            }
                        }
                        throw new IllegalStateException("malformed varint in buffer");
                    }
                }
            }
        }
        return result;
    }

    /* read {@link long} varint64 from buffer */
    public static long readRawVarint64(Unsafe unsafe, long addr) {
        int shift = 0;
        long result = 0;
        while (shift < 64) {
            final byte b = unsafe.getByte(addr++);
            result |= (long)(b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        throw new IllegalStateException("malformed varint in buffer");
    }

    /* write {@link int} varint32 to buffer */
    public static void writeRawVarint32(ByteBuffer buf, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) {
                buf.put((byte) value);
                return;
            } else {
                buf.put((byte) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
        }
    }

    /* write {@link long} varint64 to buffer */
    public static void writeRawVarint64(ByteBuffer buf, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                buf.put((byte) value);
                return;
            } else {
                buf.put((byte) ((value & 0x7F) | 0x80));
                value >>>= 7;
            }
        }
    }
}
