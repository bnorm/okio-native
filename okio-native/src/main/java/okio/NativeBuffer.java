package okio;

import javax.annotation.Nullable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static okio.Util.checkOffsetAndCount;
import static okio.Util.reverseBytesLong;

@SuppressWarnings("Duplicates")
public final class NativeBuffer implements BufferedNativeSource, BufferedNativeSink, Cloneable {
  private static final byte[] DIGITS =
          {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};
  static final int REPLACEMENT_CHARACTER = '\ufffd';

  @Nullable
  NativeSegment head;
  long size;

  public NativeBuffer() {
  }

  /**
   * Returns the number of bytes currently in this buffer.
   */
  public long size() {
    return size;
  }

  @Override public NativeBuffer buffer() {
    return this;
  }

  @Override public OutputStream outputStream() {
    return new OutputStream() {
      @Override public void write(int b) {
        writeByte((byte) b);
      }

      @Override public void write(byte[] data, int offset, int byteCount) {
        NativeBuffer.this.write(data, offset, byteCount);
      }

      @Override public void flush() {
      }

      @Override public void close() {
      }

      @Override public String toString() {
        return NativeBuffer.this + ".outputStream()";
      }
    };
  }

  @Override public NativeBuffer emitCompleteSegments() throws IOException {
    return this; // Nowhere to emit to!
  }

  @Override public BufferedNativeSink emit() {
    return this; // Nowhere to emit to!
  }

  @Override public boolean exhausted() {
    return size == 0;
  }

  @Override public void require(long byteCount) throws EOFException {
    if (size < byteCount) throw new EOFException();
  }

  @Override public boolean request(long byteCount) {
    return size >= byteCount;
  }

  @Override public InputStream inputStream() {
    return new InputStream() {
      @Override public int read() {
        if (size > 0) return readByte() & 0xff;
        return -1;
      }

      @Override public int read(byte[] sink, int offset, int byteCount) {
        return NativeBuffer.this.read(sink, offset, byteCount);
      }

      @Override public int available() {
        return (int) Math.min(size, Integer.MAX_VALUE);
      }

      @Override public void close() {
      }

      @Override public String toString() {
        return NativeBuffer.this + ".inputStream()";
      }
    };
  }

  /**
   * Copy the contents of this to {@code out}.
   */
  public NativeBuffer copyTo(Buffer out) {
    return copyTo(out, 0, size);
  }

  /**
   * Copy {@code byteCount} bytes from this, starting at {@code offset}, to
   * {@code out}.
   */
  public NativeBuffer copyTo(Buffer out, long offset, long byteCount) {
    if (out == null) throw new IllegalArgumentException("buffer == null");
    checkOffsetAndCount(size, offset, byteCount);
    if (byteCount == 0) return this;

    // Skip segments that we aren't copying from.
    NativeSegment s = head;
    for (; offset >= (s.limit - s.pos); s = s.next) {
      offset -= (s.limit - s.pos);
    }

    // Copy from one segment at a time.
    for (; byteCount > 0; s = s.next) {
      int pos = (int) (s.pos + offset);
      int toCopy = (int) Math.min(s.limit - pos, byteCount);

      Segment segment = out.writableSegment(toCopy);
      s.copyTo(offset, toCopy, segment.data, segment.pos + segment.limit);
      segment.limit += toCopy;
      out.size += toCopy;

      byteCount -= toCopy;
      offset = 0;
    }

    return this;
  }

  /**
   * Copy the contents of this to {@code out}.
   */
  public NativeBuffer copyTo(OutputStream out) throws IOException {
    return copyTo(out, 0, size);
  }

  /**
   * Copy {@code byteCount} bytes from this, starting at {@code offset}, to
   * {@code out}.
   */
  public NativeBuffer copyTo(OutputStream out, long offset, long byteCount) throws IOException {
    if (out == null) throw new IllegalArgumentException("out == null");
    checkOffsetAndCount(size, offset, byteCount);
    if (byteCount == 0) return this;


    Buffer buffer = new Buffer();
    copyTo(buffer, offset, byteCount);
    buffer.writeTo(out, byteCount);

    return this;
  }

  /**
   * Copy {@code byteCount} bytes from this, starting at {@code offset}, to {@code out}.
   */
  public NativeBuffer copyTo(NativeBuffer out, long offset, long byteCount) {
    if (out == null) throw new IllegalArgumentException("out == null");
    checkOffsetAndCount(size, offset, byteCount);
    if (byteCount == 0) return this;

    out.size += byteCount;

    // Skip segments that we aren't copying from.
    NativeSegment s = head;
    for (; offset >= (s.limit - s.pos); s = s.next) {
      offset -= (s.limit - s.pos);
    }

    // Copy one segment at a time.
    for (; byteCount > 0; s = s.next) {
      NativeSegment copy = new NativeSegment(s);
      copy.pos += offset;
      copy.limit = Math.min(copy.pos + (int) byteCount, copy.limit);
      if (out.head == null) {
        out.head = copy.next = copy.prev = copy;
      } else {
        out.head.prev.push(copy);
      }
      byteCount -= copy.limit - copy.pos;
      offset = 0;
    }

    return this;
  }

  /**
   * Write the contents of this to {@code out}.
   */
  public NativeBuffer writeTo(Buffer out) {
    return writeTo(out, size);
  }

  /**
   * Write {@code byteCount} bytes from this, starting at {@code offset}, to
   * {@code out}.
   */
  public NativeBuffer writeTo(Buffer out, long byteCount) {
    if (out == null) throw new IllegalArgumentException("buffer == null");
    checkOffsetAndCount(size, 0, byteCount);
    if (byteCount == 0) return this;

    NativeSegment s = head;
    for (; byteCount > 0; s = s.next) {
      int toCopy = (int) Math.min(s.limit - s.pos, byteCount);

      Segment segment = out.writableSegment(toCopy);
      s.copyTo(0, toCopy, segment.data, segment.pos + segment.limit);
      segment.limit += toCopy;
      out.size += toCopy;

      s.pos += toCopy;
      size -= toCopy;
      byteCount -= toCopy;

      if (s.pos == s.limit) {
        NativeSegment toRecycle = s;
        head = s = toRecycle.pop();
        NativeSegmentPool.recycle(toRecycle);
      }
    }

    return this;
  }

  /**
   * Write the contents of this to {@code out}.
   */
  public NativeBuffer writeTo(OutputStream out) throws IOException {
    return writeTo(out, size);
  }

  /**
   * Write {@code byteCount} bytes from this to {@code out}.
   */
  public NativeBuffer writeTo(OutputStream out, long byteCount) throws IOException {
    if (out == null) throw new IllegalArgumentException("out == null");
    checkOffsetAndCount(size, 0, byteCount);
    if (byteCount == 0) return this;

    Buffer buffer = new Buffer();
    writeTo(buffer, byteCount);
    buffer.writeTo(out, byteCount);

    return this;
  }

