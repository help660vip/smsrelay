package dev.smsrelay;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.util.*;
import org.json.*;

public final class StateDb implements Closeable {
  final SQLiteDatabase db;
  final History history;
  final String marker;
  private final java.util.Map<String,Long> notificationEvents =
      new LinkedHashMap<String,Long>(128,0.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Long> eldest) {
          return size() > 512;
        }
      };

  public static final class Sms {
    public long providerId, date;
    public int sub, slot;
    public String address, body, number = "未知", carrier = "未知";

    // 短信库恢复后记录 ID 可能变化，去重指纹用发件人、时间、订阅和正文计算。
    public String fingerprint() {
      return Rules.hash(address + "\u0000" + date + "\u0000" + sub + "\u0000" + body);
    }

    public String subject() {
      if (providerId < 0) return "smsrelay 渠道测试";
      return (body.contains("验证码") ? "验证码短信" : "新短信")
          + " · "
          + carrier
          + " · "
          + (slot > 0 ? "卡" + slot : "卡槽未知");
    }

    public String text() {
      if (providerId < 0) return "smsrelay 渠道连通性测试\n\n" + body;
      return "发件人："
          + address
          + "\n接收卡："
          + (slot > 0 ? "卡" + slot : "未知")
          + " · "
          + carrier
          + "\n本机号码："
          + number
          + "\n时间："
          + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)
              .format(new Date(date))
          + "\n\n"
          + body;
    }
  }

  public static final class Task {
    public long id, message, next;
    public int attempt, part, parts;
    public String channel, payload, settings, kind;
  }

  public StateDb(File home) throws Exception {
    File f = new File(home, "state.db");
    db =
        SQLiteDatabase.openDatabase(
            f.getPath(),
            null,
            SQLiteDatabase.CREATE_IF_NECESSARY,
            broken -> {
              throw new android.database.sqlite.SQLiteDatabaseCorruptException(
                  "state corrupt; preserved for recovery");
            });
    try (Cursor mode = db.rawQuery("PRAGMA journal_mode=DELETE", null)) {
      mode.moveToFirst();
    }
    db.execSQL("PRAGMA synchronous=FULL");
    db.execSQL("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY,v TEXT NOT NULL)");
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY,fp TEXT UNIQUE NOT"
            + " NULL,provider_id INTEGER,sms_date INTEGER,created INTEGER NOT NULL,body TEXT)");
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS tasks(id INTEGER PRIMARY KEY,message INTEGER NOT NULL,channel"
            + " TEXT NOT NULL,target TEXT NOT NULL,part INTEGER NOT NULL,parts INTEGER NOT"
            + " NULL,payload TEXT,settings TEXT,state TEXT NOT NULL,attempt INTEGER NOT NULL"
            + " DEFAULT 0,next INTEGER NOT NULL DEFAULT 0,error"
            + " TEXT,UNIQUE(message,channel,target,part))");
    db.execSQL("CREATE INDEX IF NOT EXISTS task_due ON tasks(state,next)");
    // 进程退出时服务端可能已经收到消息。重启补发可恢复队列，但这类任务可能重复送达。
    db.execSQL(
        "UPDATE tasks SET state='PENDING',error='recovered ambiguous send' WHERE state='SENDING'");
    history = new History(db);
    String savedMarker = get("loop_marker", "");
    if (savedMarker.isEmpty()) {
      savedMarker = "【smsrelay 转发】";
      put("loop_marker", savedMarker);
    }
    marker = savedMarker;
    android.system.Os.chmod(f.getPath(), 0600);
  }

  public String get(String k, String fallback) {
    try (Cursor c = db.rawQuery("SELECT v FROM meta WHERE k=?", new String[] {k})) {
      return c.moveToFirst() ? c.getString(0) : fallback;
    }
  }

  public long value(String k) {
    return Long.parseLong(get(k, "0"));
  }

  public void put(String k, String v) {
    ContentValues c = new ContentValues();
    c.put("k", k);
    c.put("v", v);
    db.insertWithOnConflict("meta", null, c, SQLiteDatabase.CONFLICT_REPLACE);
  }

  public void begin() {
    db.beginTransaction();
  }

  public void commit() {
    db.setTransactionSuccessful();
  }

  public void end() {
    db.endTransaction();
  }

  public boolean add(Sms s, Config cfg, boolean baseline) throws Exception {
    Routing.Event event = Routing.Event.sms(s);
    return enqueue(event, s.providerId, s.text(), cfg, baseline, s.providerId < 0, null);
  }

  long addEvent(Routing.Event event, Config cfg, boolean synthetic, String reason) throws Exception {
    return enqueueEvent(event, -1, event.message(), cfg, false, synthetic, reason);
  }

  void addChannelTest(Sms sms, Config cfg) throws Exception {
    Routing.Event event = new Routing.Event("test",sms.fingerprint(),sms.address,sms.subject(),
        sms.body,"","",sms.date,0,false);
    enqueueEvent(event,-1,sms.text(),cfg,false,true,"queued");
  }

  private boolean enqueue(Routing.Event event, long providerId, String text, Config cfg,
      boolean baseline, boolean synthetic, String reason) throws Exception {
    long id = enqueueEvent(event,providerId,text,cfg,baseline,synthetic,reason);
    if (id == 0) return false;
    return android.database.DatabaseUtils.longForQuery(db,
        "SELECT COUNT(*) FROM tasks WHERE message=?",new String[]{String.valueOf(id)}) > 0;
  }

  private long enqueueEvent(Routing.Event event, long providerId, String text, Config cfg,
      boolean baseline, boolean synthetic, String overrideReason) throws Exception {
    String fp = event.key;
    try (Cursor c = db.rawQuery("SELECT 1 FROM messages WHERE fp=?", new String[] {fp})) {
      if (c.moveToFirst()) return 0;
    }
    if (android.database.DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM messages", null)
        >= cfg.retention.getInt("max_messages"))
      throw new IOException("dedup capacity reached; no cursor advance");
    String decision = baseline ? "baseline" : overrideReason != null ? overrideReason
        : Routing.effect(cfg,event,marker);
    boolean send = decision.equals("queued");
    ContentValues m = new ContentValues();
    m.put("fp", fp);
    m.put("provider_id", providerId);
    m.put("sms_date", event.occurred);
    m.put("created", System.currentTimeMillis());
    if (send) m.put("body", event.body);
    long id = db.insertOrThrow("messages", null, m);
    if (!baseline && (synthetic || Routing.enabled(cfg,event.kind)))
      history.event(id,event,decision,synthetic);
    if (!send) return id;
    text = marker + "\n" + (synthetic ? "【测试消息】\n" : "") + text;
    JSONObject messageConfig =
        new JSONObject(cfg.raw.toString()).put("message_subject", marker + " " +
            History.clip(event.title.replace('\r',' ').replace('\n',' '),240));
    // 按渠道、目标和分段记录任务，某个目标失败时可以独立补发。
    if (Routing.channel(cfg,"wecom") && (event.kind.equals("test") || Routing.selected(cfg,event.kind,"wecom"))) {
      // 给每一段都加上同一来源标记，收件端把消息拆开时也能识别回环。
      List<String> segments = Rules.split(text.substring(marker.length()+1), 1860);
      String target = Rules.hash(cfg.wecom.getString("webhook"));
      for (int i = 0; i < segments.size(); i++)
        task(
            id,
            "wecom",
            target,
            i + 1,
            segments.size(),
            marker + "\n" +
                (segments.size() > 1 ? "[" + (i + 1) + "/" + segments.size() + "]\n" : "")
                + segments.get(i),
            messageConfig.toString());
    }
    if (Routing.channel(cfg,"smtp") && (event.kind.equals("test") || Routing.selected(cfg,event.kind,"smtp"))) {
      JSONArray to = cfg.smtp.getJSONArray("to");
      for (int i = 0; i < to.length(); i++) {
        JSONObject snapshot = new JSONObject(messageConfig.toString());
        snapshot.getJSONObject("smtp").put("to", new JSONArray().put(to.getString(i)));
        task(
            id,
            "smtp",
            Rules.hash(
                cfg.smtp.getString("host")
                    + "|"
                    + cfg.smtp.getString("from")
                    + "|"
                    + to.getString(i)),
            1,
            1,
            text,
            snapshot.toString());
      }
    }
    return id;
  }

  private void task(
      long msg,
      String channel,
      String target,
      int part,
      int parts,
      String payload,
      String settings) {
    ContentValues v = new ContentValues();
    v.put("message", msg);
    v.put("channel", channel);
    v.put("target", target);
    v.put("part", part);
    v.put("parts", parts);
    v.put("payload", payload);
    v.put("settings", settings);
    v.put("state", "PENDING");
    db.insertOrThrow("tasks", null, v);
  }

  // 同一消息发往同一目标时，前面的分段全部成功后才能发送后续分段。
  public Task due(long now) {
    return due(now, "1");
  }

  Task due(long now, String allowed) {
    String sql =
        "SELECT t.id,t.message,t.channel,t.part,t.parts,t.payload,t.settings,t.attempt,t.next,"
            + kindSql() + " FROM"
            + " tasks t WHERE t.state='PENDING' AND t.next<=? AND NOT EXISTS(SELECT 1 FROM tasks p"
            + " WHERE p.message=t.message AND p.channel=t.channel AND p.target=t.target AND"
            + " p.part<t.part AND p.state!='SUCCEEDED') AND (" + allowed + ")"
            + " ORDER BY CASE " + kindSql() + " WHEN 'call' THEN 0 WHEN 'sms' THEN 1 ELSE 2 END,t.id LIMIT 1";
    try (Cursor c = db.rawQuery(sql, new String[] {"" + now})) {
      if (!c.moveToFirst()) return null;
      Task t = new Task();
      t.id = c.getLong(0);
      t.message = c.getLong(1);
      t.channel = c.getString(2);
      t.part = c.getInt(3);
      t.parts = c.getInt(4);
      t.payload = c.getString(5);
      t.settings = c.getString(6);
      t.attempt = c.getInt(7);
      t.next = c.getLong(8);
      t.kind = c.getString(9);
      return t;
    }
  }

  public long next() {
    return next("1");
  }

  long next(String allowed) {
    try (Cursor c =
        db.rawQuery(
            "SELECT MIN(t.next) FROM tasks t WHERE state='PENDING' AND NOT EXISTS(SELECT 1 FROM"
                + " tasks p WHERE p.message=t.message AND p.channel=t.channel AND p.target=t.target"
                + " AND p.part<t.part AND p.state!='SUCCEEDED') AND (" + allowed + ")",
            null)) {
      return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : -1;
    }
  }

  static String kindSql() {
    return "COALESCE((SELECT kind FROM relay_events WHERE message=t.message),'sms')";
  }

  String allowed(Config cfg) throws Exception {
    ArrayList<String> clauses = new ArrayList<>();
    for (String channel : Routing.CHANNELS) {
      if (!Routing.channel(cfg,channel)) continue;
      ArrayList<String> kinds = new ArrayList<>();
      kinds.add("test"); // Magisk 菜单的连通性测试独立于三项功能开关。
      for (String kind : Routing.KINDS) {
        if (Routing.enabled(cfg,kind) && Routing.selected(cfg,kind,channel)) kinds.add(kind);
      }
      ArrayList<String> keys = new ArrayList<>();
      if (channel.equals("wecom")) keys.add(Rules.hash(cfg.wecom.getString("webhook")));
      else {
        JSONArray to = cfg.smtp.getJSONArray("to");
        for (int i=0;i<to.length();i++)
          keys.add(Rules.hash(cfg.smtp.getString("host")+"|"+cfg.smtp.getString("from")+"|"+to.getString(i)));
      }
      // 只用程序定义的标识和 SHA-256 值组成查询，用户内容不进入 SQL。
      clauses.add("(" + kindSql() + " IN ('" + android.text.TextUtils.join("','",kinds)
          + "') AND t.channel='" + channel + "' AND t.target IN ('"
          + android.text.TextUtils.join("','",keys) + "'))");
    }
    return clauses.isEmpty() ? "0" : android.text.TextUtils.join(" OR ",clauses);
  }

  void rememberNotification(String key, long id) { notificationEvents.put(key,id); }

  void noteDuplicate(String key) {
    Long id = notificationEvents.get(key);
    if (id != null) history.duplicate(id);
  }

  long lastCaptured(String kind) {
    return android.database.DatabaseUtils.longForQuery(db,
        "SELECT COALESCE(MAX(captured),0) FROM relay_events WHERE kind=?",
        new String[]{kind});
  }

  long nextExpiry() {
    return android.database.DatabaseUtils.longForQuery(db,"SELECT COALESCE(MIN(e.expires),0) FROM"
        +" relay_events e JOIN tasks t ON t.message=e.message WHERE e.expires>0 AND t.state='PENDING'",null);
  }

  void expire(long now) {
    if (android.database.DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM tasks t "
        + "JOIN relay_events e ON e.message=t.message WHERE t.state='PENDING' AND e.expires>0 AND e.expires<=?",
        new String[]{String.valueOf(now)}) == 0) return;
    begin();
    try { history.expire(now); commit(); }
    finally { end(); }
  }

  long held(Config cfg) throws Exception {
    return android.database.DatabaseUtils.longForQuery(db,
        "SELECT COUNT(*) FROM tasks t WHERE state='PENDING' AND NOT ("+allowed(cfg)+")",null);
  }

  boolean retryOne(long id, Config cfg) throws Exception {
    String[] args = {String.valueOf(id)};
    long message;
    int part;
    String channel, target;
    JSONObject old;
    try(Cursor c=db.rawQuery("SELECT t.message,t.channel,t.settings,t.target,t.part FROM tasks t "
        +"WHERE t.id=? AND t.state='FAILED' AND ("+allowed(cfg)+") AND NOT EXISTS"
        +"(SELECT 1 FROM relay_events e WHERE e.message=t.message AND e.expires>0 AND e.expires<="
        +System.currentTimeMillis()+")",args)) {
      if(!c.moveToFirst()) return false;
      message=c.getLong(0);channel=c.getString(1);
      old=new JSONObject(c.getString(2));target=c.getString(3);part=c.getInt(4);
    }
    JSONObject current = new JSONObject(cfg.raw.toString());
    current.put("message_subject",old.optString("message_subject","smsrelay"));
    if(channel.equals("smtp"))
      current.getJSONObject("smtp").put("to",old.getJSONObject("smtp").getJSONArray("to"));
    begin();
    try {
      db.execSQL("UPDATE tasks SET settings=?,state='PENDING',attempt=0,next=0,error='manual_retry' "
          +"WHERE message=? AND target=? AND channel=? AND (id=? OR (part>? AND state='FAILED' "
          +"AND error='blocked by failed earlier segment'))",
          new Object[]{current.toString(),message,target,channel,id,part});
      history.step(message,id,channel,System.currentTimeMillis(),"manual_retry","",0);
      commit();
    } finally { end(); }
    return true;
  }

  public long pending() {
    return android.database.DatabaseUtils.longForQuery(
        db, "SELECT COUNT(*) FROM tasks WHERE state IN ('PENDING','SENDING')", null);
  }

  public void sending(Task t) {
    db.execSQL(
        "UPDATE tasks SET state='SENDING',attempt=attempt+1 WHERE id=?", new Object[] {t.id});
    t.attempt++;
    history.step(t.message,t.id,t.channel,System.currentTimeMillis(),"sending","",t.attempt);
  }

  public void result(
      Task t, boolean success, boolean permanent, String error, Config cfg, long now) {
    long delay = Rules.delay(t.attempt, cfg.retry);
    String state = success ? "SUCCEEDED" : permanent || delay < 0 ? "FAILED" : "PENDING";
    begin();
    try {
      db.execSQL(
          "UPDATE tasks SET state=?,next=?,error=? WHERE id=?",
          new Object[] {state, success ? 0 : now + Math.max(0, delay), error, t.id});
      if (state.equals("FAILED"))
        db.execSQL(
            "UPDATE tasks SET state='FAILED',error='blocked by failed earlier segment' WHERE"
                + " message=? AND channel=? AND target=(SELECT target FROM tasks WHERE id=?) AND"
                + " part>? AND state='PENDING'",
            new Object[] {t.message, t.channel, t.id, t.part});
      history.step(t.message,t.id,t.channel,now,state.equals("PENDING") ? "retry_wait" : state.toLowerCase(Locale.ROOT),error,t.attempt);
      put(success ? "last_success" : "last_error", now + ":" + t.channel + ":" + error);
      commit();
    } finally {
      end();
    }
  }

  public void offline(Task t) {
    db.execSQL(
        "UPDATE tasks SET state='PENDING',attempt=MAX(0,attempt-1),error='waiting network' WHERE"
            + " id=?",
        new Object[] {t.id});
    history.once(t,"waiting_network","network_unavailable");
  }

  public int retry() {
    db.execSQL(
        "UPDATE tasks SET state='PENDING',attempt=0,next=0,error='manual retry' WHERE"
            + " state='FAILED'");
    return (int) pending();
  }

  // 手动重试可用新凭据修复旧任务，但发送目标必须与入队时一致。
  public int retry(Config cfg) throws Exception {
    begin();
    try {
      if (cfg.wecom.getBoolean("enabled"))
        db.execSQL(
            "UPDATE tasks SET settings=?,state='PENDING',attempt=0,next=0,error='manual retry'"
                + " WHERE state='FAILED' AND channel='wecom' AND target=?",
            new Object[] {cfg.raw.toString(), Rules.hash(cfg.wecom.getString("webhook"))});
      if (cfg.smtp.getBoolean("enabled")) {
        JSONArray to = cfg.smtp.getJSONArray("to");
        for (int i = 0; i < to.length(); i++) {
          JSONObject snapshot = new JSONObject(cfg.raw.toString());
          snapshot.getJSONObject("smtp").put("to", new JSONArray().put(to.getString(i)));
          String target =
              Rules.hash(
                  cfg.smtp.getString("host")
                      + "|"
                      + cfg.smtp.getString("from")
                      + "|"
                      + to.getString(i));
          db.execSQL(
              "UPDATE tasks SET settings=?,state='PENDING',attempt=0,next=0,error='manual retry'"
                  + " WHERE state='FAILED' AND channel='smtp' AND target=?",
              new Object[] {snapshot.toString(), target});
        }
      }
      commit();
    } finally {
      end();
    }
    return (int) pending();
  }

  public JSONObject status() throws Exception {
    JSONObject j = new JSONObject();
    try (Cursor c = db.rawQuery("SELECT state,COUNT(*) FROM tasks GROUP BY state", null)) {
      while (c.moveToNext()) j.put(c.getString(0), c.getLong(1));
    }
    j.put("last_success", get("last_success", "none"));
    j.put("last_error", get("last_error", "none"));
    return j;
  }

  public void cleanup(Config cfg) throws Exception {
    long now = System.currentTimeMillis();
    db.execSQL(
        "UPDATE messages SET body=NULL WHERE created<? AND NOT EXISTS(SELECT 1 FROM tasks WHERE"
            + " message=messages.id AND state!='SUCCEEDED')",
        new Object[] {now - cfg.retention.getInt("completed_body_hours") * 3600000L});
    db.execSQL(
        "UPDATE tasks SET payload=NULL,settings=NULL WHERE state='SUCCEEDED' AND message IN (SELECT"
            + " id FROM messages WHERE body IS NULL)");
    history.cleanup(now - cfg.retention.optInt("history_days",7) * 86400000L);
    long cutoff = now - cfg.retention.getInt("dedup_days") * 86400000L;
    begin();
    try {
      db.execSQL(
          "DELETE FROM tasks WHERE message IN (SELECT id FROM messages WHERE created<?) AND NOT"
              + " EXISTS(SELECT 1 FROM tasks x WHERE x.message=tasks.message AND"
              + " x.state IN ('PENDING','SENDING','FAILED')) AND NOT EXISTS"
              + " (SELECT 1 FROM relay_events WHERE message=tasks.message)",
          new Object[] {cutoff});
      db.execSQL(
          "DELETE FROM messages WHERE created<? AND NOT EXISTS(SELECT 1 FROM tasks WHERE"
              + " message=messages.id) AND NOT EXISTS(SELECT 1 FROM relay_events WHERE message=messages.id)",
          new Object[] {cutoff});
      commit();
    } finally {
      end();
    }
  }

  @Override
  public void close() {
    db.close();
  }
}
