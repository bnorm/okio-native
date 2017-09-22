package okio;

import javax.annotation.Nullable;

import static okio.NativeUtil.UNSAFE;
import static okio.NativeUtil.arrayBaseOffset;

/**
 * A segment of a buffer.
 *
 * <p>Each segment in a buffer is a circularly-linked list node referencing the following and
 * preceding segments in the buffer.
 *
 * <p>Each segment in the pool is a singly-linked list node referencing the rest of segments in the
 * pool.
 *
 * <p>The underlying byte arrays of segments may be shared between buffers and byte strings. When a
 * segment's byte array is shared the segment may not be recycled, nor may its byte data be changed.
 * The lone exception is that the owner segment is allowed to append to the segment, writing data at
 * {@code limit} and beyond. There is a single owning segment for each byte array. Positions,
 * limits, prev, and next references are not shared.
 */
final class NativeSegment {

  /** The size of all segments in bytes. */
  static final int SIZE = 8192;

  /** Segments will be shared when doing so avoids {@code arraycopy()} of this many bytes. */
  static final int SHARE_MINIMUM = 1024;

  final long address;

  /** The next byte of application data byte to read in this segment. */
  int pos;

  /** The first byte of available data ready to be written to. */
  int limit;

  /** True if other segments or byte strings use the same byte array. */
  boolean shared;

  /** True if this segment owns the byte array and can append to it, extending {@code limit}. */
  boolean owner;

  /** Next segment in a linked or circularly-linked list. */
  NativeSegment next;

  /** Previous segment in a circularly-linked list. */
  NativeSegment prev;

  NativeSegment() {
    this.address = UNSAFE.allocateMemory(SIZE);
    this.owner = true;
    this.shared = false;
  }

  NativeSegment(NativeSegment shareFrom) {
    this(shareFrom.address, shareFrom.pos, shareFrom.limit);
    shareFrom.shared = true;
  }

  NativeSegment(long address, int pos, int limit) {
    this.address = address;
    this.pos = pos;
    this.limit = limit;
    this.owner = false;
    this.shared = true;
  }

  public void free() {
    UNSAFE.freeMemory(address);
  }

  /**
   * Removes this segment of a circularly-linked list and returns its successor.
   * Returns null if the list is now empty.
   */
  public @Nullable NativeSegment pop() {
    NativeSegment result = next != this ? next : null;
    prev.next = next;
    next.prev = prev;
    next = null;
    prev = null;
    return result;
  }

  /**
   * Appends {@code segment} after this segment in the circularly-linked list.
   * Returns the pushed segment.
   */
  public NativeSegment push(NativeSegment segment) {
    segment.prev = this;
    segment.next = next;
    next.prev = segment;
    next = segment;
    return segment;
  }

  /**
   * Splits this head of a circularly-linked list into two segments. The first
   * segment contains the data in {@code [pos..pos+byteCount)}. The second
   * segment contains the data in {@code [pos+byteCount..limit)}. This can be
   * useful when moving partial segments from one buffer to another.
   *
   * <p>Returns the new head of the circularly-linked list.
   */
  public NativeSegment split(int byteCount) {
    if (byteCount <= 0 || byteCount > limit - pos) throw new IllegalArgumentException();
    NativeSegment prefix;

    // We have two competing performance goals:
    //  - Avoid copying data. We accomplish this by sharing segments.
    //  - Avoid short shared segments. These are bad for performance because they are readonly and
    //    may lead to long chains of short segments.
    // To balance these goals we only share segments when the copy will be large.
    if (byteCount >= SHARE_MINIMUM) {
      prefix = new NativeSegment(this);
    } else {
      prefix = NativeSegmentPool.take();
      UNSAFE.copyMemory(address + pos, prefix.address, byteCount);
    }

    prefix.limit = prefix.pos + byteCount;
    pos += byteCount;
    prev.push(prefix);
    return prefix;
  }

  /**
   * Call this when the tail and its predecessor may both be less than half
   * full. This will copy data so that segments can be recycled.
   */
  public void compact() {
    if (prev == this) throw new IllegalStateException();
    if (!prev.owner) return; // Cannot compact: prev isn't writable.
    int byteCount = limit - pos;
    int availableByteCount = SIZE - prev.limit + (prev.shared ? 0 : prev.pos);
    if (byteCount > availableByteCount) return; // Cannot compact: not enough writable space.
    writeTo(prev, byteCount);
    pop();
    NativeSegmentPool.recycle(this);
  }

  /** Moves {@code byteCount} bytes from this segment to {@code sink}. */
  public void writeTo(NativeSegment sink, int byteCount) {
    if (!sink.owner) throw new IllegalArgumentException();
    if (sink.limit + byteCount > SIZE) {
      // We can't fit byteCount bytes at the sink's current position. Shift sink first.
      if (sink.shared) throw new IllegalArgumentException();
      if (sink.limit + byteCount - sink.pos > SIZE) throw new IllegalArgumentException();
      UNSAFE.copyMemory(sink.address + pos, sink.address, sink.limit - sink.pos);
      sink.limit -= sink.pos;
      sink.pos = 0;
    }

    UNSAFE.copyMemory(address + pos, sink.address + sink.limit, byteCount);
    sink.limit += byteCount;
    pos += byteCount;
  }

  void copyTo(long offset, long length, byte[] dst, long dstPos) {
    UNSAFE.copyMemory(null, address + pos + offset, dst, arrayBaseOffset + dstPos, length);
  }

  void copyFrom(long offset, long length, byte[] src, long srcPos) {
    UNSAFE.copyMemory(src, arrayBaseOffset + srcPos, null, address + limit + offset, length);
  }
}
