package dev.smsrelay;

import android.net.*;
import android.os.Handler;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class Control implements Closeable {
  private final LocalServerSocket server;
  private volatile boolean closed;

  static String name(File home) {
    return "smsrelay." + Rules.hash(home.getAbsolutePath()).substring(0, 16);
  }

  public Control(File home, Handler handler, RelayCore core) throws Exception {
    server = new LocalServerSocket(name(home));
    Thread thread =
        new Thread(
            () -> {
              while (!closed)
                try (LocalSocket s = server.accept()) {
                  s.setSoTimeout(5000);
                  if (s.getPeerCredentials().getUid() != 0) continue;
                  String cmd = boundedLine(s.getInputStream(), 49160);
                  if (cmd == null) continue;
                  FutureTask<String> task =
                      new FutureTask<>(
                          () -> {
                            try {
                              return core.command(cmd);
                            } catch (Exception e) {
                              return "ERROR "
                                  + e.getClass().getSimpleName()
                                  + "; active configuration preserved";
                            }
                          });
                  handler.post(task);
                  String result;
                  try {
                    result = task.get(30, TimeUnit.SECONDS);
                  } catch (Exception e) {
                    result = "ERROR control_timeout";
                  }
                  s.getOutputStream().write((result + "\n").getBytes("UTF-8"));
                } catch (Exception ignored) {
                }
            },
            "smsrelay-control");
    thread.setDaemon(true);
    thread.start();
  }

  static String boundedLine(InputStream in, int max) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    int c;
    while ((c = in.read()) != -1 && c != '\n') {
      if (b.size() >= max) throw new IOException("line too long");
      b.write(c);
    }
    return b.size() == 0 ? null : b.toString("UTF-8");
  }

  public static String request(File home, String command) throws Exception {
    try (LocalSocket s = new LocalSocket()) {
      s.connect(new LocalSocketAddress(name(home)));
      s.setSoTimeout(35000);
      s.getOutputStream().write((command + "\n").getBytes("UTF-8"));
      ByteArrayOutputStream b = new ByteArrayOutputStream();
      byte[] x = new byte[4096];
      int n;
      while ((n = s.getInputStream().read(x)) != -1) {
        b.write(x, 0, n);
        if (b.size() > 1048576) throw new IOException("response too large");
      }
      return b.toString("UTF-8").trim();
    }
  }

  static String redact(String s) {
    return s.replaceAll("https?://[^\\s]+", "[URL]")
        .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[EMAIL]")
        .replaceAll("(?<![0-9])\\+?[0-9]{7,}(?![0-9])", "[NUMBER]");
  }

  static String tail(File home, boolean warnings) throws IOException {
    File f = new File(home, "logs/service.log");
    if (!f.exists()) return "no service log";
    ArrayDeque<String> lines = new ArrayDeque<>();
    try (BufferedReader r =
        new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
      String s;
      while ((s = r.readLine()) != null)
        if (!warnings || s.contains("|WARN|") || s.contains("|ERROR|")) {
          lines.add(s);
          if (lines.size() > 50) lines.removeFirst();
        }
    }
    StringBuilder b = new StringBuilder();
    for (String s : lines) b.append(redact(s)).append('\n');
    return b.toString();
  }

  static String export(File home, String status) throws Exception {
    File dir = new File(home, "exports");
    dir.mkdirs();
    android.system.Os.chmod(dir.getPath(), 0700);
    File output = new File(dir, "diagnostic-" + System.currentTimeMillis() + ".txt");
    Logger.atomic(
        output,
        "smsrelay diagnostic (no config, SMS body, SIM numbers, device serial)\n"
            + redact(status)
            + "\n"
            + tail(home, false));
    return output.getAbsolutePath();
  }

  @Override
  public void close() throws IOException {
    closed = true;
    server.close();
  }
}
