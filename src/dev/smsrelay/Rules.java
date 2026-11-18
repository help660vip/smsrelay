package dev.smsrelay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class Rules {
  public static String hash(String s) {
    try {
      StringBuilder b = new StringBuilder();
      for (byte v : MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)))
        b.append(String.format(Locale.ROOT, "%02x", v));
      return b.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static List<String> split(String s, int budget) {
    if (budget < 4) throw new IllegalArgumentException("budget");
    List<String> out = new ArrayList<>();
    StringBuilder b = new StringBuilder();
    int bytes = 0;
    for (int i = 0; i < s.length(); ) {
      int cp = s.codePointAt(i);
      String c = new String(Character.toChars(cp));
      int n = c.getBytes(StandardCharsets.UTF_8).length;
      if (bytes + n > budget) {
        out.add(b.toString());
        b.setLength(0);
        bytes = 0;
      }
      b.append(c);
      bytes += n;
      i += Character.charCount(cp);
    }
    if (b.length() > 0 || out.isEmpty()) out.add(b.toString());
    return out;
  }

  public static long cutoff(long now, String tz, int days) {
    Calendar c = Calendar.getInstance(TimeZone.getTimeZone(tz));
    c.setTimeInMillis(now);
    c.set(Calendar.HOUR_OF_DAY, 0);
    c.set(Calendar.MINUTE, 0);
    c.set(Calendar.SECOND, 0);
    c.set(Calendar.MILLISECOND, 0);
    c.add(Calendar.DAY_OF_MONTH, 1 - days);
    return c.getTimeInMillis();
  }

  public static boolean timeJump(long wallDelta, long monoDelta) {
    return Math.abs(wallDelta - monoDelta) > 300000;
  }

  public static long delay(int attempts, int[] seconds) {
    return attempts <= seconds.length ? seconds[attempts - 1] * 1000L : -1;
  }
}
