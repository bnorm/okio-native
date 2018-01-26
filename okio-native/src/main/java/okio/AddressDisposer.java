package okio;

import static okio.NativeUtil.free;

final class AddressDisposer implements Runnable {

  private final long address;

  AddressDisposer(long address) {
    this.address = address;
  }

  @Override
  public void run() {
    free(address);
  }
}
