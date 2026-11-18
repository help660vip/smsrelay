package dev.smsrelay;

import android.net.*;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.*;
import java.util.concurrent.*;
import org.json.*;

public final class RelayCore implements AutoCloseable {
  final File home;
  final Handler h;
  Config cfg;
  final StateDb db;
  final Logger log;
  final Device device;
  final SmsSource source;
  private final ExecutorService work = Executors.newSingleThreadExecutor();
  private final ConnectivityManager.NetworkCallback callback;
  private boolean injectedOffline;
  private final String instance = java.util.UUID.randomUUID().toString();

  private boolean online() {
    return !injectedOffline && device.online();
  }

  private boolean watching, busy, closed;
  private Process child;
  private PowerManager.WakeLock wake;
  public long sends;
  private long nextWecom;
  private final Runnable pump = () -> dispatch();

  public RelayCore(File dir, Handler handler) throws Exception {
    home = dir;
    h = handler;
    cfg = loadEffective(dir);
    log = new Logger(home, cfg);
    log.log("INFO", "starting smsrelay 0.1.0");
    db = new StateDb(home);
    db.cleanup(cfg);
    device = new Device(h);
    device.refreshSims();
    device.watchSims(() -> log.log("INFO", "subscription_changed " + device.simStatus));
    log.log("INFO", "subscriptions " + device.simStatus);
    source = new SmsSource(h, db, device, log, cfg, () -> dispatch());
    callback =
        new ConnectivityManager.NetworkCallback() {
          @Override
          public void onAvailable(Network n) {
            h.post(pump);
          }

          @Override
          public void onLost(Network n) {
            h.post(pump);
          }

          @Override
          public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
            h.post(pump);
          }
        };
    h.post(pump);
  }

  static Config loadEffective(File home) throws Exception {
    try {
      Config c = Config.load(home);
      Logger.atomic(new File(home, "config.active.json"), c.raw.toString());
      return c;
    } catch (Exception bad) {
      File old = new File(home, "config.active.json");
      if (!old.exists()) throw bad;
      return new Config(Config.read(old));
    }
  }

  void watch(boolean needed) {
    if (needed && !watching) {
      device.network.registerNetworkCallback(
          new NetworkRequest.Builder()
              .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
              .build(),
          callback);
      watching = true;
    } else if (!needed && watching) {
      device.network.unregisterNetworkCallback(callback);
      watching = false;
    }
  }

  void dispatch() {
    if (closed) return;
    h.removeCallbacks(pump);
    StateDb.Task startedTask = null;
    try {
      boolean pending = db.pending() > 0;
      watch(pending && cfg.enabled);
      if (!pending || !cfg.enabled || busy) return;
      if (!online()) return;
      long now = System.currentTimeMillis();
      StateDb.Task task = db.due(now);
      if (task == null) {
        long next = db.next();
        if (next >= 0) h.postDelayed(pump, Math.max(1, next - now));
        return;
      }
      if (task.channel.equals("wecom") && now < nextWecom) {
        h.postDelayed(pump, nextWecom - now);
        return;
      }
      Config snapshot = new Config(task.settings);
      if (task.attempt >= snapshot.retry.length + 1) {
        db.result(task, false, true, "interrupted_attempt_limit", cfg, now);
        log.log("WARN", "attempt_limit task=" + task.id);
        h.post(pump);
        return;
      }
      busy = true;
      db.sending(task);
      startedTask = task;
      sends++;
      wake = device.wake(snapshot.total * 1000L + 5000);
      final Process sender = Main.process("send-worker", home);
      child = sender;
      final long start = SystemClock.elapsedRealtime();
      final boolean[] done = {false};
      final Runnable deadline =
          () -> {
            sender.destroy();
            finish(task, start, done, false, false, "whole_call_timeout");
          };
      h.postDelayed(deadline, snapshot.total * 1000L);
      work.execute(
          () -> {
            boolean ok = false, permanent = false;
            String code = "worker_error";
            try {
              Process p = sender;
              JSONObject request =
                  new JSONObject()
                      .put("channel", task.channel)
                      .put("settings", task.settings)
                      .put("payload", task.payload)
                      .put(
                          "message_id",
                          Rules.hash(task.message + ":" + task.id + ":" + task.payload));
              try (OutputStream out = p.getOutputStream()) {
                out.write((request.toString() + "\n").getBytes("UTF-8"));
              }
              Main.discard(p.getErrorStream());
              String line;
              try (BufferedReader reader =
                  new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                line = reader.readLine();
              }
              int exit = p.waitFor();
              if (exit == 0 && line != null && line.length() < 1024) {
                JSONObject response = new JSONObject(line);
                ok = response.getBoolean("success");
                permanent = response.getBoolean("permanent");
                code = response.getString("code");
              }
            } catch (Exception e) {
              code = e.getClass().getSimpleName();
            }
            final boolean success = ok, stop = permanent;
            final String result = code;
            h.post(
                () -> {
                  h.removeCallbacks(deadline);
                  finish(task, start, done, success, stop, result);
                });
          });
    } catch (Exception e) {
      if (wake != null && wake.isHeld()) wake.release();
      busy = false;
      log.error("dispatch", e);
      if (startedTask != null) {
        try {
          if (child != null) child.destroy();
          child = null;
          db.result(
              startedTask, false, false, "local_dispatch_failure", cfg, System.currentTimeMillis());
          h.post(pump);
        } catch (Exception persistence) {
          log.error("dispatch_persist", persistence);
          System.exit(70);
        }
      }
    }
  }

  private void finish(
      StateDb.Task t, long start, boolean[] done, boolean success, boolean permanent, String code) {
    if (done[0] || closed) return;
    done[0] = true;
    child = null;
    try {
      if (!success && !online()) {
        db.offline(t);
        log.log("INFO", "waiting_network task=" + t.id);
      } else {
        db.result(t, success, permanent, code, cfg, System.currentTimeMillis());
        log.log(
            success ? "INFO" : "WARN",
            "delivery task="
                + t.id
                + " channel="
                + t.channel
                + " part="
                + t.part
                + "/"
                + t.parts
                + " attempt="
                + t.attempt
                + " result="
                + code
                + " elapsed_ms="
                + (SystemClock.elapsedRealtime() - start));
      }
      if (t.channel.equals("wecom")) nextWecom = System.currentTimeMillis() + 3100;
      db.cleanup(cfg);
    } catch (Exception e) {
      log.error("queue_result", e);
      System.exit(70); // Persisted SENDING is recovered by bounded supervisor restart.
    } finally {
      if (wake != null && wake.isHeld()) wake.release();
      wake = null;
      busy = false;
      h.post(pump);
    }
  }

  public synchronized Process activeChild() {
    return child;
  }

  public JSONObject status() throws Exception {
    return new JSONObject()
        .put("pid", android.os.Process.myPid())
        .put("instance", instance)
        .put("running", true)
        .put("version", "0.1.0")
        .put("enabled", cfg.enabled)
        .put("observer_registered", source.registered())
        .put("compatibility", source.branch())
        .put("queue", db.status())
        .put("queries", source.queries())
        .put("callbacks", source.callbacks)
        .put("scans", source.scans)
        .put("sends", sends)
        .put("network_callback", watching)
        .put("active_work", busy)
        .put("wakelock", wake != null && wake.isHeld())
        .put("sim_status", device.simStatus)
        .put("log_write_failed", log.failed());
  }

  public String command(String command) throws Exception {
    if (command.equals("test-offline") || command.equals("test-online")) {
      if (!home.getName().startsWith("smsrelay-test-"))
        throw new SecurityException("isolated service only");
      injectedOffline = command.equals("test-offline");
      dispatch();
      return "injected_network_unavailable=" + injectedOffline;
    }
    switch (command) {
      case "status":
      case "queue":
        return status().toString();
      case "check":
        Config.load(home);
        return "config_valid";
      case "reload":
        {
          Config next = Config.load(home);
          source.scan();
          Logger.atomic(new File(home, "config.active.json"), next.raw.toString());
          cfg = next;
          source.configure(next);
          log.configure(next);
          log.log("INFO", "configuration_reloaded");
          dispatch();
          return "config_applied";
        }
      case "retry":
        int n = db.retry(cfg);
        log.log("INFO", "manual_retry pending=" + n);
        dispatch();
        return "pending=" + n;
      case "test-wecom":
      case "test-smtp":
      case "test-both":
        {
          JSONObject raw = new JSONObject(cfg.raw.toString());
          boolean wecom = command.equals("test-wecom"), both = command.equals("test-both");
          if (both && !home.getName().startsWith("smsrelay-test-"))
            throw new SecurityException("isolated service only");
          if (!both && !raw.getJSONObject(wecom ? "wecom" : "smtp").getBoolean("enabled"))
            throw new IllegalStateException("channel disabled");
          raw.put("enabled", true);
          if (!both) raw.getJSONObject(wecom ? "smtp" : "wecom").put("enabled", false);
          raw.put(
              "filters",
              new JSONObject()
                  .put("sim_slots", new JSONArray())
                  .put("numbers", new JSONArray())
                  .put("keywords", new JSONArray()));
          StateDb.Sms sms = new StateDb.Sms();
          sms.providerId = -1;
          sms.date = System.currentTimeMillis();
          sms.sub = -1;
          sms.slot = 0;
          sms.address = "SMSRELAY_TEST";
          sms.body =
              "SMSRELAY_TEST "
                  + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.ROOT)
                      .format(new java.util.Date())
                  + " 渠道连通性测试";
          db.begin();
          try {
            db.add(sms, new Config(raw.toString()), false);
            db.commit();
          } finally {
            db.end();
          }
          log.log("INFO", "test_enqueued channel=" + (wecom ? "wecom" : "smtp"));
          dispatch();
          return "test_queued";
        }
      case "maintain":
        log.maintain(false);
        db.cleanup(cfg);
        return "maintenance_complete; pending_sms_preserved";
      case "logs":
        return Control.tail(home, false);
      case "warnings":
        return Control.tail(home, true);
      case "export":
        return Control.export(home, status().toString());
      case "stop":
        h.postDelayed(
            () -> {
              try {
                close();
              } catch (Exception ignored) {
              }
              System.exit(0);
            },
            150);
        return "stopping";
      default:
        throw new IllegalArgumentException("unknown command");
    }
  }

  @Override
  public synchronized void close() throws Exception {
    closed = true;
    h.removeCallbacks(pump);
    if (child != null) child.destroy();
    if (wake != null && wake.isHeld()) wake.release();
    work.shutdownNow();
    watch(false);
    source.close();
    device.close();
    log.log("INFO", "stopped");
    log.close();
    db.close();
  }
}
