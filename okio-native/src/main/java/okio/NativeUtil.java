package okio;

import java.lang.reflect.Field;
import sun.misc.Unsafe;

final class NativeUtil {

  static final Unsafe UNSAFE;

  // This number limits the number of bytes to copy per call to Unsafe's
  // copyMemory method. A limit is imposed to allow for safepoint polling
  // during a large copy
  static final long UNSAFE_COPY_THRESHOLD = 1024L * 1024L;

  static {
    try {
      Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
      theUnsafe.setAccessible(true);
      UNSAFE = (Unsafe) theUnsafe.get(null);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  static final long arrayBaseOffset = Unsafe.ARRAY_BYTE_BASE_OFFSET;

  static native boolean equals0(long address1, long address2, long limit);

  static native int hash0(long address, long limit);

  static long allocate(long byteCount) {
    return UNSAFE.allocateMemory(byteCount);
  }

  static void free(long address) {
    UNSAFE.freeMemory(address);
  }

  static void copy(long source, long destination, long byteCount) {
    UNSAFE.copyMemory(source, destination, byteCount);
  }

  static void copyFromArray(byte[] src, long srcOffset, long dest, long destOffset,
      long byteCount) {
    long offset = arrayBaseOffset + srcOffset;
    dest += destOffset;
    while (byteCount > 0) {
      long size = Math.min(byteCount, UNSAFE_COPY_THRESHOLD);
      UNSAFE.copyMemory(src, offset, null, dest, size);
      byteCount -= size;
      offset += size;
      dest += size;
    }
  }

  static void copyToArray(long src, long srcOffset, byte[] dest, long destOffset, long byteCount) {
    long offset = arrayBaseOffset + destOffset;
    src += srcOffset;
    while (byteCount > 0) {
      long size = Math.min(byteCount, UNSAFE_COPY_THRESHOLD);
      UNSAFE.copyMemory(null, src, dest, offset, size);
      byteCount -= size;
      src += size;
      offset += size;
    }
  }

  static byte getByte(long src, long offset) {
    return UNSAFE.getByte(src + offset);
  }

  static short getShort(long address, int offset) {
    return UNSAFE.getShort(address + offset);
  }

  static int getInt(long address, int offset) {
    return UNSAFE.getInt(address + offset);
  }

  static long getLong(long address, long offset) {
    return UNSAFE.getLong(address + offset);
  }

  static void putByte(long address, long offset, byte c) {
    UNSAFE.putByte(address + offset, c);
  }

  static void putShort(long address, long offset, short s) {
    UNSAFE.putShort(address + offset, s);
  }

  static void putInt(long address, long offset, int i) {
    UNSAFE.putInt(address + offset, i);
  }

  static void putLong(long address, long offset, long l) {
    UNSAFE.putLong(address + offset, l);
  }

  public static boolean rangeEquals(long address, int offset, byte[] other, int otherOffset,
      int byteCount) {
    // TODO(bnorm): hmm...
    // return equals0(address + offset, other + otherOffset, byteCount);
    return false;
  }
}
