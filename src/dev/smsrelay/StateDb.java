package dev.smsrelay;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.util.*;
import org.json.*;

public final class StateDb implements Closeable {
  final SQLiteDatabase db;

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
    public String channel, payload, settings;
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
    String fp = s.fingerprint();
    try (Cursor c = db.rawQuery("SELECT 1 FROM messages WHERE fp=?", new String[] {fp})) {
      if (c.moveToFirst()) return false;
    }
    if (android.database.DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM messages", null)
        >= cfg.retention.getInt("max_messages"))
      throw new IOException("dedup capacity reached; no cursor advance");
    boolean send = !baseline && cfg.enabled && cfg.accepts(s.slot, s.address, s.body);
    ContentValues m = new ContentValues();
    m.put("fp", fp);
    m.put("provider_id", s.providerId);
    m.put("sms_date", s.date);
    m.put("created", System.currentTimeMillis());
    if (send) m.put("body", s.body);
    long id = db.insertOrThrow("messages", null, m);
    if (!send) return false;
    String text = s.text();
    JSONObject messageConfig =
        new JSONObject(cfg.raw.toString()).put("message_subject", s.subject());
    // 按渠道、目标和分段记录任务，某个目标失败时可以独立补发。
    if (cfg.wecom.getBoolean("enabled")) {
      List<String> segments = Rules.split(text, 1900);
      String target = Rules.hash(cfg.wecom.getString("webhook"));
      for (int i = 0; i < segments.size(); i++)
        task(
            id,
            "wecom",
            target,
            i + 1,
            segments.size(),
            (segments.size() > 1 ? "[" + (i + 1) + "/" + segments.size() + "]\n" : "")
                + segments.get(i),
            messageConfig.toString());
    }
    if (cfg.smtp.getBoolean("enabled")) {
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
    return true;
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
    String sql =
        "SELECT t.id,t.message,t.channel,t.part,t.parts,t.payload,t.settings,t.attempt,t.next FROM"
            + " tasks t WHERE t.state='PENDING' AND t.next<=? AND NOT EXISTS(SELECT 1 FROM tasks p"
            + " WHERE p.message=t.message AND p.channel=t.channel AND p.target=t.target AND"
            + " p.part<t.part AND p.state!='SUCCEEDED') ORDER BY t.id LIMIT 1";
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
      return t;
    }
  }

  public long next() {
    try (Cursor c =
        db.rawQuery(
            "SELECT MIN(t.next) FROM tasks t WHERE state='PENDING' AND NOT EXISTS(SELECT 1 FROM"
                + " tasks p WHERE p.message=t.message AND p.channel=t.channel AND p.target=t.target"
                + " AND p.part<t.part AND p.state!='SUCCEEDED')",
            null)) {
      return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : -1;
    }
  }

  public long pending() {
    return android.database.DatabaseUtils.longForQuery(
        db, "SELECT COUNT(*) FROM tasks WHERE state IN ('PENDING','SENDING')", null);
  }

  public void sending(Task t) {
    db.execSQL(
        "UPDATE tasks SET state='SENDING',attempt=attempt+1 WHERE id=?", new Object[] {t.id});
    t.attempt++;
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
    long cutoff = now - cfg.retention.getInt("dedup_days") * 86400000L;
    begin();
    try {
      db.execSQL(
          "DELETE FROM tasks WHERE message IN (SELECT id FROM messages WHERE created<?) AND NOT"
              + " EXISTS(SELECT 1 FROM tasks x WHERE x.message=tasks.message AND"
              + " x.state!='SUCCEEDED')",
          new Object[] {cutoff});
      db.execSQL(
          "DELETE FROM messages WHERE created<? AND NOT EXISTS(SELECT 1 FROM tasks WHERE"
              + " message=messages.id)",
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
