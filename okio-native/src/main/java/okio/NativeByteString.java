package okio;

import sun.nio.ch.DirectBuffer;

import javax.annotation.Nullable;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import static okio.NativeUtil.*;
import static okio.Util.arrayRangeEquals;
import static okio.Util.checkOffsetAndCount;

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

  /**
   * A singleton empty {@code ByteString}.
   */
  public static final NativeByteString EMPTY = NativeByteString.of();

  final long address;
  final int size;
  transient int hashCode; // Lazily computed; 0 if unknown.
  transient String utf8; // Lazily computed.

  NativeByteString(long address, int size) {
    // todo(bnorm) - need to free the address when finalized
    this.address = address; // Trusted internal constructor doesn't clone data.
    this.size = size;
  }

  /**
   * Returns a new byte string containing a clone of the bytes of {@code data}.
   */
  public static NativeByteString of(byte... data) {
    if (data == null) throw new IllegalArgumentException("data == null");

    long address = UNSAFE.allocateMemory(data.length);
    copyFromArray(data, 0, address, data.length);
    return new NativeByteString(address, data.length);
  }

  /**
   * Returns a new byte string containing a copy of {@code byteCount} bytes of {@code data} starting
   * at {@code offset}.
   */
  public static NativeByteString of(byte[] data, int offset, int byteCount) {
    if (data == null) throw new IllegalArgumentException("data == null");
    checkOffsetAndCount(data.length, offset, byteCount);

    long address = UNSAFE.allocateMemory(data.length);
    copyFromArray(data, offset, address, byteCount);
    return new NativeByteString(address, data.length);
  }

  public static NativeByteString of(ByteBuffer data) {
    if (data == null) throw new IllegalArgumentException("data == null");

    int byteCount = data.remaining();
    long address = UNSAFE.allocateMemory(byteCount);
    if (data instanceof DirectBuffer) {
      UNSAFE.copyMemory(((DirectBuffer) data).address(), address, byteCount);
    } else if (data.hasArray()) {
      copyFromArray(data.array(), data.position(), address, byteCount);
    } else {
      // todo(bnorm) is there a way to void this Heap -> Heap -> Off-Heap copy?
      // Would a Heap -> Off-Heap -> Off-Heap copy be better? (ie, DirectByteBuffer)
      byte[] copy = new byte[byteCount];
      data.get(copy);
      copyFromArray(copy, 0, address, byteCount);
    }

    return new NativeByteString(address, byteCount);
  }

  /**
   * Returns a new byte string containing the {@code UTF-8} bytes of {@code s}.
   */
  public static NativeByteString encodeUtf8(String s) {
    if (s == null) throw new IllegalArgumentException("s == null");
    // todo(bnorm) is there a way to void this Heap -> Heap -> Off-Heap copy?
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
    // todo(bnorm) is there a way to void this Heap -> Heap -> Off-Heap copy?
    return of(s.getBytes(charset));
  }

  /**
   * Constructs a new {@code String} by decoding the bytes as {@code UTF-8}.
   */
  public String utf8() {
    String result = utf8;
    // We don't care if we double-allocate in racy code.
    return result != null ? result : (utf8 = new String(data, Util.UTF_8));
  }

  /**
   * Constructs a new {@code String} by decoding the bytes using {@code charset}.
   */
  public String string(Charset charset) {
    if (charset == null) throw new IllegalArgumentException("charset == null");
    return new String(data, charset);
  }

  /**
   * Returns this byte string encoded as <a
   * href="http://www.ietf.org/rfc/rfc2045.txt">Base64</a>. In violation of the
   * RFC, the returned string does not wrap lines at 76 columns.
   */
  public String base64() {
    // todo(bnorm) - native
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the 128-bit MD5 hash of this byte string.
   */
  public NativeByteString md5() {
    // todo(bnorm) - native
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the 160-bit SHA-1 hash of this byte string.
   */
  public NativeByteString sha1() {
    // todo(bnorm) - native
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the 256-bit SHA-256 hash of this byte string.
   */
  public NativeByteString sha256() {
    // todo(bnorm) - native
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the 512-bit SHA-512 hash of this byte string.
   */
  public NativeByteString sha512() {
    // todo(bnorm) - native
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the 160-bit SHA-1 HMAC of this byte string.
   */
  public NativeByteString hmacSha1(NativeByteString key) {
    // todo(bnorm) - native
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the 256-bit SHA-256 HMAC of this byte string.
   */
  public NativeByteString hmacSha256(NativeByteString key) {
    // todo(bnorm) - native
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the 512-bit SHA-512 HMAC of this byte string.
   */
  public NativeByteString hmacSha512(NativeByteString key) {
    // todo(bnorm) - native
    throw new UnsupportedOperationException();
  }

  /**
   * Returns this byte string encoded as <a href="http://www.ietf.org/rfc/rfc4648.txt">URL-safe
   * Base64</a>.
   */
  public String base64Url() {
    // todo(bnorm) - native
    throw new UnsupportedOperationException();
  }

  /**
   * Decodes the Base64-encoded bytes and returns their value as a byte string.
   * Returns null if {@code base64} is not a Base64-encoded sequence of bytes.
   */
  public static @Nullable NativeByteString decodeBase64(String base64) {
    // todo(bnorm) - native
    throw new UnsupportedOperationException();
  }

  /**
   * Returns this byte string encoded in hexadecimal.
   */
  public String hex() {
    char[] result = new char[size * 2];
    int c = 0;
    for (byte b : data) {
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
    return new NativeByteString(result, byteCount);
  }

  /**
   * Returns a byte string equal to this byte string, but with the bytes 'A'
   * through 'Z' replaced with the corresponding byte in 'a' through 'z'.
   * Returns this byte string if it contains no bytes in 'A' through 'Z'.
   */
  public NativeByteString toAsciiLowercase() {
    // Search for an uppercase character. If we don't find one, return this.
    for (int i = 0; i < size; i++) {
      byte c = data[i];
      if (c < 'A' || c > 'Z') continue;

      // If we reach this point, this string is not not lowercase. Create and
      // return a new byte string.
      byte[] lowercase = data.clone();
      lowercase[i++] = (byte) (c - ('A' - 'a'));
      for (; i < lowercase.length; i++) {
        c = lowercase[i];
        if (c < 'A' || c > 'Z') continue;
        lowercase[i] = (byte) (c - ('A' - 'a'));
      }
      return new NativeByteString(lowercase, size);
    }
    return this;
  }

  /**
   * Returns a byte string equal to this byte string, but with the bytes 'a'
   * through 'z' replaced with the corresponding byte in 'A' through 'Z'.
   * Returns this byte string if it contains no bytes in 'a' through 'z'.
   */
  public NativeByteString toAsciiUppercase() {
    // Search for an lowercase character. If we don't find one, return this.
    for (int i = 0; i < size; i++) {
      byte c = data[i];
      if (c < 'a' || c > 'z') continue;

      // If we reach this point, this string is not not uppercase. Create and
      // return a new byte string.
      byte[] lowercase = data.clone();
      lowercase[i++] = (byte) (c - ('a' - 'A'));
      for (; i < lowercase.length; i++) {
        c = lowercase[i];
        if (c < 'a' || c > 'z') continue;
        lowercase[i] = (byte) (c - ('a' - 'A'));
      }
      return new NativeByteString(lowercase, size);
    }
    return this;
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

    byte[] copy = new byte[subLen];
    System.arraycopy(data, beginIndex, copy, 0, subLen);
    return new NativeByteString(copy);
  }

  /**
   * Returns the byte at {@code pos}.
   */
  public byte getByte(int pos) {
    return data[pos];
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
    return data.clone();
  }

  long internalAddress() {
    return address;
  }

  /**
   * Returns a {@code ByteBuffer} view of the bytes in this {@code ByteString}.
   */
  public ByteBuffer asByteBuffer() {
    return ByteBuffer.wrap(data).asReadOnlyBuffer();
  }

  /**
   * Writes the contents of this byte string to {@code out}.
   */
  public void write(OutputStream out) throws IOException {
    if (out == null) throw new IllegalArgumentException("out == null");
    out.write(data);
  }

  /**
   * Writes the contents of this byte string to {@code buffer}.
   */
  void write(Buffer buffer) {
    buffer.write(data, 0, size);
  }

  /**
   * Returns true if the bytes of this in {@code [offset..offset+byteCount)} equal the bytes of
   * {@code other} in {@code [otherOffset..otherOffset+byteCount)}. Returns false if either range is
   * out of bounds.
   */
  public boolean rangeEquals(int offset, NativeByteString other, int otherOffset, int byteCount) {
    return other.rangeEquals(otherOffset, this.data, offset, byteCount);
  }

  /**
   * Returns true if the bytes of this in {@code [offset..offset+byteCount)} equal the bytes of
   * {@code other} in {@code [otherOffset..otherOffset+byteCount)}. Returns false if either range is
   * out of bounds.
   */
  public boolean rangeEquals(int offset, byte[] other, int otherOffset, int byteCount) {
    return offset >= 0 && offset <= size - byteCount
            && otherOffset >= 0 && otherOffset <= other.length - byteCount
            && arrayRangeEquals(data, offset, other, otherOffset, byteCount);
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
    return indexOf(other.internalArray(), fromIndex);
  }

  public final int indexOf(byte[] other) {
    return indexOf(other, 0);
  }

  public int indexOf(byte[] other, int fromIndex) {
    fromIndex = Math.max(fromIndex, 0);
    for (int i = fromIndex, limit = size - other.length; i <= limit; i++) {
      if (arrayRangeEquals(data, i, other, 0, other.length)) {
        return i;
      }
    }
    return -1;
  }

  public final int lastIndexOf(NativeByteString other) {
    return lastIndexOf(other, size());
  }

  public final int lastIndexOf(NativeByteString other, int fromIndex) {
    return lastIndexOf(other.internalArray(), fromIndex);
  }

  public final int lastIndexOf(byte[] other) {
    return lastIndexOf(other, size());
  }

  public int lastIndexOf(byte[] other, int fromIndex) {
    fromIndex = Math.min(fromIndex, size - other.length);
    for (int i = fromIndex; i >= 0; i--) {
      if (arrayRangeEquals(data, i, other, 0, other.length)) {
        return i;
      }
    }
    return -1;
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
      // todo(bnorm) - make sure byteString doesn't free memory
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
