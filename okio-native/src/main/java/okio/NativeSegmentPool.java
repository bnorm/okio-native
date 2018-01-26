package okio;

import javax.annotation.Nullable;

/**
 * A collection of unused segments, necessary to avoid GC churn and zero-fill.
 * This pool is a thread-safe static singleton.
 */
final class NativeSegmentPool {
  /** The maximum number of bytes to pool. */
  // TODO: Is 64 KiB a good maximum size? Do we ever have that many idle segments?
  static final long MAX_SIZE = 64 * 1024; // 64 KiB.

  /** Singly-linked list of segments. */
  static @Nullable NativeSegment next;

  /** Total bytes in this pool. */
  static long byteCount;

  private NativeSegmentPool() {
  }

  static NativeSegment take() {
    synchronized (NativeSegmentPool.class) {
      if (next != null) {
        NativeSegment result = next;
        next = result.next;
        result.next = null;
        byteCount -= NativeSegment.SIZE;
        return result;
      }
    }
    return new NativeSegment(); // Pool is empty. Don't zero-fill while holding a lock.
  }

  static void recycle(NativeSegment segment) {
    if (segment.next != null || segment.prev != null) throw new IllegalArgumentException();
    if (segment.shared) return; // This segment cannot be recycled.
    synchronized (NativeSegmentPool.class) {
      if (byteCount + NativeSegment.SIZE > MAX_SIZE) return; // Pool is full.
      byteCount += NativeSegment.SIZE;
      segment.next = next;
      segment.pos = segment.limit = 0;
      next = segment;
    }
  }
}
