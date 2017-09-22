package okio;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

final class NativeUtil {
  static final Unsafe UNSAFE;

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

  static void copyFromArray(byte[] src, long srcPos, long dstAddr, long length) {
    UNSAFE.copyMemory(src, arrayBaseOffset + srcPos, null, dstAddr, length);
  }

  static native boolean equals0(long address1, long address2, long limit);

  static native int hash0(long address, long limit);
}
