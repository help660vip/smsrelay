package dev.smsrelay;

import android.database.*;
import android.os.*;
import java.security.*;

public final class Probe {
  public static void main(String[] args) throws Exception {
    Looper.prepareMainLooper();
    final Handler h = new Handler(Looper.myLooper());
    final PlatformBridge b = new PlatformBridge();
    final String marker = args.length > 0 ? args[0] : "SMSRELAY_TEST";
    final long[] max = {0};
    try (Cursor c = b.query(new String[] {"_id"}, null, null, "_id DESC LIMIT 1")) {
      if (c.moveToFirst()) max[0] = c.getLong(0);
    }
    b.observe(
        new ContentObserver(h) {
          public void onChange(boolean self) {
            System.out.println("CALLBACK");
            try (Cursor c =
                b.query(
                    new String[] {"_id", "body", "sub_id", "date"},
                    "_id > ?",
                    new String[] {"" + max[0]},
                    "_id ASC")) {
              while (c.moveToNext()) {
                max[0] = Math.max(max[0], c.getLong(0));
                String text = c.getString(1);
                if (text != null && text.contains(marker)) {
                  StringBuilder hash = new StringBuilder();
                  for (byte v : MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8")))
                    hash.append(String.format("%02x", v));
                  System.out.println(
                      "TEST_READ chars="
                          + text.length()
                          + " sha256="
                          + hash
                          + " sub_id="
                          + c.getInt(2));
                } else System.out.println("NEW_ROW body omitted");
              }
            } catch (Exception e) {
              System.out.println("QUERY_ERROR " + e.getClass().getSimpleName());
            }
          }
        });
    System.out.println("REGISTERED " + b.branch() + " baseline=" + max[0]);
    h.postDelayed(
        () -> {
          try {
            b.close();
          } catch (Exception ignored) {
          }
          System.out.println("PROBE_END queries=" + b.queries);
          System.exit(0);
        },
        180000);
    Looper.loop();
  }
}
