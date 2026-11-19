package dev.smsrelay;

import android.database.*;
import android.os.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** 使用隔离数据验证采集和队列逻辑，无需读取真实短信或连接网络。 */
public final class SelfTest {
  static int checks;

  static void check(boolean condition, String name) {
    checks++;
    if (!condition) throw new AssertionError(name);
    System.out.println("PASS " + name);
  }

  static long count(StateDb db, String sql) {
    return android.database.DatabaseUtils.longForQuery(db.db, sql, null);
  }

  static StateDb.Sms sms(long id, String body) {
    StateDb.Sms s = new StateDb.Sms();
    s.providerId = id;
    s.date = System.currentTimeMillis();
    s.sub = 1;
    s.slot = 1;
    s.address = "SMSRELAY_TEST";
    s.body = body;
    return s;
  }

  static final class Fake implements SmsSource.Input {
    final List<StateDb.Sms> rows = new ArrayList<>();
    long n;
    ContentObserver observer;

    public Cursor query(String[] cols, String where, String[] args, String sort) {
      n++;
      List<StateDb.Sms> chosen = new ArrayList<>();
      for (StateDb.Sms s : rows) {
        boolean include = true;
        if (where != null) {
          if (where.startsWith("date")) include = s.date >= Long.parseLong(args[0]);
          else
            include = s.providerId > Long.parseLong(args[0]) || s.date >= Long.parseLong(args[1]);
        }
        if (include) chosen.add(s);
      }
      if (sort != null && sort.startsWith("date DESC"))
        Collections.sort(chosen, (a, b) -> Long.compare(b.date, a.date));
      else
        Collections.sort(
            chosen,
            (a, b) ->
                sort != null && sort.contains("DESC")
                    ? Long.compare(b.providerId, a.providerId)
                    : Long.compare(a.providerId, b.providerId));
      if (sort != null
          && sort.contains("LIMIT 1")
          && !sort.contains("LIMIT 512")
          && chosen.size() > 1) chosen = new ArrayList<>(chosen.subList(0, 1));
      MatrixCursor c = new MatrixCursor(cols);
      for (StateDb.Sms s : chosen) {
        Object[] values = new Object[cols.length];
        for (int i = 0; i < cols.length; i++)
          switch (cols[i]) {
            case "_id":
              values[i] = s.providerId;
              break;
            case "date":
              values[i] = s.date;
              break;
            case "sub_id":
              values[i] = s.sub;
              break;
            case "address":
              values[i] = s.address;
              break;
            case "body":
              values[i] = s.body;
              break;
          }
        c.addRow(values);
      }
      return c;
    }

    public void observe(ContentObserver o) {
      observer = o;
    }

    public void death(Runnable r) {}

    public void close() {}

    public String branch() {
      return "fake";
    }

    public long queries() {
      return n;
    }
  }

  static final class FakeClock implements Logger.Clock {
    long wall = 1795017600000L, mono = 1;

    public long wall() {
      return wall;
    }

    public long mono() {
      return mono;
    }

    void step(long w, long m) {
      wall += w;
      mono += m;
    }
  }