  /**
   * Read and exhaust bytes from {@code in} to this.
   */
  public NativeBuffer readFrom(Buffer in) {
    return readFrom(in, in.size);
  }

  /**
   * Read {@code byteCount} bytes from {@code in} to this.
   */
  public NativeBuffer readFrom(Buffer in, long byteCount) {
    if (in == null) throw new IllegalArgumentException("buffer == null");
    if (byteCount > in.size) throw new IllegalArgumentException("size=" + in.size + " byteCount=" + byteCount);
    checkOffsetAndCount(size, 0, byteCount);
    if (byteCount == 0) return this;

    Segment s = in.head;
    while (byteCount > 0) {
      int toCopy = (int) Math.min(byteCount, s.limit - s.pos);

      NativeSegment tail = writableNativeSegment(toCopy);
      tail.copyFrom(0, toCopy, s.data, s.pos);
      tail.limit += toCopy;
      size += toCopy;

      s.pos += toCopy;
      in.size -= toCopy;
      byteCount -= toCopy;

      if (s.pos == s.limit) {
        Segment toRecycle = s;
        in.head = s = toRecycle.pop();
        SegmentPool.recycle(toRecycle);
      }
    }

    return this;
  }

  /**
   * Read and exhaust bytes from {@code in} to this.
   */
  public NativeBuffer readFrom(InputStream in) throws IOException {
    if (in == null) throw new IllegalArgumentException("in == null");
    Buffer buffer = new Buffer();
    buffer.readFrom(in);
    readFrom(buffer);
    return this;
  }

  /**
   * Read {@code byteCount} bytes from {@code in} to this.
   */
  public NativeBuffer readFrom(InputStream in, long byteCount) throws IOException {
    if (in == null) throw new IllegalArgumentException("in == null");
    if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
    Buffer buffer = new Buffer();
    buffer.readFrom(in, byteCount);
    readFrom(buffer);
    return this;
  }

  /**
   * Returns the number of bytes in segments that are not writable. This is the
   * number of bytes that can be flushed immediately to an underlying sink
   * without harming throughput.
   */
  public long completeSegmentByteCount() {
    long result = size;
    if (result == 0) return 0;

    // Omit the tail if it's still writable.
    NativeSegment tail = head.prev;
    if (tail.limit < NativeSegment.SIZE && tail.owner) {
      result -= tail.limit - tail.pos;
    }

    return result;
  }

  @Override public byte readByte() {
    if (size == 0) throw new IllegalStateException("size == 0");

    NativeSegment segment = head;
    int pos = segment.pos;
    int limit = segment.limit;

    byte b = NativeSegment.UNSAFE.getByte(segment.address + pos);
    pos += 1;
    size -= 1;

    if (pos == limit) {
      head = segment.pop();
      NativeSegmentPool.recycle(segment);
    } else {
      segment.pos = pos;
    }

    return b;
  }

  /**
   * Returns the byte at {@code pos}.
   */
  public byte getByte(long pos) {
    checkOffsetAndCount(size, pos, 1);
    for (NativeSegment s = head; true; s = s.next) {
      int segmentByteCount = s.limit - s.pos;
      if (pos < segmentByteCount) return NativeSegment.UNSAFE.getByte(s.address + s.pos + pos);
      pos -= segmentByteCount;
    }
  }

  @Override public short readShort() {
    if (size < 2) throw new IllegalStateException("size < 2: " + size);

    NativeSegment segment = head;
    int pos = segment.pos;
    int limit = segment.limit;

    // If the short is split across multiple segments, delegate to readByte().
    if (limit - pos < 2) {
      int s = (readByte() & 0xff) << 8
              | (readByte() & 0xff);
      return (short) s;
    }

    int s = NativeSegment.UNSAFE.getShort(segment.address + pos);
    pos += 2;
    size -= 2;

    if (pos == limit) {
      head = segment.pop();
      NativeSegmentPool.recycle(segment);
    } else {
      segment.pos = pos;
    }

    return (short) s;
  }

  @Override public int readInt() {
    if (size < 4) throw new IllegalStateException("size < 4: " + size);

    NativeSegment segment = head;
    int pos = segment.pos;
    int limit = segment.limit;

    // If the int is split across multiple segments, delegate to readByte().
    if (limit - pos < 4) {
      return (readByte() & 0xff) << 24
              | (readByte() & 0xff) << 16
              | (readByte() & 0xff) << 8
              | (readByte() & 0xff);
    }

    int i = NativeSegment.UNSAFE.getInt(segment.address + pos);
    pos -= 4;
    size -= 4;

    if (pos == limit) {
      head = segment.pop();
      NativeSegmentPool.recycle(segment);
    } else {
      segment.pos = pos;
    }

    return i;
  }

  @Override public long readLong() {
    if (size < 8) throw new IllegalStateException("size < 8: " + size);

    NativeSegment segment = head;
    int pos = segment.pos;
    int limit = segment.limit;

    // If the long is split across multiple segments, delegate to readInt().
    if (limit - pos < 8) {
      return (readInt() & 0xffffffffL) << 32
              | (readInt() & 0xffffffffL);
    }

    long v = NativeSegment.UNSAFE.getLong(segment.address + pos);
    pos += 8;
    size -= 8;

    if (pos == limit) {
      head = segment.pop();
      NativeSegmentPool.recycle(segment);
    } else {
      segment.pos = pos;
    }

    return v;
  }

  @Override public short readShortLe() {
    return Util.reverseBytesShort(readShort());
  }

  @Override public int readIntLe() {
    return Util.reverseBytesInt(readInt());
  }

  @Override public long readLongLe() {
    return Util.reverseBytesLong(readLong());
  }

  @Override public long readDecimalLong() {
    if (size == 0) throw new IllegalStateException("size == 0");

    // This value is always built negatively in order to accommodate Long.MIN_VALUE.
    long value = 0;
    int seen = 0;
    boolean negative = false;
    boolean done = false;

    long overflowZone = Long.MIN_VALUE / 10;
    long overflowDigit = (Long.MIN_VALUE % 10) + 1;

    do {
      NativeSegment segment = head;

      long address = segment.address;
      int pos = segment.pos;
      int limit = segment.limit;

      for (; pos < limit; pos++, seen++) {
        byte b = NativeSegment.UNSAFE.getByte(address + pos);
        if (b >= '0' && b <= '9') {
          int digit = '0' - b;

          // Detect when the digit would cause an overflow.
          if (value < overflowZone || value == overflowZone && digit < overflowDigit) {
            Buffer buffer = new Buffer().writeDecimalLong(value).writeByte(b);
            if (!negative) buffer.readByte(); // Skip negative sign.
            throw new NumberFormatException("Number too large: " + buffer.readUtf8());
          }
          value *= 10;
          value += digit;
        } else if (b == '-' && seen == 0) {
          negative = true;
          overflowDigit -= 1;
        } else {
          if (seen == 0) {
            throw new NumberFormatException(
                    "Expected leading [0-9] or '-' character but was 0x" + Integer.toHexString(b));
          }
          // Set a flag to stop iteration. We still need to run through segment updating below.
          done = true;
          break;
        }
      }

      if (pos == limit) {
        head = segment.pop();
        NativeSegmentPool.recycle(segment);
      } else {
        segment.pos = pos;
      }
    } while (!done && head != null);

    size -= seen;
    return negative ? value : -value;
  }

