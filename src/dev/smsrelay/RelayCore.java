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
  final EventSources events;
  final WebPanel panel;
  private final java.util.Map<String,String> activeNotifications =
      new java.util.LinkedHashMap<String,String>(128,0.75f,true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String,String> eldest) {
          return size() > 512;
        }
      };
  private long notificationBaseline;
  private final java.util.Map<String,Long> lastEvent = new java.util.HashMap<>();
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
  private long nextWecom, nextNotificationSmtp;
  private final Runnable pump = () -> dispatch();

  public RelayCore(File dir, Handler handler) throws Exception {
    home = dir;
    h = handler;
    cfg = loadEffective(dir);
    log = new Logger(home, cfg);
    log.log("INFO", "starting smsrelay 0.2.0");
    db = new StateDb(home);
    db.cleanup(cfg);
    device = new Device(h);
    device.refreshSims();
    events = new EventSources(device, new EventSources.Sink() {
      @Override public void ringing(int sub, String number) { onRing(sub,number); }
      @Override public void posted(android.service.notification.StatusBarNotification sbn) {
        onNotification(sbn);
      }
      @Override public void removed(android.service.notification.StatusBarNotification sbn) {
        activeNotifications.remove(sbn.getKey());
      }
    });
    device.watchSims(() -> {
      log.log("INFO", "subscription_changed " + device.simStatus);
      if (Routing.enabled(cfg,"call")) events.refreshCalls();
    });
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
    panel = new WebPanel(this);
    configureSources();
    h.post(pump);
  }

  void configureSources() {
    boolean notify = Routing.enabled(cfg,"notification");
    if (!notify || !events.notificationState.equals("ready")) {
      notificationBaseline = System.currentTimeMillis();
      activeNotifications.clear();
    }
    events.configure(Routing.enabled(cfg,"call"),notify);
  }

  private void onRing(int sub, String number) {
    if (!Routing.enabled(cfg,"call")) return;
    long now = System.currentTimeMillis();
    String name = number != null && !number.isEmpty() ? number : "未知号码";
    lastEvent.put("call",now);
    capture(new Routing.Event("call","call:"+java.util.UUID.randomUUID(),name,
        "来电提醒 · "+name,"","","",now,device.sim(sub).slot,false),null);
  }

  private void onNotification(android.service.notification.StatusBarNotification sbn) {
    if (!Routing.enabled(cfg,"notification") || sbn == null || sbn.getPostTime() < notificationBaseline) return;
    if (sbn.getUser() == null || !sbn.getUser().equals(android.os.Process.myUserHandle())) return;
    String pkg = sbn.getPackageName();
    try {
      if (!Routing.selectedApp(cfg,pkg)) return;
      android.app.Notification n = sbn.getNotification();
      if (n == null) return;
      lastEvent.put("notification",System.currentTimeMillis());
      android.os.Bundle extras = n.extras;
      String title = extra(extras, android.app.Notification.EXTRA_TITLE);
      String body = extra(extras, android.app.Notification.EXTRA_BIG_TEXT);
      if (body.isEmpty()) body = extra(extras, android.app.Notification.EXTRA_TEXT);
      if (body.isEmpty() && extras != null) {
        CharSequence[] lines = extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES);
        if (lines != null) body = android.text.TextUtils.join("\n",lines);
      }
      String label = pkg;
      try {
        android.content.pm.PackageManager pm = device.context.getPackageManager();
        label = pm.getApplicationLabel(pm.getApplicationInfo(pkg,0)).toString();
      } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
      if (title == null) title = "";
      boolean noisy = (n.flags & (android.app.Notification.FLAG_ONGOING_EVENT |
          android.app.Notification.FLAG_GROUP_SUMMARY | android.app.Notification.FLAG_FOREGROUND_SERVICE)) != 0;
      String signature = Rules.hash(title+"\u0000"+body);
      boolean repeat = signature.equals(activeNotifications.put(sbn.getKey(),signature));
      String category = android.os.Build.VERSION.SDK_INT >= 26 ? n.getChannelId() : n.category;
      if (category == null) category = "";
      String key = "notice:" + Rules.hash(sbn.getKey()+"|"+sbn.getPostTime()+"|"+signature);
      Routing.Event event = new Routing.Event("notification",key,label,title,body,pkg,
          category,sbn.getPostTime(),0,noisy);
      // 重复刷新写入原时间线，不再复制正文或建立新任务。
      if (repeat) { db.noteDuplicate(sbn.getKey()); return; }
      long id = capture(event,null);
      if (id > 0) db.rememberNotification(sbn.getKey(),id);
    } catch (Exception e) { log.error("notification_read",e); }
  }

  private static String extra(android.os.Bundle extras, String key) {
    CharSequence value = extras == null ? null : extras.getCharSequence(key);
    return value == null ? "" : value.toString();
  }

  long capture(Routing.Event event, String reason) {
    try {
      long id;
      db.begin();
      try { id = db.addEvent(event,cfg,false,reason); db.commit(); }
      finally { db.end(); }
      dispatch();
      return id;
    } catch (Exception e) {
      log.error("event_capture",e);
      return 0;
    }
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
      long now = System.currentTimeMillis();
      db.expire(now);
      long expiry = db.nextExpiry();
      if (expiry>now) h.postDelayed(pump,expiry-now);
      String allowed = db.allowed(cfg);
      long next = db.next(allowed);
      watch(next >= 0 || busy);
      if (next < 0 || busy) return;
      StateDb.Task candidate = db.due(now,allowed);
      if (!online()) {
        if (candidate != null) db.history.once(candidate,"waiting_network","network_unavailable");
        return;
      }
      String ready = "("+allowed+")";
      if (now < nextWecom) {
        StateDb.Task limited = db.due(now,ready+" AND t.channel='wecom'");
        if (limited != null) {
          db.history.once(limited,"throttled","wecom_interval");
          h.postDelayed(pump,nextWecom-now);
        }
        ready += " AND t.channel!='wecom'";
      }
      if (now < nextNotificationSmtp) {
        String group = "(t.channel='smtp' AND "+StateDb.kindSql()+"='notification')";
        StateDb.Task limited = db.due(now,ready+" AND "+group);
        if (limited != null) {
          db.history.once(limited,"throttled","notification_interval");
          h.postDelayed(pump,nextNotificationSmtp-now);
        }
        ready += " AND NOT "+group;
      }
      candidate = db.due(now,ready);
      if (candidate == null) {
        if (next > now) h.postDelayed(pump,next-now);
        return;
      }
      final StateDb.Task task = candidate;
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
      // 将网络请求放在子进程里，达到总超时时可以终止它。
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
    // 超时与子进程结果都回到同一个 Handler，先到的回调负责结算。
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
      else if (t.kind.equals("notification")) nextNotificationSmtp = System.currentTimeMillis() + 1500;
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
        .put("version", "0.2.0")
        .put("enabled", Routing.enabled(cfg,"sms"))
        .put("health", new JSONObject()
            .put("sms", health("sms",source.registered() ? "ready" : "disconnected"))
            .put("call",health("call",events.callState)
                .put("ready_subscriptions",events.readySubscriptions())
                .put("watched_subscriptions",events.watchedSubscriptions()))
            .put("notification",health("notification",events.notificationState)))
        .put("observer_registered", source.registered())
        .put("compatibility", source.branch())
        .put("queue", db.status().put("HELD",db.held(cfg)))
        .put("queries", source.queries())
        .put("callbacks", source.callbacks)
        .put("scans", source.scans)
        .put("sends", sends)
        .put("online",online())
        .put("network_callback", watching)
        .put("active_work", busy)
        .put("wakelock", wake != null && wake.isHeld())
        .put("sim_status", device.simStatus)
        .put("log_write_failed", log.failed());
  }

  private JSONObject health(String kind, String state) throws Exception {
    long latest = lastEvent.containsKey(kind) ? lastEvent.get(kind) : db.lastCaptured(kind);
    return new JSONObject().put("enabled",Routing.enabled(cfg,kind)).put("listener",state)
        .put("last_event",latest);
  }

  void apply(Config next) throws Exception {
    source.scan();
    Logger.atomic(new File(home,"config.active.json"),next.raw.toString());
    cfg=next;
    source.configure(next);
    log.configure(next);
    configureSources();
    log.log("INFO","configuration_reloaded");
    dispatch();
  }

  public String command(String command) throws Exception {
    if (command.startsWith("web ")) return panel.request(command.substring(4));
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
          apply(Config.load(home));
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
          raw.put("enabled", false);
          if (!both) raw.getJSONObject(wecom ? "smtp" : "wecom").put("enabled", false);
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
            db.addChannelTest(sms, new Config(raw.toString()));
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
    events.close();
    source.close();
    device.close();
    log.log("INFO", "stopped");
    log.close();
    db.close();
  }
}