  public static void run(File home) throws Exception {
    if (!home.getAbsolutePath().contains("smsrelay-test-"))
      throw new SecurityException("isolated test directory required");
    home.mkdirs();
    Config cfg = Config.load(home);
    Looper.prepareMainLooper();
    Handler h = new Handler(Looper.myLooper());
    String text = "中文\n引号\"\\body= & 😀";
    List<String> parts = Rules.split(String.join("", Collections.nCopies(500, text)), 1900);
    StringBuilder joined = new StringBuilder();
    for (String p : parts) {
      check(p.getBytes("UTF-8").length <= 1900, "UTF8 segment bounded");
      joined.append(p);
    }
    check(
        joined.toString().equals(String.join("", Collections.nCopies(500, text))),
        "Unicode complete and ordered");
    check(
        Rules.delay(1, new int[] {30, 120, 600}) == 30000
            && Rules.delay(4, new int[] {30, 120, 600}) == -1,
        "finite retry schedule");
    try (Logger log = new Logger(home, cfg);
        StateDb db = new StateDb(home)) {
      Fake fake = new Fake();
      SmsSource source = new SmsSource(h, db, null, log, cfg, () -> {}, fake);
      check(
          db.get("initialized", "false").equals("true") && db.value("max_id") == 0,
          "empty inbox initialization independent of zero");
      fake.rows.add(sms(100, text));
      source.scan();
      long tasks = count(db, "SELECT COUNT(*) FROM tasks");
      check(tasks >= 2, "first SMS after empty inbox enqueued to both channels");
      source.scan();
      source.scan();
      check(
          count(db, "SELECT COUNT(*) FROM tasks") == tasks, "duplicate notifications deduplicated");
      StateDb.Sms low = sms(2, "SMSRELAY_TEST low ID");
      fake.rows.add(low);
      source.scan();
      check(count(db, "SELECT COUNT(*) FROM tasks") > tasks, "lower ID caught by overlap");
      StateDb.Task t = db.due(System.currentTimeMillis());
      db.sending(t);
      db.result(t, true, false, "mock accepted", cfg, System.currentTimeMillis());
      long succeeded = count(db, "SELECT COUNT(*) FROM tasks WHERE state='SUCCEEDED'");
      StateDb.Task failed = db.due(System.currentTimeMillis());
      db.sending(failed);
      db.result(failed, false, true, "mock auth", cfg, System.currentTimeMillis());
      db.retry();
      check(
          count(db, "SELECT COUNT(*) FROM tasks WHERE state='SUCCEEDED'") == succeeded,
          "manual retry preserves successful targets");
      StateDb.Task sending = db.due(System.currentTimeMillis());
      db.sending(sending);
      source.close();
    }
    try (StateDb recovered = new StateDb(home)) {
      check(
          count(recovered, "SELECT COUNT(*) FROM tasks WHERE state='SENDING'") == 0,
          "interrupted SENDING recovered");
      check(recovered.pending() > 0, "restart preserves pending queue");
      recovered.cleanup(cfg);
      check(
          count(recovered, "SELECT COUNT(*) FROM messages WHERE body IS NOT NULL") > 0,
          "pending message body preserved");
    }
    File history = new File(home, "history");
    history.mkdirs();
    try (Logger log = new Logger(history, cfg);
        StateDb db = new StateDb(history)) {
      Fake fake = new Fake();
      fake.rows.add(sms(1000, "history"));
      SmsSource source = new SmsSource(h, db, null, log, cfg, () -> {}, fake);
      source.scan();
      check(db.pending() == 0, "existing history skipped");
      fake.rows.add(sms(1001, "new"));
      fake.rows.add(sms(1002, "batch"));
      source.scan();
      check(db.pending() >= 4, "batch arrivals queued");
      source.close();
    }
    try {
      new Config("{invalid");
      throw new AssertionError("invalid config accepted");
    } catch (JSONException expected) {
      check(true, "invalid JSON rejected without applying");
    }
    File logs = new File(home, "log-test");
    logs.mkdirs();
    JSONObject raw = new JSONObject(cfg.raw.toString());
    raw.getJSONObject("logging").put("max_bytes", 65536).put("trim_bytes", 32768);
    Config small = new Config(raw.toString());
    FakeClock clock = new FakeClock();
    try (Logger log = new Logger(logs, small, clock)) {
      log.log("INFO", "initial marker");
      clock.step(30L * 86400000, 1000);
      log.log("INFO", "clock jumped");
      check(
          Config.read(new File(logs, "logs/service.log")).contains("initial marker"),
          "clock jump defers age deletion");
      for (int i = 0; i < 1500; i++)
        log.log("INFO", "size " + i + String.join("", Collections.nCopies(160, "x")));
      check(
          new File(logs, "logs/service.log").length() < 65536,
          "log capacity bounded with complete records");
      log.maintain(false);
    }
    File tmp = new File(logs, "logs/service.log.tmp");
    try (FileOutputStream w = new FileOutputStream(tmp)) {
      w.write("incomplete".getBytes("UTF-8"));
    }
    try (Logger log = new Logger(logs, small, clock)) {
      check(
          !tmp.exists() && new File(logs, "logs/service.log").length() > 0,
          "interrupted trim preserves old log");
    }
    long now = clock.wall;
    check(
        Rules.cutoff(now, "Asia/Shanghai", 7) < now - 5L * 86400000
            && Rules.cutoff(now, "Asia/Shanghai", 7) > now - 7L * 86400000,
        "seven natural day boundary");
    check(
        Menu.isPress("EV_KEY KEY_VOLUMEDOWN DOWN")
            && !Menu.isPress("EV_KEY KEY_VOLUMEDOWN UP")
            && !Menu.isPress("EV_KEY KEY_VOLUMEUP UP"),
        "menu ignores releases");
    File calendarHome = new File(home, "calendar");
    new File(calendarHome, "logs").mkdirs();
    FakeClock calendarClock = new FakeClock();
    long edge = Rules.cutoff(calendarClock.wall, "Asia/Shanghai", 7);
    Logger.atomic(
        new File(calendarHome, "logs/service.log"),
        (edge - 1)
            + "|date|INFO|expired\n"
            + edge
            + "|date|INFO|boundary\n"
            + calendarClock.wall
            + "|date|INFO|today\n");
    try (Logger log = new Logger(calendarHome, cfg, calendarClock)) {
      String records = Config.read(new File(calendarHome, "logs/service.log"));
      check(
          !records.contains("expired") && records.contains("boundary"),
          "age pruning uses record timestamps");
      calendarClock.step(86400000, 86400000);
      log.log("INFO", "next day");
      check(
          !Config.read(new File(calendarHome, "logs/service.log")).contains("boundary"),
          "first real log on new day advances boundary");
    }
    try (Logger log = new Logger(calendarHome, cfg, calendarClock)) {
      check(
          Config.read(new File(calendarHome, "logs/service.log")).contains("today"),
          "restart does not restart retention age");
    }
    File forwardHome = new File(home, "clock-restart");
    forwardHome.mkdirs();
    FakeClock forward = new FakeClock();
    try (Logger log = new Logger(forwardHome, cfg, forward)) {
      log.log("INFO", "pre jump retained");
    }
    forward.step(30L * 86400000, 1000);
    try (Logger log = new Logger(forwardHome, cfg, forward)) {
      check(
          Config.read(new File(forwardHome, "logs/service.log")).contains("pre jump retained"),
          "same-boot time jump across restart defers age pruning");
    }
    File corruptHome = new File(home, "corrupt");
    corruptHome.mkdirs();
    File corrupt = new File(corruptHome, "state.db");
    byte[] damage = new byte[4096];
    Arrays.fill(damage, (byte) 0x55);
    try (FileOutputStream out = new FileOutputStream(corrupt)) {
      out.write(damage);
    }
    try {
      new StateDb(corruptHome);
      throw new AssertionError("corrupt db accepted");
    } catch (android.database.sqlite.SQLiteException expected) {
      check(corrupt.exists() && corrupt.length() == 4096, "corrupt state database preserved");
    }
    File capacityHome = new File(home, "capacity");
    capacityHome.mkdirs();
    JSONObject cappedRaw = new JSONObject(cfg.raw.toString());
    cappedRaw.getJSONObject("retention").put("max_messages", 1000);
    Config capped = new Config(cappedRaw.toString());
    try (Logger log = new Logger(capacityHome, capped);
        StateDb db = new StateDb(capacityHome)) {
      Fake fake = new Fake();
      SmsSource source = new SmsSource(h, db, null, log, capped, () -> {}, fake);
      db.begin();
      try {
        for (int i = 0; i < 1000; i++) db.add(sms(i, "capacity " + i), capped, true);
        db.commit();
      } finally {
        db.end();
      }
      fake.rows.add(sms(5000, "new after capacity"));
      try {
        source.scan();
        throw new AssertionError("capacity exceeded");
      } catch (IOException expected) {
        check(
            db.value("max_id") == 0 && count(db, "SELECT COUNT(*) FROM messages") == 1000,
            "failed capture transaction never advances cursor");
      }
      source.close();
    }
    routingAndHistory(home,cfg);
    System.out.println(
        "SELFTEST_OK checks=" + checks + " no_network=true no_system_SMS_mutation=true");
  }

