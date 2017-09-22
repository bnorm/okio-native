package okio;

import java.util.AbstractList;
import java.util.RandomAccess;

/** An indexed set of values that may be read with {@link BufferedSource#select}. */
public final class NativeOptions extends AbstractList<NativeByteString> implements RandomAccess {
  final NativeByteString[] byteStrings;

  private NativeOptions(NativeByteString[] byteStrings) {
    this.byteStrings = byteStrings;
  }

  public static NativeOptions of(NativeByteString... byteStrings) {
    return new NativeOptions(byteStrings.clone()); // Defensive copy.
  }

  @Override public NativeByteString get(int i) {
    return byteStrings[i];
  }

  @Override public int size() {
    return byteStrings.length;
  }
}
