package okio;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class Disposer implements Runnable {

  private static final ReferenceQueue<Object> queue = new ReferenceQueue<>();
  private static final Map<Object, Runnable> records = new ConcurrentHashMap<>();
  private static Disposer disposerInstance;

  static {
    disposerInstance = new Disposer();

    AccessController.doPrivileged(
        new PrivilegedAction() {
          public Object run() {
            /* The thread must be a member of a thread group
             * which will not get GCed before VM exit.
             * Make its parent the top-level thread group.
             */
            ThreadGroup tg = Thread.currentThread().getThreadGroup();
            for (ThreadGroup tgn = tg;
                tgn != null;
                tg = tgn, tgn = tg.getParent()) {
            }
            Thread t =
                new Thread(tg, disposerInstance, "Okio Native Address Disposer");
            t.setContextClassLoader(null);
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY);
            t.start();
            return null;
          }
        }
    );
  }

  private Disposer() {
  }

  public static void addRecord(Object target, Runnable rec) {
    if (rec == null) throw new IllegalArgumentException("rec == null");
    PhantomReference<?> ref = new PhantomReference<>(target, queue);
    records.put(ref, rec);
  }

  public void run() {
    while (true) {
      try {
        Reference<?> ref = queue.remove();
        ref.clear();
        Runnable rec = records.remove(ref);
        rec.run();
      } catch (Exception e) {
        System.err.println("Exception while removing reference: " + e);
        e.printStackTrace(System.err);
      }
    }
  }
}