  @Override public long readHexadecimalUnsignedLong() {
    if (size == 0) throw new IllegalStateException("size == 0");

    long value = 0;
    int seen = 0;
    boolean done = false;

    do {
      NativeSegment segment = head;

      long address = segment.address;
      int pos = segment.pos;
      int limit = segment.limit;

      for (; pos < limit; pos++, seen++) {
        int digit;

        byte b = NativeSegment.UNSAFE.getByte(address + pos);
        if (b >= '0' && b <= '9') {
          digit = b - '0';
        } else if (b >= 'a' && b <= 'f') {
          digit = b - 'a' + 10;
        } else if (b >= 'A' && b <= 'F') {
          digit = b - 'A' + 10; // We never write uppercase, but we support reading it.
        } else {
          if (seen == 0) {
            throw new NumberFormatException(
                    "Expected leading [0-9a-fA-F] character but was 0x" + Integer.toHexString(b));
          }
          // Set a flag to stop iteration. We still need to run through segment updating below.
          done = true;
          break;
        }

        // Detect when the shift will overflow.
        if ((value & 0xf000000000000000L) != 0) {
          Buffer buffer = new Buffer().writeHexadecimalUnsignedLong(value).writeByte(b);
          throw new NumberFormatException("Number too large: " + buffer.readUtf8());
        }

        value <<= 4;
        value |= digit;
      }

      if (pos == limit) {
        head = segment.pop();
        NativeSegmentPool.recycle(segment);
      } else {
        segment.pos = pos;
      }
    } while (!done && head != null);

    size -= seen;
    return value;
  }

  @Override public NativeByteString readByteString() {
    return new NativeByteString(readByteArray());
  }

  @Override public NativeByteString readByteString(long byteCount) throws EOFException {
    return new NativeByteString(readByteArray(byteCount));
  }

  @Override public int select(Options options) {
    NativeSegment s = head;
    if (s == null) return options.indexOf(NativeByteString.EMPTY);

    NativeByteString[] byteStrings = options.byteStrings;
    for (int i = 0, listSize = byteStrings.length; i < listSize; i++) {
      NativeByteString b = byteStrings[i];
      if (size >= b.size() && rangeEquals(s, s.pos, b, 0, b.size())) {
        try {
          skip(b.size());
          return i;
        } catch (EOFException e) {
          throw new AssertionError(e);
        }
      }
    }
    return -1;
  }

  /**
   * Returns the index of a value in {@code options} that is either the prefix of this buffer, or
   * that this buffer is a prefix of. Unlike {@link #select} this never consumes the value, even
   * if it is found in full.
   */
  int selectPrefix(Options options) {
    NativeSegment s = head;
    NativeByteString[] byteStrings = options.byteStrings;
    for (int i = 0, listSize = byteStrings.length; i < listSize; i++) {
      NativeByteString b = byteStrings[i];
      int bytesLimit = (int) Math.min(size, b.size());
      if (bytesLimit == 0 || rangeEquals(s, s.pos, b, 0, bytesLimit)) {
        return i;
      }
    }
    return -1;
  }

  @Override public void readFully(NativeBuffer sink, long byteCount) throws EOFException {
    if (size < byteCount) {
      sink.write(this, size); // Exhaust ourselves.
      throw new EOFException();
    }
    sink.write(this, byteCount);
  }

  @Override public long readAll(NativeSink sink) throws IOException {
    long byteCount = size;
    if (byteCount > 0) {
      sink.write(this, byteCount);
    }
    return byteCount;
  }

  @Override public String readUtf8() {
    try {
      return readString(size, Util.UTF_8);
    } catch (EOFException e) {
      throw new AssertionError(e);
    }
  }

  @Override public String readUtf8(long byteCount) throws EOFException {
    return readString(byteCount, Util.UTF_8);
  }

  @Override public String readString(Charset charset) {
    try {
      return readString(size, charset);
    } catch (EOFException e) {
      throw new AssertionError(e);
    }
  }

  @Override public String readString(long byteCount, Charset charset) throws EOFException {
    if (charset == null) throw new IllegalArgumentException("charset == null");
    checkOffsetAndCount(size, 0, byteCount);
    if (byteCount > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("byteCount > Integer.MAX_VALUE: " + byteCount);
    }
    if (byteCount == 0) return "";

    NativeSegment s = head;
    if (s.pos + byteCount > s.limit) {
      // If the string spans multiple segments, delegate to readBytes().
      return new String(readByteArray(byteCount), charset);
    }

    Buffer buffer = new Buffer();
    writeTo(buffer, byteCount);
    return buffer.readString(charset);
  }

  @Override public @Nullable String readUtf8Line() throws EOFException {
    long newline = indexOf((byte) '\n');

    if (newline == -1) {
      return size != 0 ? readUtf8(size) : null;
    }

    return readUtf8Line(newline);
  }

  @Override public String readUtf8LineStrict() throws EOFException {
    return readUtf8LineStrict(Long.MAX_VALUE);
  }

  @Override public String readUtf8LineStrict(long limit) throws EOFException {
    if (limit < 0) throw new IllegalArgumentException("limit < 0: " + limit);
    long scanLength = limit == Long.MAX_VALUE ? Long.MAX_VALUE : limit + 1;
    long newline = indexOf((byte) '\n', 0, scanLength);
    if (newline != -1) return readUtf8Line(newline);
    if (scanLength < size()
            && getByte(scanLength - 1) == '\r' && getByte(scanLength) == '\n') {
      return readUtf8Line(scanLength); // The line was 'limit' UTF-8 bytes followed by \r\n.
    }
    NativeBuffer data = new NativeBuffer();
    copyTo(data, 0, Math.min(32, size()));
    throw new EOFException("\\n not found: limit=" + Math.min(size(), limit)
            + " content=" + data.readByteString().hex() + '…');
  }

  String readUtf8Line(long newline) throws EOFException {
    if (newline > 0 && getByte(newline - 1) == '\r') {
      // Read everything until '\r\n', then skip the '\r\n'.
      String result = readUtf8((newline - 1));
      skip(2);
      return result;

    } else {
      // Read everything until '\n', then skip the '\n'.
      String result = readUtf8(newline);
      skip(1);
      return result;
    }
  }

