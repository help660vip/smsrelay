package dev.smsrelay;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.*;
import java.nio.channels.*;
import java.util.*;

public final class Main {
  static File home;
  static FileChannel lockChannel;
  static FileLock lock;

  public static void main(String[] args) {
    int code = 0;
    try {
      android.system.Os.umask(0077);
      if (android.os.Process.myUid() != 0) throw new SecurityException("root required");
      String mode = args.length > 0 ? args[0] : "ctl";
      home = new File(args.length > 1 ? args[1] : "/data/adb/smsrelay");
      if (mode.equals("send-worker")) {
        SendWorker.run();
        return;
      }
      if (mode.equals("selftest")) {
        try {
          SelfTest.run(home);
        } catch (Throwable e) {
          e.printStackTrace();
          throw e;
        }
        return;
      }
      if (mode.equals("probe")) {
        Probe.main(Arrays.copyOfRange(args, 2, args.length));
        return;
      }
      if (mode.equals("ctl")) {
        String cmd = args.length > 2 ? args[2] : "status";
        if (cmd.equals("start")) {
          start(home);
          System.out.println(await(home));
        } else if (cmd.equals("restart")) {
          try {
            Control.request(home, "stop");
          } catch (Exception ignored) {
          }
          waitStopped(home);
          start(home);
          System.out.println(await(home));
        } else if (cmd.equals("await")) System.out.println(await(home));
        else {
          try {
            System.out.println(Control.request(home, cmd));
          } catch (IOException absent) {
            System.out.println(offline(home, cmd));
          }
        }
        return;
      }
      if (mode.equals("menu")) {
        Menu.run(home);
        return;
      }
      home.mkdirs();
      android.system.Os.chmod(home.getPath(), 0700);
      File run = new File(home, "run");
      run.mkdirs();
      android.system.Os.chmod(run.getPath(), 0700);
      String lockname = mode.equals("supervise") ? "supervisor.lock" : "daemon.lock";
      lockChannel = new RandomAccessFile(new File(run, lockname), "rw").getChannel();
      lock = lockChannel.tryLock();
      if (lock == null) {
        System.out.println("already_running");
        return;
      }
      if (mode.equals("supervise")) {
        supervise(home);
        return;
      }
      if (!mode.equals("daemon")) throw new IllegalArgumentException("mode");
      Looper.prepareMainLooper();
      Handler h = new Handler(Looper.myLooper());
      RelayCore core = new RelayCore(home, h);
      Control ctl = new Control(home, h, core);
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      if (core.activeChild() != null) core.activeChild().destroy();
                    } catch (Exception ignored) {
                    }
                  },
                  "smsrelay-exit"));
      Logger.atomic(new File(run, "ready.json"), core.status().toString());
      Looper.loop();
      ctl.close();
      core.close();
    } catch (Throwable e) {
      code = 78;
      try {
        if (home != null) {
          File run = new File(home, "run");
          run.mkdirs();
          Logger.atomic(
              new File(run, "last-error.txt"),
              "startup_failed:"
                  + e.getClass().getSimpleName()
                  + "; unlock then use restart; no polling fallback");
        }
      } catch (Exception ignored) {
      }
      System.out.println("ERROR " + e.getClass().getSimpleName());
    } finally {
      try {
        if (lock != null) lock.release();
        if (lockChannel != null) lockChannel.close();
      } catch (Exception ignored) {
      }
    }
    System.exit(code);
  }

  static Process process(String mode, File home) throws IOException {
    ProcessBuilder b =
        new ProcessBuilder(
            "/system/bin/app_process",
            "/system/bin",
            "--nice-name=smsrelay-" + mode,
            "dev.smsrelay.Main",
            mode,
            home.getAbsolutePath());
    b.environment().put("CLASSPATH", System.getenv("CLASSPATH"));
    return b.start();
  }

  static void discard(InputStream in) {
    Thread t =
        new Thread(
            () -> {
              try {
                byte[] b = new byte[2048];
                while (in.read(b) != -1) {}
              } catch (IOException ignored) {
              } finally {
                try {
                  in.close();
                } catch (IOException ignored) {
                }
              }
            },
            "smsrelay-pipe");
    t.setDaemon(true);
    t.start();
  }

  static void supervise(File home) throws Exception {
    int failures = 0;
    long window = SystemClock.elapsedRealtime();
    while (true) {
      Process p = process("daemon", home);
      discard(p.getInputStream());
      discard(p.getErrorStream());
      int result = p.waitFor();
      if (result == 0 || result == 78) break;
      long now = SystemClock.elapsedRealtime();
      if (now - window > 600000) {
        failures = 0;
        window = now;
      }
      if (++failures >= 3) {
        Logger.atomic(
            new File(home, "run/last-error.txt"),
            "crash_limit_reached:3/10min; manual restart required");
        break;
      }
      Thread.sleep(failures * 2000L); // Only after an actual child exit, never a heartbeat.
    }
  }

  static String module() {
    String jar = System.getenv("CLASSPATH");
    return new File(jar).getParent();
  }

  static void start(File home) throws Exception {
    new File(home, "run").mkdirs();
    String line =
        "CLASSPATH='"
            + System.getenv("CLASSPATH")
            + "' nohup /system/bin/app_process /system/bin --nice-name=smsrelay-supervise"
            + " dev.smsrelay.Main supervise '"
            + home.getAbsolutePath()
            + "' >/dev/null 2>&1 </dev/null &";
    Process p = new ProcessBuilder("/system/bin/sh", "-c", line).start();
    p.waitFor();
  }

  static String await(File home) throws Exception {
    long end = SystemClock.elapsedRealtime() + 20000;
    while (SystemClock.elapsedRealtime() < end) {
      try {
        String s = Control.request(home, "status");
        if (new org.json.JSONObject(s).getBoolean("observer_registered")) return s;
      } catch (Exception ignored) {
      }
      Thread.sleep(400);
    }
    return offline(home, "status") + "; start_not_confirmed";
  }

  static void waitStopped(File home) throws Exception {
    long end = SystemClock.elapsedRealtime() + 6000;
    while (SystemClock.elapsedRealtime() < end) {
      try {
        Control.request(home, "status");
        Thread.sleep(150);
      } catch (IOException stopped) {
        return;
      }
    }
    throw new IOException("stop not confirmed");
  }

  static String offline(File home, String command) throws Exception {
    if (command.equals("logs")) return Control.tail(home, false);
    if (command.equals("warnings")) return Control.tail(home, true);
    if (command.equals("export")) return Control.export(home, "service_not_running");
    if (command.equals("check") || command.equals("reload")) {
      Config.load(home);
      return "config_valid; service_not_running";
    }
    if (command.equals("maintain")) {
      File f = new File(home, "run/daemon.lock");
      try (RandomAccessFile r = new RandomAccessFile(f, "rw");
          FileChannel c = r.getChannel();
          FileLock l = c.tryLock()) {
        if (l == null) throw new IOException("daemon still owns log");
        try (Logger log = new Logger(home, RelayCore.loadEffective(home))) {
          log.maintain(false);
        }
        return "logs_maintained; queue_untouched";
      }
    }
    File err = new File(home, "run/last-error.txt");
    return "service_not_running" + (err.exists() ? "; " + Config.read(err) : "");
  }
}