  private static void routingAndHistory(File home, Config original) throws Exception {
    long now = System.currentTimeMillis();
    File isolated = new File(home,"routing");
    isolated.mkdirs();
    JSONObject raw = new JSONObject(original.raw.toString());
    JSONObject rules = Routing.defaults(original);
    rules.getJSONObject("notification").put("enabled",true).put("wecom",true)
        .put("smtp",true).put("apps",new JSONArray().put("com.example.good"));
    rules.getJSONObject("call").put("enabled",true).put("wecom",false).put("smtp",true);
    Config cfg = new Config(raw.put("routing",rules).toString());
    try (StateDb db = new StateDb(isolated)) {
      Routing.Event app = new Routing.Event("notification","notice1","合成应用","合成标题",
          "包含完整正文\n第二行","com.example.good","message",now,0,false);
      Routing.Event second = new Routing.Event("notification","notice2","合成应用","合成标题",
          app.body,app.pkg,app.category,now+1,0,false);
      Routing.Event other = new Routing.Event("notification","noticeX","其他应用","合成标题",
          app.body,"com.example.other","message",now,0,false);
      Routing.Event loop = new Routing.Event("notification","noticeY","合成应用","系统消息",
          db.marker+"\n已转发过的内容",app.pkg,app.category,now,0,false);
      Routing.Event call = new Routing.Event("call","call1","+12025550148","来电测试",
          "","", "",now,2,false,"+12025550149","合成运营商");
      check(Routing.effect(cfg,other,db.marker).equals("app_not_selected"),"unselected application is rejected");
      check(Routing.effect(cfg,loop,db.marker).equals("loop_prevented"),"same relay marker blocks either channel");
      long id, callId;
      db.begin();
      try {
        id=db.addEvent(app,cfg,true,null);
        db.addEvent(second,cfg,true,null);
        db.addEvent(loop,cfg,true,null);
        callId=db.addEvent(call,cfg,true,null);
        db.commit();
      } finally { db.end(); }
      check(db.pending()==5,"equal text with different event IDs is not discarded");
      StateDb.Task ring = db.due(now,db.allowed(cfg));
      check(ring.kind.equals("call"),"ring alert precedes ordinary notices");
      JSONObject callHistory=db.history.detail(callId,db.allowed(cfg));
      check(ring.payload.contains("来电号码：+12025550148")
          && ring.payload.contains("本机号码：+12025550149")
          && callHistory.getJSONObject("receiver").getInt("slot")==2
          && callHistory.getJSONObject("receiver").getString("number").equals("+12025550149"),
          "ring payload and history distinguish caller and receiving SIM number");
      StateDb.Sms sms=new StateDb.Sms();
      sms.date=now;sms.slot=1;sms.number="+12025550147";sms.carrier="合成运营商";sms.address="TEST";
      sms.body="合成正文";
      check(Routing.Event.sms(sms).message().contains("本机号码：+12025550147"),
          "SMS formatter identifies its receiving SIM number");
      db.expire(now+600001);
      check(db.pending()==4,"stale ring alert expires without sending");
      JSONObject entry=db.history.detail(id,db.allowed(cfg));
      check(entry.getString("body").equals(app.body) && entry.getJSONArray("tasks").length()==2,
          "history preserves notice content and independent channel tasks");
      StateDb.Task t=db.due(now+2,db.allowed(cfg));
      db.sending(t);
      db.result(t,true,false,"server_accepted",cfg,now+3);
      check(db.history.detail(id,db.allowed(cfg)).getJSONArray("steps").length()>=4,
          "send attempt and acceptance appear on detail timeline");
      JSONObject changed = new JSONObject(cfg.raw.toString());
      changed.getJSONObject("smtp").put("to",new JSONArray().put("another@example.invalid"));
      check(db.held(new Config(changed.toString()))==2,"old destination is held when recipient changes");
    }
    String jsonc="{\n  // keep this\n  \"smtp\": {\"password\": \"first\"} // keep this too\n}\n";
    String changed=JsonEdits.set(jsonc,"smtp.password","embedded \" quotes // text");
    changed=JsonEdits.set(changed,"routing.sms.enabled",true);
    check(changed.contains("// keep this") && changed.contains("// keep this too")
        && new JSONObject(changed).getJSONObject("smtp").getString("password").equals("embedded \" quotes // text"),
        "form changes preserve unrelated JSON comments and escape values");
  }
}