  @Override public int readUtf8CodePoint() throws EOFException {
    if (size == 0) throw new EOFException();

    byte b0 = getByte(0);
    int codePoint;
    int byteCount;
    int min;

    if ((b0 & 0x80) == 0) {
      // 0xxxxxxx.
      codePoint = b0 & 0x7f;
      byteCount = 1; // 7 bits (ASCII).
      min = 0x0;

    } else if ((b0 & 0xe0) == 0xc0) {
      // 0x110xxxxx
      codePoint = b0 & 0x1f;
      byteCount = 2; // 11 bits (5 + 6).
      min = 0x80;

    } else if ((b0 & 0xf0) == 0xe0) {
      // 0x1110xxxx
      codePoint = b0 & 0x0f;
      byteCount = 3; // 16 bits (4 + 6 + 6).
      min = 0x800;

    } else if ((b0 & 0xf8) == 0xf0) {
      // 0x11110xxx
      codePoint = b0 & 0x07;
      byteCount = 4; // 21 bits (3 + 6 + 6 + 6).
      min = 0x10000;

    } else {
      // We expected the first byte of a code point but got something else.
      skip(1);
      return REPLACEMENT_CHARACTER;
    }

    if (size < byteCount) {
      throw new EOFException("size < " + byteCount + ": " + size
              + " (to read code point prefixed 0x" + Integer.toHexString(b0) + ")");
    }

    // Read the continuation bytes. If we encounter a non-continuation byte, the sequence consumed
    // thus far is truncated and is decoded as the replacement character. That non-continuation byte
    // is left in the stream for processing by the next call to readUtf8CodePoint().
    for (int i = 1; i < byteCount; i++) {
      byte b = getByte(i);
      if ((b & 0xc0) == 0x80) {
        // 0x10xxxxxx
        codePoint <<= 6;
        codePoint |= b & 0x3f;
      } else {
        skip(i);
        return REPLACEMENT_CHARACTER;
      }
    }

    skip(byteCount);

    if (codePoint > 0x10ffff) {
      return REPLACEMENT_CHARACTER; // Reject code points larger than the Unicode maximum.
    }

    if (codePoint >= 0xd800 && codePoint <= 0xdfff) {
      return REPLACEMENT_CHARACTER; // Reject partial surrogates.
    }

    if (codePoint < min) {
      return REPLACEMENT_CHARACTER; // Reject overlong code points.
    }

    return codePoint;
  }

  @Override public byte[] readByteArray() {
    try {
      return readByteArray(size);
    } catch (EOFException e) {
      throw new AssertionError(e);
    }
  }

  @Override public byte[] readByteArray(long byteCount) throws EOFException {
    checkOffsetAndCount(size, 0, byteCount);
    if (byteCount > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("byteCount > Integer.MAX_VALUE: " + byteCount);
    }

    byte[] result = new byte[(int) byteCount];
    readFully(result);
    return result;
  }

  @Override public int read(byte[] sink) {
    return read(sink, 0, sink.length);
  }

  @Override public void readFully(byte[] sink) throws EOFException {
    int offset = 0;
    while (offset < sink.length) {
      int read = read(sink, offset, sink.length - offset);
      if (read == -1) throw new EOFException();
      offset += read;
    }
  }

  @Override public int read(byte[] sink, int offset, int byteCount) {
    checkOffsetAndCount(sink.length, offset, byteCount);

    NativeSegment s = head;
    if (s == null) return -1;
    int toCopy = Math.min(byteCount, s.limit - s.pos);
    s.copyTo(0, toCopy, sink, offset);

    s.pos += toCopy;
    size -= toCopy;

    if (s.pos == s.limit) {
      head = s.pop();
      NativeSegmentPool.recycle(s);
    }

    return toCopy;
  }

