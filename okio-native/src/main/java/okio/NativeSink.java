package okio;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;

public interface NativeSink extends Closeable, Flushable {
  void write(NativeBuffer source, long byteCount) throws IOException;

  @Override void flush() throws IOException;

  Timeout timeout();

  @Override void close() throws IOException;
}

