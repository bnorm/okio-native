package okio;

import static okio.NativeUtil.allocate;
import static okio.NativeUtil.copyFromArray;
import static okio.NativeUtil.hash0;
import static okio.Util.arrayRangeEquals;
import static okio.Util.checkOffsetAndCount;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import javax.annotation.Nullable;
import sun.nio.ch.DirectBuffer;

/**
 * An immutable sequence of bytes.
 * <p>
 * <p>Byte strings compare lexicographically as a sequence of <strong>unsigned</strong> bytes. That
 * is, the byte string {@code ff} sorts after {@code 00}. This is counter to the sort order of the
 * corresponding bytes, where {@code -1} sorts before {@code 0}.
 * <p>
 * <p><strong>Full disclosure:</strong> this class provides untrusted input and output streams with
 * raw access to the underlying byte array. A hostile stream implementation could keep a reference
 * to the mutable byte string, violating the immutable guarantee of this class. For this reason a
 * byte string's immutability guarantee cannot be relied upon for security in applets and other
 * environments that run both trusted and untrusted code in the same process.
 */
public class NativeByteString implements Serializable, Comparable<NativeByteString> {
  static final char[] HEX_DIGITS =
          {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

  /** A singleton empty {@code ByteString}. */
  public static final NativeByteString EMPTY = NativeByteString.of();

  final long address;
  final int size;
  transient int hashCode; // Lazily computed; 0 if unknown.
  transient String utf8; // Lazily computed.

  NativeByteString(long address, int size) {
    this.address = address; // Trusted internal constructor doesn't clone data.
    this.size = size;

    // Use this ByteString as a way to free the address memory
    if (address != -1) Disposer.addRecord(this, new AddressDisposer(address));
  }

  /**
   * Returns a new byte string containing a clone of the bytes of {@code data}.
   */
  public static NativeByteString of(byte... data) {
    if (data == null) throw new IllegalArgumentException("data == null");

    long address = allocate(data.length);
    copyFromArray(data, 0, address, 0, data.length);
    return new NativeByteString(address, data.length);
  }

  /**
   * Returns a new byte string containing a copy of {@code byteCount} bytes of {@code data} starting
   * at {@code offset}.
   */
  public static NativeByteString of(byte[] data, int offset, int byteCount) {
    if (data == null) throw new IllegalArgumentException("data == null");
    checkOffsetAndCount(data.length, offset, byteCount);

    long address = allocate(data.length);
    copyFromArray(data, offset, address, 0, byteCount);
    return new NativeByteString(address, data.length);
  }

  public static NativeByteString of(ByteBuffer data) {
    // TODO(bnorm): doc that buffer needs to be flipped first
    if (data == null) throw new IllegalArgumentException("data == null");

    int byteCount = data.remaining();
    long address = allocate(byteCount);
    if (data instanceof DirectBuffer) {
      long source = ((DirectBuffer) data).address();
      NativeUtil.copy(source, address, byteCount);
    } else if (data.hasArray()) {
      copyFromArray(data.array(), data.position(), address, 0, byteCount);
    } else {
      // Read-only
      // TODO(bnorm): is there a way to void this Heap -> Heap -> Off-Heap copy?
      // Would a Heap -> Off-Heap -> Off-Heap copy be better? (ie, DirectByteBuffer)
      byte[] copy = new byte[byteCount];
      data.get(copy);
      copyFromArray(copy, 0, address, 0, byteCount);
    }

    return new NativeByteString(address, byteCount);
  }

  /**
   * Returns a new byte string containing the {@code UTF-8} bytes of {@code s}.
   */
  public static NativeByteString encodeUtf8(String s) {
    if (s == null) throw new IllegalArgumentException("s == null");
    // TODO(bnorm): is there a way to avoid this Heap -> Heap -> Off-Heap copy?
    NativeByteString byteString = of(s.getBytes(Util.UTF_8));
    byteString.utf8 = s;
    return byteString;
  }

  /**
   * Returns a new byte string containing the {@code charset}-encoded bytes of {@code s}.
   */
  public static NativeByteString encodeString(String s, Charset charset) {
    if (s == null) throw new IllegalArgumentException("s == null");
    if (charset == null) throw new IllegalArgumentException("charset == null");
    // TODO(bnorm): is there a way to avoid this Heap -> Heap -> Off-Heap copy?
    return of(s.getBytes(charset));
  }

  /**
   * Constructs a new {@code String} by decoding the bytes as {@code UTF-8}.
   */
  public String utf8() {
    String result = utf8;
    // We don't care if we double-allocate in racy code.
    return result != null ? result : (utf8 = new String(toByteArray(), Util.UTF_8));
  }

  /**
   * Constructs a new {@code String} by decoding the bytes using {@code charset}.
   */
  public String string(Charset charset) {
    return toByteString().string(charset);
  }

  /**
   * Returns this byte string encoded as <a
   * href="http://www.ietf.org/rfc/rfc2045.txt">Base64</a>. In violation of the
   * RFC, the returned string does not wrap lines at 76 columns.
   */
  public String base64() {
    return toByteString().base64();
  }

  public ByteString md5() {
    return toByteString().md5();
  }

  public ByteString sha1() {
    return toByteString().sha1();
  }

  public ByteString sha256() {
    return toByteString().sha256();
  }

  public ByteString hmacSha1(ByteString key) {
    return toByteString().hmacSha1(key);
  }

  public ByteString hmacSha256(ByteString key) {
    return toByteString().hmacSha256(key);
  }

  public String base64Url() {
    return toByteString().base64Url();
  }

  /**
   * Decodes the Base64-encoded bytes and returns their value as a byte string.
   * Returns null if {@code base64} is not a Base64-encoded sequence of bytes.
   */
  public static @Nullable NativeByteString decodeBase64(String base64) {
    // TODO(bnorm): native
    throw new UnsupportedOperationException();
  }

  /**
   * Returns this byte string encoded in hexadecimal.
   */
  public String hex() {
    char[] result = new char[size * 2];
    int c = 0;
    for (byte b : toByteArray()) {
      result[c++] = HEX_DIGITS[(b >> 4) & 0xf];
      result[c++] = HEX_DIGITS[b & 0xf];
    }
    return new String(result);
  }

  /**
   * Decodes the hex-encoded bytes and returns their value a byte string.
   */
  public static NativeByteString decodeHex(String hex) {
    if (hex == null) throw new IllegalArgumentException("hex == null");
    if (hex.length() % 2 != 0) throw new IllegalArgumentException("Unexpected hex string: " + hex);

    byte[] result = new byte[hex.length() / 2];
    for (int i = 0; i < result.length; i++) {
      int d1 = decodeHexDigit(hex.charAt(i * 2)) << 4;
      int d2 = decodeHexDigit(hex.charAt(i * 2 + 1));
      result[i] = (byte) (d1 + d2);
    }
    return of(result);
  }

  private static int decodeHexDigit(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    throw new IllegalArgumentException("Unexpected hex digit: " + c);
  }

  /**
   * Reads {@code count} bytes from {@code in} and returns the result.
   *
   * @throws java.io.EOFException if {@code in} has fewer than {@code count}
   *                              bytes to read.
   */
  public static NativeByteString read(InputStream in, int byteCount) throws IOException {
    if (in == null) throw new IllegalArgumentException("in == null");
    if (byteCount < 0) throw new IllegalArgumentException("byteCount < 0: " + byteCount);

    byte[] result = new byte[byteCount];
    for (int offset = 0, read; offset < byteCount; offset += read) {
      read = in.read(result, offset, byteCount - offset);
      if (read == -1) throw new EOFException();
    }
    return of(result, 0, byteCount);
  }

  /**
   * Returns a byte string that is a substring of this byte string, beginning at the specified
   * index until the end of this string. Returns this byte string if {@code beginIndex} is 0.
   */
  public NativeByteString substring(int beginIndex) {
    return substring(beginIndex, size);
  }

  /**
   * Returns a byte string that is a substring of this byte string, beginning at the specified
   * {@code beginIndex} and ends at the specified {@code endIndex}. Returns this byte string if
   * {@code beginIndex} is 0 and {@code endIndex} is the length of this byte string.
   */
  public NativeByteString substring(int beginIndex, int endIndex) {
    if (beginIndex < 0) throw new IllegalArgumentException("beginIndex < 0");
    if (endIndex > size) {
      throw new IllegalArgumentException("endIndex > length(" + size + ")");
    }

    int subLen = endIndex - beginIndex;
    if (subLen < 0) throw new IllegalArgumentException("endIndex < beginIndex");

    if ((beginIndex == 0) && (endIndex == size)) {
      return this;
    }

    long copy = NativeUtil.allocate(subLen);
    NativeUtil.copy(address + beginIndex, copy, subLen);
    return new NativeByteString(copy, subLen);
  }

  /**
   * Returns the byte at {@code pos}.
   */
  public byte getByte(int pos) {
    return NativeUtil.getByte(address, pos);
  }

  /**
   * Returns the number of bytes in this ByteString.
   */
  public int size() {
    return size;
  }

  /**
   * Returns a byte array containing a copy of the bytes in this {@code ByteString}.
   */
  public byte[] toByteArray() {
    byte[] array = new byte[size];
    NativeUtil.copyToArray(address, 0, array, 0, size);
    return array;
  }

  long internalAddress() {
    return address;
  }

  /**
   * Writes the contents of this byte string to {@code out}.
   */
  public void write(OutputStream out) throws IOException {
    if (out == null) throw new IllegalArgumentException("out == null");
    // TODO(bnorm): is there a way to avoid the Off-Heap -> Heap -> stream copy?
    out.write(toByteArray());
  }

  /**
   * Writes the contents of this byte string to {@code buffer}.
   */
  void write(NativeBuffer buffer) {
    long offset = 0;
    while (offset < size) {
      NativeSegment tail = buffer.writableNativeSegment(1);

      long toCopy = Math.min(size - offset, NativeSegment.SIZE - tail.limit);
      NativeUtil.copy(address + offset, tail.address + tail.pos, toCopy);

      offset += toCopy;
      tail.limit += toCopy;
    }

    buffer.size += size;
  }

  /**
   * Returns true if the bytes of this in {@code [offset..offset+byteCount)} equal the bytes of
   * {@code other} in {@code [otherOffset..otherOffset+byteCount)}. Returns false if either range is
   * out of bounds.
   */
  public boolean rangeEquals(int offset, NativeByteString other, int otherOffset, int byteCount) {
    return  otherOffset <= size - byteCount
        && other.rangeEquals(otherOffset, this.address, offset, byteCount);
  }

  /**
   * Returns true if the bytes of this in {@code [offset..offset+byteCount)} equal the bytes of
   * {@code other} in {@code [otherOffset..otherOffset+byteCount)}. Returns false if either range is
   * out of bounds.
   */
  public boolean rangeEquals(int offset, byte[] other, int otherOffset, int byteCount) {
    return offset >= 0 && offset <= size - byteCount
            && otherOffset >= 0 && otherOffset <= other.length - byteCount
            && NativeUtil.rangeEquals(address, offset, other, otherOffset, byteCount);
  }

  boolean rangeEquals(int offset, long other, int otherOffset, int byteCount) {
    return offset >= 0 && offset <= size - byteCount
        && otherOffset >= 0
        && NativeUtil.equals0(this.address + offset, other + otherOffset, byteCount);
  }

  public final boolean startsWith(NativeByteString prefix) {
    return rangeEquals(0, prefix, 0, prefix.size());
  }

  public final boolean startsWith(byte[] prefix) {
    return rangeEquals(0, prefix, 0, prefix.length);
  }

  public final boolean endsWith(NativeByteString suffix) {
    return rangeEquals(size() - suffix.size(), suffix, 0, suffix.size());
  }

  public final boolean endsWith(byte[] suffix) {
    return rangeEquals(size() - suffix.length, suffix, 0, suffix.length);
  }

  public final int indexOf(NativeByteString other) {
    return indexOf(other, 0);
  }

  public final int indexOf(NativeByteString other, int fromIndex) {
    // TODO(bnorm): native indexOf0
    return -1;
  }

  public final int indexOf(byte[] other) {
    return indexOf(other, 0);
  }

  public int indexOf(byte[] other, int fromIndex) {
    // TODO(bnorm): native indexOf0
    return -1;
  }

  public final int lastIndexOf(NativeByteString other) {
    return lastIndexOf(other, size());
  }

  public final int lastIndexOf(NativeByteString other, int fromIndex) {
    // TODO(bnorm): native lastIndexOf0
    return -1;
  }

  public final int lastIndexOf(byte[] other) {
    return lastIndexOf(other, size());
  }

  public int lastIndexOf(byte[] other, int fromIndex) {
    // TODO(bnorm): native lastIndexOf0
    return -1;
  }

  /** Returns a copy as a non-segmented byte string. */
  private ByteString toByteString() {
    return new ByteString(toByteArray());
  }

  @Override public boolean equals(Object o) {
    if (o == this) return true;
    return o instanceof NativeByteString
            && ((NativeByteString) o).size() == size
            && rangeEquals(0, ((NativeByteString) o), 0, size);
  }

  @Override public int hashCode() {
    int result = hashCode;
    return result != 0 ? result : (hashCode = hash0(address, size));
  }

  @Override public int compareTo(NativeByteString byteString) {
    int sizeA = size();
    int sizeB = byteString.size();
    for (int i = 0, size = Math.min(sizeA, sizeB); i < size; i++) {
      int byteA = getByte(i) & 0xff;
      int byteB = byteString.getByte(i) & 0xff;
      if (byteA == byteB) continue;
      return byteA < byteB ? -1 : 1;
    }
    if (sizeA == sizeB) return 0;
    return sizeA < sizeB ? -1 : 1;
  }

  /**
   * Returns a human-readable string that describes the contents of this byte string. Typically this
   * is a string like {@code [text=Hello]} or {@code [hex=0000ffff]}.
   */
  @Override public String toString() {
    if (size == 0) {
      return "[size=0]";
    }

    String text = utf8();
    int i = codePointIndexToCharIndex(text, 64);

    if (i == -1) {
      return size <= 64
              ? "[hex=" + hex() + "]"
              : "[size=" + size + " hex=" + substring(0, 64).hex() + "…]";
    }

    String safeText = text.substring(0, i)
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
    return i < text.length()
            ? "[size=" + size + " text=" + safeText + "…]"
            : "[text=" + safeText + "]";
  }

  static int codePointIndexToCharIndex(String s, int codePointCount) {
    for (int i = 0, j = 0, length = s.length(), c; i < length; i += Character.charCount(c)) {
      if (j == codePointCount) {
        return i;
      }
      c = s.codePointAt(i);
      if ((Character.isISOControl(c) && c != '\n' && c != '\r')
              || c == Buffer.REPLACEMENT_CHARACTER) {
        return -1;
      }
      j++;
    }
    return s.length();
  }

  private void readObject(ObjectInputStream in) throws IOException {
    int dataLength = in.readInt();
    NativeByteString byteString = NativeByteString.read(in, dataLength);
    try {
      Field size = NativeByteString.class.getDeclaredField("size");
      size.setAccessible(true);
      size.set(this, dataLength);
      Field address = NativeByteString.class.getDeclaredField("address");
      address.setAccessible(true);
      address.set(this, byteString.address);
      // TODO(bnorm): make sure byteString doesn't free memory
    } catch (NoSuchFieldException e) {
      throw new AssertionError();
    } catch (IllegalAccessException e) {
      throw new AssertionError();
    }
  }

  private void writeObject(ObjectOutputStream out) throws IOException {
    out.writeInt(size);
    write(out);
  }
}