  /**
   * Discards all bytes in this buffer. Calling this method when you're done
   * with a buffer will return its segments to the pool.
   */
  public void clear() {
    try {
      skip(size);
    } catch (EOFException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * Discards {@code byteCount} bytes from the head of this buffer.
   */
  @Override public void skip(long byteCount) throws EOFException {
    while (byteCount > 0) {
      if (head == null) throw new EOFException();

      int toSkip = (int) Math.min(byteCount, head.limit - head.pos);
      size -= toSkip;
      byteCount -= toSkip;
      head.pos += toSkip;

      if (head.pos == head.limit) {
        NativeSegment toRecycle = head;
        head = toRecycle.pop();
        NativeSegmentPool.recycle(toRecycle);
      }
    }
  }

  @Override public NativeBuffer write(NativeByteString byteString) {
    if (byteString == null) throw new IllegalArgumentException("byteString == null");
    write(byteString.internalAddress());
    return this;
  }

  @Override public NativeBuffer writeUtf8(String string) {
    return writeUtf8(string, 0, string.length());
  }

  @Override public NativeBuffer writeUtf8(String string, int beginIndex, int endIndex) {
    if (string == null) throw new IllegalArgumentException("string == null");
    if (beginIndex < 0) throw new IllegalArgumentException("beginIndex < 0: " + beginIndex);
    if (endIndex < beginIndex) {
      throw new IllegalArgumentException("endIndex < beginIndex: " + endIndex + " < " + beginIndex);
    }
    if (endIndex > string.length()) {
      throw new IllegalArgumentException(
              "endIndex > string.length: " + endIndex + " > " + string.length());
    }

    // Transcode a UTF-16 Java String to UTF-8 bytes.
    for (int i = beginIndex; i < endIndex; ) {
      int c = string.charAt(i);

      if (c < 0x80) {
        NativeSegment tail = writableNativeSegment(1);
        long address = tail.address;
        int segmentOffset = tail.limit - i;
        int runLimit = Math.min(endIndex, NativeSegment.SIZE - segmentOffset);

        // Emit a 7-bit character with 1 byte.
        NativeSegment.UNSAFE.putByte(address + segmentOffset + i, (byte) c); // 0xxxxxxx
        i += 1;

        // Fast-path contiguous runs of ASCII characters. This is ugly, but yields a ~4x performance
        // improvement over independent calls to writeByte().
        while (i < runLimit) {
          c = string.charAt(i);
          if (c >= 0x80) break;
          NativeSegment.UNSAFE.putByte(address + segmentOffset + i, (byte) c); // 0xxxxxxx
          i += 1;
        }

        int runSize = i + segmentOffset - tail.limit; // Equivalent to i - (previous i).
        tail.limit += runSize;
        size += runSize;

      } else if (c < 0x800) {
        // Emit a 11-bit character with 2 bytes.
        writeByte(c >> 6 | 0xc0); // 110xxxxx
        writeByte(c & 0x3f | 0x80); // 10xxxxxx
        i++;

      } else if (c < 0xd800 || c > 0xdfff) {
        // Emit a 16-bit character with 3 bytes.
        writeByte(c >> 12 | 0xe0); // 1110xxxx
        writeByte(c >> 6 & 0x3f | 0x80); // 10xxxxxx
        writeByte(c & 0x3f | 0x80); // 10xxxxxx
        i++;

      } else {
        // c is a surrogate. Make sure it is a high surrogate & that its successor is a low
        // surrogate. If not, the UTF-16 is invalid, in which case we emit a replacement character.
        int low = i + 1 < endIndex ? string.charAt(i + 1) : 0;
        if (c > 0xdbff || low < 0xdc00 || low > 0xdfff) {
          writeByte('?');
          i++;
          continue;
        }

        // UTF-16 high surrogate: 110110xxxxxxxxxx (10 bits)
        // UTF-16 low surrogate:  110111yyyyyyyyyy (10 bits)
        // Unicode code point:    00010000000000000000 + xxxxxxxxxxyyyyyyyyyy (21 bits)
        int codePoint = 0x010000 + ((c & ~0xd800) << 10 | low & ~0xdc00);

        // Emit a 21-bit character with 4 bytes.
        writeByte(codePoint >> 18 | 0xf0); // 11110xxx
        writeByte(codePoint >> 12 & 0x3f | 0x80); // 10xxxxxx
        writeByte(codePoint >> 6 & 0x3f | 0x80); // 10xxyyyy
        writeByte(codePoint & 0x3f | 0x80); // 10yyyyyy
        i += 2;
      }
    }

    return this;
  }

  @Override public NativeBuffer writeUtf8CodePoint(int codePoint) {
    if (codePoint < 0x80) {
      // Emit a 7-bit code point with 1 byte.
      writeByte(codePoint);

    } else if (codePoint < 0x800) {
      // Emit a 11-bit code point with 2 bytes.
      writeByte(codePoint >> 6 | 0xc0); // 110xxxxx
      writeByte(codePoint & 0x3f | 0x80); // 10xxxxxx

    } else if (codePoint < 0x10000) {
      if (codePoint >= 0xd800 && codePoint <= 0xdfff) {
        // Emit a replacement character for a partial surrogate.
        writeByte('?');
      } else {
        // Emit a 16-bit code point with 3 bytes.
        writeByte(codePoint >> 12 | 0xe0); // 1110xxxx
        writeByte(codePoint >> 6 & 0x3f | 0x80); // 10xxxxxx
        writeByte(codePoint & 0x3f | 0x80); // 10xxxxxx
      }

    } else if (codePoint <= 0x10ffff) {
      // Emit a 21-bit code point with 4 bytes.
      writeByte(codePoint >> 18 | 0xf0); // 11110xxx
      writeByte(codePoint >> 12 & 0x3f | 0x80); // 10xxxxxx
      writeByte(codePoint >> 6 & 0x3f | 0x80); // 10xxxxxx
      writeByte(codePoint & 0x3f | 0x80); // 10xxxxxx

    } else {
      throw new IllegalArgumentException(
              "Unexpected code point: " + Integer.toHexString(codePoint));
    }

    return this;
  }

  @Override public NativeBuffer writeString(String string, Charset charset) {
    return writeString(string, 0, string.length(), charset);
  }

  @Override
  public NativeBuffer writeString(String string, int beginIndex, int endIndex, Charset charset) {
    if (string == null) throw new IllegalArgumentException("string == null");
    if (beginIndex < 0) throw new IllegalAccessError("beginIndex < 0: " + beginIndex);
    if (endIndex < beginIndex) {
      throw new IllegalArgumentException("endIndex < beginIndex: " + endIndex + " < " + beginIndex);
    }
    if (endIndex > string.length()) {
      throw new IllegalArgumentException(
              "endIndex > string.length: " + endIndex + " > " + string.length());
    }
    if (charset == null) throw new IllegalArgumentException("charset == null");
    if (charset.equals(Util.UTF_8)) return writeUtf8(string, beginIndex, endIndex);
    byte[] data = string.substring(beginIndex, endIndex).getBytes(charset);
    return write(data, 0, data.length);
  }

  @Override public NativeBuffer write(byte[] source) {
    if (source == null) throw new IllegalArgumentException("source == null");
    return write(source, 0, source.length);
  }

  @Override public NativeBuffer write(byte[] source, int offset, int byteCount) {
    if (source == null) throw new IllegalArgumentException("source == null");
    checkOffsetAndCount(source.length, offset, byteCount);

    int limit = offset + byteCount;
    while (offset < limit) {
      NativeSegment tail = writableNativeSegment(1);

      int toCopy = Math.min(limit - offset, NativeSegment.SIZE - tail.limit);
      tail.copyFrom(0, toCopy, source, offset);

      offset += toCopy;
      tail.limit += toCopy;
    }

    size += byteCount;
    return this;
  }

  @Override public long writeAll(NativeSource source) throws IOException {
    if (source == null) throw new IllegalArgumentException("source == null");
    long totalBytesRead = 0;
    for (long readCount; (readCount = source.read(this, NativeSegment.SIZE)) != -1; ) {
      totalBytesRead += readCount;
    }
    return totalBytesRead;
  }

  @Override public BufferedNativeSink write(NativeSource source, long byteCount) throws IOException {
    while (byteCount > 0) {
      long read = source.read(this, byteCount);
      if (read == -1) throw new EOFException();
      byteCount -= read;
    }
    return this;
  }

  @Override public NativeBuffer writeByte(int b) {
    NativeSegment tail = writableNativeSegment(1);
    NativeSegment.UNSAFE.putByte(tail.address + tail.limit, (byte) b);
    tail.limit += 1;
    size += 1;
    return this;
  }

  @Override public NativeBuffer writeShort(int s) {
    NativeSegment tail = writableNativeSegment(2);
    NativeSegment.UNSAFE.putShort(tail.address + tail.limit, (short) s);
    tail.limit += 2;
    size += 2;
    return this;
  }

  @Override public NativeBuffer writeShortLe(int s) {
    return writeShort(Util.reverseBytesShort((short) s));
  }

  @Override public NativeBuffer writeInt(int i) {
    NativeSegment tail = writableNativeSegment(4);
    NativeSegment.UNSAFE.putInt(tail.address + tail.limit, i);
    tail.limit += 4;
    size += 4;
    return this;
  }

  @Override public NativeBuffer writeIntLe(int i) {
    return writeInt(Util.reverseBytesInt(i));
  }

  @Override public NativeBuffer writeLong(long v) {
    NativeSegment tail = writableNativeSegment(8);
    NativeSegment.UNSAFE.putLong(tail.address + tail.limit, v);
    tail.limit += 8;
    size += 8;
    return this;
  }

  @Override public NativeBuffer writeLongLe(long v) {
    return writeLong(reverseBytesLong(v));
  }

  @Override public NativeBuffer writeDecimalLong(long v) {
    if (v == 0) {
      // Both a shortcut and required since the following code can't handle zero.
      return writeByte('0');
    }

    boolean negative = false;
    if (v < 0) {
      v = -v;
      if (v < 0) { // Only true for Long.MIN_VALUE.
        return writeUtf8("-9223372036854775808");
      }
      negative = true;
    }

    // Binary search for character width which favors matching lower numbers.
    int width = //
            v < 100000000L
                    ? v < 10000L
                    ? v < 100L
                    ? v < 10L ? 1 : 2
                    : v < 1000L ? 3 : 4
                    : v < 1000000L
                    ? v < 100000L ? 5 : 6
                    : v < 10000000L ? 7 : 8
                    : v < 1000000000000L
                    ? v < 10000000000L
                    ? v < 1000000000L ? 9 : 10
                    : v < 100000000000L ? 11 : 12
                    : v < 1000000000000000L
                    ? v < 10000000000000L ? 13
                    : v < 100000000000000L ? 14 : 15
                    : v < 100000000000000000L
                    ? v < 10000000000000000L ? 16 : 17
                    : v < 1000000000000000000L ? 18 : 19;
    if (negative) {
      ++width;
    }

    NativeSegment tail = writableNativeSegment(width);
    long address = tail.address;
    int pos = tail.limit + width; // We write backwards from right to left.
    while (v != 0) {
      int digit = (int) (v % 10);
      NativeSegment.UNSAFE.putByte(address + pos, DIGITS[digit]);
      pos -= 1;
      v /= 10;
    }
    if (negative) {
      NativeSegment.UNSAFE.putByte(address + pos, (byte) '-');
      pos -= 1;
    }

    tail.limit += width;
    this.size += width;
    return this;
  }

  @Override public NativeBuffer writeHexadecimalUnsignedLong(long v) {
    if (v == 0) {
      // Both a shortcut and required since the following code can't handle zero.
      return writeByte('0');
    }

    int width = Long.numberOfTrailingZeros(Long.highestOneBit(v)) / 4 + 1;

    NativeSegment tail = writableNativeSegment(width);
    long address = tail.address;
    for (int pos = tail.limit + width - 1, start = tail.limit; pos >= start; pos--) {
      NativeSegment.UNSAFE.putByte(address + pos, DIGITS[(int) (v & 0xF)]);
      v >>>= 4;
    }
    tail.limit += width;
    size += width;
    return this;
  }

  /**
   * Returns a tail segment that we can write at least {@code minimumCapacity}
   * bytes to, creating it if necessary.
   */
  NativeSegment writableNativeSegment(int minimumCapacity) {
    if (minimumCapacity < 1 || minimumCapacity > NativeSegment.SIZE) throw new IllegalArgumentException();

    if (head == null) {
      head = NativeSegmentPool.take(); // Acquire a first segment.
      return head.next = head.prev = head;
    }

    NativeSegment tail = head.prev;
    if (tail.limit + minimumCapacity > NativeSegment.SIZE || !tail.owner) {
      tail = tail.push(NativeSegmentPool.take()); // Append a new empty segment to fill up.
    }
    return tail;
  }

  @Override public void write(NativeBuffer source, long byteCount) {
    // Move bytes from the head of the source buffer to the tail of this buffer
    // while balancing two conflicting goals: don't waste CPU and don't waste
    // memory.
    //
    //
    // Don't waste CPU (ie. don't copy data around).
    //
    // Copying large amounts of data is expensive. Instead, we prefer to
    // reassign entire segments from one buffer to the other.
    //
    //
    // Don't waste memory.
    //
    // As an invariant, adjacent pairs of segments in a buffer should be at
    // least 50% full, except for the head segment and the tail segment.
    //
    // The head segment cannot maintain the invariant because the application is
    // consuming bytes from this segment, decreasing its level.
    //
    // The tail segment cannot maintain the invariant because the application is
    // producing bytes, which may require new nearly-empty tail segments to be
    // appended.
    //
    //
    // Moving segments between buffers
    //
    // When writing one buffer to another, we prefer to reassign entire segments
    // over copying bytes into their most compact form. Suppose we have a buffer
    // with these segment levels [91%, 61%]. If we append a buffer with a
    // single [72%] segment, that yields [91%, 61%, 72%]. No bytes are copied.
    //
    // Or suppose we have a buffer with these segment levels: [100%, 2%], and we
    // want to append it to a buffer with these segment levels [99%, 3%]. This
    // operation will yield the following segments: [100%, 2%, 99%, 3%]. That
    // is, we do not spend time copying bytes around to achieve more efficient
    // memory use like [100%, 100%, 4%].
    //
    // When combining buffers, we will compact adjacent buffers when their
    // combined level doesn't exceed 100%. For example, when we start with
    // [100%, 40%] and append [30%, 80%], the result is [100%, 70%, 80%].
    //
    //
    // Splitting segments
    //
    // Occasionally we write only part of a source buffer to a sink buffer. For
    // example, given a sink [51%, 91%], we may want to write the first 30% of
    // a source [92%, 82%] to it. To simplify, we first transform the source to
    // an equivalent buffer [30%, 62%, 82%] and then move the head segment,
    // yielding sink [51%, 91%, 30%] and source [62%, 82%].

    if (source == null) throw new IllegalArgumentException("source == null");
    if (source == this) throw new IllegalArgumentException("source == this");
    checkOffsetAndCount(source.size, 0, byteCount);

    while (byteCount > 0) {
      // Is a prefix of the source's head segment all that we need to move?
      if (byteCount < (source.head.limit - source.head.pos)) {
        NativeSegment tail = head != null ? head.prev : null;
        if (tail != null && tail.owner
                && (byteCount + tail.limit - (tail.shared ? 0 : tail.pos) <= NativeSegment.SIZE)) {
          // Our existing segments are sufficient. Move bytes from source's head to our tail.
          source.head.writeTo(tail, (int) byteCount);
          source.size -= byteCount;
          size += byteCount;
          return;
        } else {
          // We're going to need another segment. Split the source's head
          // segment in two, then move the first of those two to this buffer.
          source.head = source.head.split((int) byteCount);
        }
      }

      // Remove the source's head segment and append it to our tail.
      NativeSegment segmentToMove = source.head;
      long movedByteCount = segmentToMove.limit - segmentToMove.pos;
      source.head = segmentToMove.pop();
      if (head == null) {
        head = segmentToMove;
        head.next = head.prev = head;
      } else {
        NativeSegment tail = head.prev;
        tail = tail.push(segmentToMove);
        tail.compact();
      }
      source.size -= movedByteCount;
      size += movedByteCount;
      byteCount -= movedByteCount;
    }
  }

  @Override public long read(NativeBuffer sink, long byteCount) {
    if (sink == null) throw new IllegalArgumentException("sink == null");
    if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);
    if (size == 0) return -1L;
    if (byteCount > size) byteCount = size;
    sink.write(this, byteCount);
    return byteCount;
  }

  @Override public long indexOf(byte b) {
    return indexOf(b, 0, Long.MAX_VALUE);
  }

  /**
   * Returns the index of {@code b} in this at or beyond {@code fromIndex}, or
   * -1 if this buffer does not contain {@code b} in that range.
   */
  @Override public long indexOf(byte b, long fromIndex) {
    return indexOf(b, fromIndex, Long.MAX_VALUE);
  }

  @Override public long indexOf(byte b, long fromIndex, long toIndex) {
    if (fromIndex < 0 || toIndex < fromIndex) {
      throw new IllegalArgumentException(
              String.format("size=%s fromIndex=%s toIndex=%s", size, fromIndex, toIndex));
    }

    if (toIndex > size) toIndex = size;
    if (fromIndex == toIndex) return -1L;

    NativeSegment s;
    long offset;

    // TODO(jwilson): extract this to a shared helper method when can do so without allocating.
    findNativeSegmentAndOffset:
    {
      // Pick the first segment to scan. This is the first segment with offset <= fromIndex.
      s = head;
      if (s == null) {
        // No segments to scan!
        return -1L;
      } else if (size - fromIndex < fromIndex) {
        // We're scanning in the back half of this buffer. Find the segment starting at the back.
        offset = size;
        while (offset > fromIndex) {
          s = s.prev;
          offset -= (s.limit - s.pos);
        }
      } else {
        // We're scanning in the front half of this buffer. Find the segment starting at the front.
        offset = 0L;
        for (long nextOffset; (nextOffset = offset + (s.limit - s.pos)) < fromIndex; ) {
          s = s.next;
          offset = nextOffset;
        }
      }
    }

    // todo - native
//    // Scan through the segments, searching for b.
//    while (offset < toIndex) {
//      byte[] data = s.data;
//      int limit = (int) Math.min(s.limit, s.pos + toIndex - offset);
//      int pos = (int) (s.pos + fromIndex - offset);
//      for (; pos < limit; pos++) {
//        if (data[pos] == b) {
//          return pos - s.pos + offset;
//        }
//      }
//
//      // Not in this segment. Try the next one.
//      offset += (s.limit - s.pos);
//      fromIndex = offset;
//      s = s.next;
//    }

    return -1L;
  }

  @Override public long indexOf(NativeByteString bytes) throws IOException {
    return indexOf(bytes, 0);
  }

  @Override public long indexOf(NativeByteString bytes, long fromIndex) throws IOException {
    if (bytes.size() == 0) throw new IllegalArgumentException("bytes is empty");
    if (fromIndex < 0) throw new IllegalArgumentException("fromIndex < 0");

    NativeSegment s;
    long offset;

    // TODO(jwilson): extract this to a shared helper method when can do so without allocating.
    findNativeSegmentAndOffset:
    {
      // Pick the first segment to scan. This is the first segment with offset <= fromIndex.
      s = head;
      if (s == null) {
        // No segments to scan!
        return -1L;
      } else if (size - fromIndex < fromIndex) {
        // We're scanning in the back half of this buffer. Find the segment starting at the back.
        offset = size;
        while (offset > fromIndex) {
          s = s.prev;
          offset -= (s.limit - s.pos);
        }
      } else {
        // We're scanning in the front half of this buffer. Find the segment starting at the front.
        offset = 0L;
        for (long nextOffset; (nextOffset = offset + (s.limit - s.pos)) < fromIndex; ) {
          s = s.next;
          offset = nextOffset;
        }
      }
    }

    // todo - native
//    // Scan through the segments, searching for the lead byte. Each time that is found, delegate to
//    // rangeEquals() to check for a complete match.
//    byte b0 = bytes.getByte(0);
//    int bytesSize = bytes.size();
//    long resultLimit = size - bytesSize + 1;
//    while (offset < resultLimit) {
//      // Scan through the current segment.
//      byte[] data = s.data;
//      int segmentLimit = (int) Math.min(s.limit, s.pos + resultLimit - offset);
//      for (int pos = (int) (s.pos + fromIndex - offset); pos < segmentLimit; pos++) {
//        if (data[pos] == b0 && rangeEquals(s, pos + 1, bytes, 1, bytesSize)) {
//          return pos - s.pos + offset;
//        }
//      }
//
//      // Not in this segment. Try the next one.
//      offset += (s.limit - s.pos);
//      fromIndex = offset;
//      s = s.next;
//    }

    return -1L;
  }

  @Override public long indexOfElement(NativeByteString targetBytes) {
    return indexOfElement(targetBytes, 0);
  }

  @Override public long indexOfElement(NativeByteString targetBytes, long fromIndex) {
    if (fromIndex < 0) throw new IllegalArgumentException("fromIndex < 0");

    NativeSegment s;
    long offset;

    // TODO(jwilson): extract this to a shared helper method when can do so without allocating.
    findNativeSegmentAndOffset:
    {
      // Pick the first segment to scan. This is the first segment with offset <= fromIndex.
      s = head;
      if (s == null) {
        // No segments to scan!
        return -1L;
      } else if (size - fromIndex < fromIndex) {
        // We're scanning in the back half of this buffer. Find the segment starting at the back.
        offset = size;
        while (offset > fromIndex) {
          s = s.prev;
          offset -= (s.limit - s.pos);
        }
      } else {
        // We're scanning in the front half of this buffer. Find the segment starting at the front.
        offset = 0L;
        for (long nextOffset; (nextOffset = offset + (s.limit - s.pos)) < fromIndex; ) {
          s = s.next;
          offset = nextOffset;
        }
      }
    }

    // todo - native
//    // Special case searching for one of two bytes. This is a common case for tools like Moshi,
//    // which search for pairs of chars like `\r` and `\n` or {@code `"` and `\`. The impact of this
//    // optimization is a ~5x speedup for this case without a substantial cost to other cases.
//    if (targetBytes.size() == 2) {
//      // Scan through the segments, searching for either of the two bytes.
//      byte b0 = targetBytes.getByte(0);
//      byte b1 = targetBytes.getByte(1);
//      while (offset < size) {
//        byte[] data = s.data;
//        for (int pos = (int) (s.pos + fromIndex - offset), limit = s.limit; pos < limit; pos++) {
//          int b = data[pos];
//          if (b == b0 || b == b1) {
//            return pos - s.pos + offset;
//          }
//        }
//
//        // Not in this segment. Try the next one.
//        offset += (s.limit - s.pos);
//        fromIndex = offset;
//        s = s.next;
//      }
//    } else {
//      // Scan through the segments, searching for a byte that's also in the array.
//      byte[] targetByteArray = targetBytes.internalArray();
//      while (offset < size) {
//        byte[] data = s.data;
//        for (int pos = (int) (s.pos + fromIndex - offset), limit = s.limit; pos < limit; pos++) {
//          int b = data[pos];
//          for (byte t : targetByteArray) {
//            if (b == t) return pos - s.pos + offset;
//          }
//        }
//
//        // Not in this segment. Try the next one.
//        offset += (s.limit - s.pos);
//        fromIndex = offset;
//        s = s.next;
//      }
//    }

    return -1L;
  }

  @Override public boolean rangeEquals(long offset, NativeByteString bytes) {
    return rangeEquals(offset, bytes, 0, bytes.size());
  }

  @Override public boolean rangeEquals(
          long offset, NativeByteString bytes, int bytesOffset, int byteCount) {
    if (offset < 0
            || bytesOffset < 0
            || byteCount < 0
            || size - offset < byteCount
            || bytes.size() - bytesOffset < byteCount) {
      return false;
    }
    for (int i = 0; i < byteCount; i++) {
      if (getByte(offset + i) != bytes.getByte(bytesOffset + i)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns true if the range within this buffer starting at {@code segmentPos} in {@code segment}
   * is equal to {@code bytes[bytesOffset..bytesLimit)}.
   */
  // todo - native
  private boolean rangeEquals(
          NativeSegment segment, int segmentPos, NativeByteString bytes, int bytesOffset, int bytesLimit) {
    throw new UnsupportedOperationException("native");
//    int segmentLimit = segment.limit;
//    byte[] data = segment.data;
//
//    for (int i = bytesOffset; i < bytesLimit; ) {
//      if (segmentPos == segmentLimit) {
//        segment = segment.next;
//        data = segment.data;
//        segmentPos = segment.pos;
//        segmentLimit = segment.limit;
//      }
//
//      if (data[segmentPos] != bytes.getByte(i)) {
//        return false;
//      }
//
//      segmentPos++;
//      i++;
//    }
//
//    return true;
  }

  @Override public void flush() {
  }

  @Override public void close() {
  }

  @Override public Timeout timeout() {
    return Timeout.NONE;
  }

  /**
   * For testing. This returns the sizes of the segments in this buffer.
   */
  List<Integer> segmentSizes() {
    if (head == null) return Collections.emptyList();
    List<Integer> result = new ArrayList<>();
    result.add(head.limit - head.pos);
    for (NativeSegment s = head.next; s != head; s = s.next) {
      result.add(s.limit - s.pos);
    }
    return result;
  }

  /**
   * Returns the 128-bit MD5 hash of this buffer.
   */
  public NativeByteString md5() {
    Buffer buffer = new Buffer();
    copyTo(buffer);
    return buffer.md5();
  }

  /**
   * Returns the 160-bit SHA-1 hash of this buffer.
   */
  public NativeByteString sha1() {
    Buffer buffer = new Buffer();
    copyTo(buffer);
    return buffer.sha1();
  }

  /**
   * Returns the 256-bit SHA-256 hash of this buffer.
   */
  public NativeByteString sha256() {
    Buffer buffer = new Buffer();
    copyTo(buffer);
    return buffer.sha256();
  }

  /**
   * Returns the 512-bit SHA-512 hash of this buffer.
   */
  public NativeByteString sha512() {
    Buffer buffer = new Buffer();
    copyTo(buffer);
    return buffer.sha512();
  }

  /**
   * Returns the 160-bit SHA-1 HMAC of this buffer.
   */
  public NativeByteString hmacSha1(NativeByteString key) {
    Buffer buffer = new Buffer();
    copyTo(buffer);
    return buffer.hmacSha1(key);
  }

  /**
   * Returns the 256-bit SHA-256 HMAC of this buffer.
   */
  public NativeByteString hmacSha256(NativeByteString key) {
    Buffer buffer = new Buffer();
    copyTo(buffer);
    return buffer.hmacSha256(key);
  }

  /**
   * Returns the 512-bit SHA-512 HMAC of this buffer.
   */
  public NativeByteString hmacSha512(NativeByteString key) {
    Buffer buffer = new Buffer();
    copyTo(buffer);
    return buffer.hmacSha512(key);
  }

  @Override public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof NativeBuffer)) return false;
    NativeBuffer that = (NativeBuffer) o;
    if (size != that.size) return false;
    if (size == 0) return true; // Both buffers are empty.

    NativeSegment sa = this.head;
    NativeSegment sb = that.head;
    int posA = sa.pos;
    int posB = sb.pos;

    for (long pos = 0, count; pos < size; pos += count) {
      count = Math.min(sa.limit - posA, sb.limit - posB);

      // todo - native
//      for (int i = 0; i < count; i++) {
//        if (sa.data[posA++] != sb.data[posB++]) return false;
//      }

      if (posA == sa.limit) {
        sa = sa.next;
        posA = sa.pos;
      }

      if (posB == sb.limit) {
        sb = sb.next;
        posB = sb.pos;
      }
    }

    return true;
  }

  @Override public int hashCode() {
    NativeSegment s = head;
    if (s == null) return 0;
    int result = 1;
    do {
      // todo - native
//      for (int pos = s.pos, limit = s.limit; pos < limit; pos++) {
//        result = 31 * result + s.data[pos];
//      }
      s = s.next;
    } while (s != head);
    return result;
  }

  /**
   * Returns a human-readable string that describes the contents of this buffer. Typically this
   * is a string like {@code [text=Hello]} or {@code [hex=0000ffff]}.
   */
  @Override public String toString() {
    return snapshot().toString();
  }

  /**
   * Returns a deep copy of this buffer.
   */
  @Override public NativeBuffer clone() {
    NativeBuffer result = new NativeBuffer();
    if (size == 0 || head == null) return result;

    result.head = new NativeSegment(head);
    result.head.next = result.head.prev = result.head;
    for (NativeSegment s = head.next; s != head; s = s.next) {
      result.head.prev.push(new NativeSegment(s));
    }
    result.size = size;
    return result;
  }

  /**
   * Returns an immutable copy of this buffer as a byte string.
   */
  public NativeByteString snapshot() {
    if (size > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("size > Integer.MAX_VALUE: " + size);
    }
    return snapshot((int) size);
  }

  /**
   * Returns an immutable copy of the first {@code byteCount} bytes of this buffer as a byte string.
   */
  public NativeByteString snapshot(int byteCount) {
    throw new UnsupportedOperationException();
//    if (byteCount == 0) return ByteString.EMPTY;
//    return new NativeSegmentedByteString(this, byteCount);
  }
}
