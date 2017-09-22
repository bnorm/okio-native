package okio;

import java.io.Closeable;
import java.io.IOException;

public interface NativeSource extends Closeable {
  long read(NativeBuffer sink, long byteCount) throws IOException;

  Timeout timeout();

  @Override void close() throws IOException;
}