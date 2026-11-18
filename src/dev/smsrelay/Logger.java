package dev.smsrelay;

import android.os.SystemClock;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

/** Only the daemon event thread writes or maintains this file. */
public final class Logger implements Closeable {
  public interface Clock {
    long wall();

    long mono();
  }

  private final Clock clock;
  private final File file, meta;
  private FileOutputStream out;
  private Config config;
  private long lastWall, lastMono, holdUntil;
  private String lastDay = "";
  private boolean failed;
  private String bootId;
  private long previousMono;
  private String previousBoot;
  private final Map<String, long[]> repeated = new LinkedHashMap<>();

  public Logger(File home, Config cfg) throws Exception {
    this(
        home,
        cfg,
        new Clock() {
          public long wall() {
            return System.currentTimeMillis();
          }

          public long mono() {
            return SystemClock.elapsedRealtime();
          }
        });
  }

  public Logger(File home, Config cfg, Clock time) throws Exception {
    clock = time;
    try {
      bootId = Config.read(new File("/proc/sys/kernel/random/boot_id")).trim();
    } catch (Exception e) {
      bootId = "unknown";
    }
    File dir = new File(home, "logs");
    dir.mkdirs();
    file = new File(dir, "service.log");
    meta = new File(dir, "maintenance.json");
    config = cfg;
    if (meta.exists())
      try {
        JSONObject m = new JSONObject(Config.read(meta));
        lastDay = m.optString("day");
        lastWall = m.optLong("wall");
        holdUntil = m.optLong("hold_until");
        previousMono = m.optLong("mono");
        previousBoot = m.optString("boot");
      } catch (Exception ignored) {
      }
    if (lastWall > 0
        && bootId.equals(previousBoot)
        && Rules.timeJump(clock.wall() - lastWall, clock.mono() - previousMono))
      holdUntil = Math.max(holdUntil, Math.max(lastWall, clock.wall()) + 86400000);
    if (lastWall > 0 && clock.wall() < lastWall - 300000)
      holdUntil = Math.max(holdUntil, lastWall + 86400000);
    File interrupted = new File(dir, "service.log.tmp");
    if (interrupted.exists()) interrupted.delete();
    maintain(true);
    lastWall = clock.wall();
    lastMono = clock.mono();
  }

  public void configure(Config c) {
    config = c;
  }

  private String day(long n) {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
    f.setTimeZone(TimeZone.getTimeZone(config.logging.optString("timezone", "UTC")));
    return f.format(new Date(n));
  }

  public void log(String level, String message) {
    if (rank(level) < rank(config.logging.optString("level", "INFO"))) return;
    long now = clock.wall(), mono = clock.mono();
    if (lastMono != 0 && Rules.timeJump(now - lastWall, mono - lastMono))
      holdUntil = Math.max(holdUntil, Math.max(now, lastWall) + 86400000);
    lastWall = now;
    lastMono = mono;
    try {
      if (!day(now).equals(lastDay) || file.length() >= config.logging.getLong("max_bytes"))
        maintain(false);
      SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);
      f.setTimeZone(TimeZone.getTimeZone(config.logging.getString("timezone")));
      String safe = message.replace('\r', ' ').replace('\n', ' ');
      if (safe.length() > 1500) safe = safe.substring(0, 1500);
      byte[] b =
          (now + "|" + f.format(new Date(now)) + "|" + level + "|" + safe + "\n").getBytes("UTF-8");
      if (out == null) out = new FileOutputStream(file, true);
      out.write(b);
      out.flush();
      if (file.length() >= config.logging.getLong("max_bytes")) maintain(false);
      failed = false;
    } catch (Exception ignored) {
      failed = true;
    }
  }

  private int rank(String l) {
    return l.equals("DEBUG") ? 0 : l.equals("INFO") ? 1 : l.equals("WARN") ? 2 : 3;
  }

  public void error(String code, Exception error) {
    // Never log exception messages: HTTP/SMTP exceptions may contain credentials or recipients.
    String key = code + ":" + error.getClass().getSimpleName();
    long now = clock.mono();
    long[] entry = repeated.get(key);
    if (entry != null && now - entry[0] < 60000) {
      entry[1]++;
      return;
    }
    long count = entry == null ? 0 : entry[1];
    log("ERROR", key + (count > 0 ? " suppressed=" + count : ""));
    if (repeated.size() > 128) repeated.clear();
    repeated.put(key, new long[] {now, 0});
  }

  public void maintain(boolean startup) throws Exception {
    if (out != null) {
      out.close();
      out = null;
    }
    long now = clock.wall();
    String today = day(now);
    boolean byAge = now >= holdUntil;
    long cutoff =
        Rules.cutoff(now, config.logging.getString("timezone"), config.logging.getInt("days"));
    long max = config.logging.getLong("max_bytes"), trim = config.logging.getLong("trim_bytes");
    if (file.exists()) {
      ArrayDeque<byte[]> keep = new ArrayDeque<>();
      long bytes = 0;
      try (BufferedReader r =
          new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
        String line;
        while ((line = r.readLine()) != null) {
          if (line.length() > 8192) continue;
          int p = line.indexOf('|');
          long stamp;
          try {
            stamp = Long.parseLong(line.substring(0, p));
          } catch (Exception e) {
            continue;
          }
          if (byAge && stamp < cutoff) continue;
          byte[] b = (line + "\n").getBytes("UTF-8");
          keep.add(b);
          bytes += b.length;
          while (bytes > max && !keep.isEmpty()) bytes -= keep.removeFirst().length;
        }
      }
      if (file.length() >= max)
        while (bytes > trim && !keep.isEmpty()) bytes -= keep.removeFirst().length;
      File tmp = new File(file.getPath() + ".tmp");
      try (FileOutputStream w = new FileOutputStream(tmp)) {
        for (byte[] b : keep) w.write(b);
        w.getFD().sync();
      }
      if (!tmp.renameTo(file)) throw new IOException("log replace failed");
    }
    lastDay = today;
    atomic(
        meta,
        new JSONObject()
            .put("day", today)
            .put("wall", now)
            .put("hold_until", holdUntil)
            .put("mono", clock.mono())
            .put("boot", bootId)
            .toString());
    android.system.Os.chmod(file.getParent(), 0700);
    if (file.exists()) android.system.Os.chmod(file.getPath(), 0600);
  }

  public boolean failed() {
    return failed;
  }

  public static void atomic(File f, String s) throws IOException {
    File tmp = new File(f.getPath() + ".new");
    try (FileOutputStream o = new FileOutputStream(tmp)) {
      o.write(s.getBytes("UTF-8"));
      o.getFD().sync();
    }
    if (!tmp.renameTo(f)) throw new IOException("atomic replace");
    try {
      android.system.Os.chmod(f.getPath(), 0600);
    } catch (Exception e) {
      throw new IOException("chmod", e);
    }
  }

  @Override
  public void close() throws IOException {
    if (out != null) out.close();
  }
}
