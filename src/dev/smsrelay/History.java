package dev.smsrelay;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;

final class History {
  private final SQLiteDatabase db;
  History(SQLiteDatabase db) {
    this.db = db;
    db.execSQL("CREATE TABLE IF NOT EXISTS relay_events (message INTEGER PRIMARY KEY, kind TEXT NOT NULL,"
        + " source TEXT, title TEXT, body TEXT, pkg TEXT, category TEXT, occurred INTEGER NOT NULL,"
        + " captured INTEGER NOT NULL, decision TEXT NOT NULL, expires INTEGER NOT NULL DEFAULT 0,"
        + " synthetic INTEGER NOT NULL DEFAULT 0)");
    db.execSQL("CREATE TABLE IF NOT EXISTS relay_steps (id INTEGER PRIMARY KEY, message INTEGER NOT NULL,"
        + " task INTEGER, channel TEXT, at INTEGER NOT NULL, state TEXT NOT NULL, reason TEXT, attempt INTEGER)");
    db.execSQL("CREATE INDEX IF NOT EXISTS relay_steps_message ON relay_steps(message,id)");
  }

  void event(long message, Routing.Event event, String decision, boolean synthetic) {
    ContentValues v = new ContentValues();
    v.put("message",message); v.put("kind",event.kind); v.put("source",event.source);
    v.put("title",event.title); v.put("body",event.kind.equals("call")?event.message():event.body); v.put("pkg",event.pkg);
    v.put("category",event.category); v.put("occurred",event.occurred);
    long now = System.currentTimeMillis();
    v.put("captured",now); v.put("decision",decision); v.put("synthetic",synthetic ? 1 : 0);
    v.put("expires",event.kind.equals("call") ? event.occurred + 600000L : 0L);
    db.insertOrThrow("relay_events",null,v);
    step(message,0,"",now,"received",decision,0);
    if (decision.equals("queued")) step(message,0,"",now,"queued","",0);
  }

  void step(long message, long task, String channel, long now, String state, String reason, int attempt) {
    db.execSQL("INSERT INTO relay_steps(message,task,channel,at,state,reason,attempt)"
        + " SELECT ?,?,?,?,?,?,? WHERE EXISTS (SELECT 1 FROM relay_events WHERE message=?)",
        new Object[]{message,task,channel,now,state,reason,attempt,message});
  }

  void once(StateDb.Task t, String state, String reason) {
    try (Cursor c = db.rawQuery("SELECT state,reason FROM relay_steps WHERE task=? ORDER BY id DESC LIMIT 1",
        new String[]{String.valueOf(t.id)})) {
      if (c.moveToFirst() && state.equals(c.getString(0)) && reason.equals(c.getString(1))) return;
    }
    step(t.message,t.id,t.channel,System.currentTimeMillis(),state,reason,t.attempt);
  }

  void duplicate(long id) {
    // 高频更新保留计数和最后一次时间，避免日志随每次进度变化增长。
    long now = System.currentTimeMillis();
    db.execSQL("INSERT INTO relay_steps(message,task,channel,at,state,reason,attempt)"
        + " SELECT ?,0,'',?,'duplicate_update','unchanged_notification',0 WHERE NOT EXISTS"
        + " (SELECT 1 FROM relay_steps WHERE message=? AND state='duplicate_update')",
        new Object[]{id,now,id});
    db.execSQL("UPDATE relay_steps SET at=?,attempt=attempt+1 WHERE message=? AND state='duplicate_update'",
        new Object[]{now,id});
  }

  void expire(long now) {
    db.execSQL("INSERT INTO relay_steps(message,task,channel,at,state,reason,attempt)"
        + " SELECT t.message,t.id,t.channel,?,'expired','call_expired',t.attempt FROM tasks t"
        + " JOIN relay_events e ON e.message=t.message WHERE t.state='PENDING' AND e.expires>0 AND e.expires<=?",
        new Object[]{now,now});
    db.execSQL("UPDATE tasks SET state='EXPIRED',error='call_expired' WHERE state='PENDING' AND message IN"
        + " (SELECT message FROM relay_events WHERE expires>0 AND expires<=?)",new Object[]{now});
  }

  void cleanup(long cutoff) {
    db.execSQL("DELETE FROM relay_events WHERE captured<? AND NOT EXISTS (SELECT 1 FROM tasks"
        + " WHERE tasks.message=relay_events.message AND state IN ('PENDING','SENDING','FAILED'))",
        new Object[]{cutoff});
    db.execSQL("DELETE FROM relay_steps WHERE NOT EXISTS (SELECT 1 FROM relay_events e WHERE e.message=relay_steps.message)");
  }

  JSONArray list(String kind, String state, long before, long from, String channel, String search) throws Exception {
    JSONArray rows = new JSONArray();
    String sql = "SELECT message,kind,source,title,captured,decision,synthetic,"
        + "(SELECT group_concat(DISTINCT t.state) FROM tasks t WHERE t.message=e.message)"
        + " FROM relay_events e WHERE message<? AND captured>=?"
        + (channel.isEmpty() ? "" : " AND EXISTS(SELECT 1 FROM tasks t WHERE t.message=e.message AND t.channel=?)")
        + (search.isEmpty() ? "" : " AND instr(lower(source||' '||title||' '||pkg),lower(?))>0")
        + (kind.isEmpty() ? "" : " AND kind=?")
        + (state.isEmpty() ? "" : " AND EXISTS(SELECT 1 FROM tasks t WHERE t.message=e.message AND t.state=?)")
        + " ORDER BY message DESC LIMIT 40";
    java.util.ArrayList<String> args = new java.util.ArrayList<>();
    args.add(String.valueOf(before));
    args.add(String.valueOf(from));
    if (!channel.isEmpty()) args.add(channel);
    if (!search.isEmpty()) args.add(search);
    if (!kind.isEmpty()) args.add(kind);
    if (!state.isEmpty()) args.add(state);
    try (Cursor c = db.rawQuery(sql,args.toArray(new String[0]))) {
      while (c.moveToNext()) rows.put(new JSONObject()
          .put("id",c.getLong(0)).put("kind",c.getString(1))
          .put("source",clip(c.getString(2),100)).put("title",clip(c.getString(3),120))
          .put("captured",c.getLong(4)).put("decision",c.getString(5))
          .put("synthetic",c.getInt(6)!=0).put("states",c.isNull(7)?"":c.getString(7)));
    }
    return rows;
  }

  JSONObject detail(long id, String allowed) throws Exception {
    String[] args = {String.valueOf(id)};
    JSONObject result;
    try (Cursor c = db.rawQuery("SELECT kind,source,title,body,pkg,category,occurred,captured,decision"
        + " FROM relay_events WHERE message=?",args)) {
      if (!c.moveToFirst()) throw new IllegalArgumentException("record_missing");
      result = new JSONObject().put("kind",c.getString(0)).put("source",c.getString(1))
          .put("title",c.getString(2)).put("body",c.getString(3)).put("pkg",c.getString(4))
          .put("category",c.getString(5)).put("occurred",c.getLong(6)).put("captured",c.getLong(7))
          .put("decision",c.getString(8));
    }
    JSONArray steps = new JSONArray(), tasks = new JSONArray();
    try (Cursor c = db.rawQuery("SELECT at,channel,task,state,reason,attempt FROM relay_steps"
        + " WHERE message=? ORDER BY id",args)) {
      while(c.moveToNext()) steps.put(new JSONObject().put("at",c.getLong(0))
          .put("channel",c.getString(1)).put("task",c.getLong(2)).put("state",c.getString(3))
          .put("reason",c.getString(4)).put("attempt",c.getInt(5)));
    }
    try (Cursor c = db.rawQuery("SELECT id,channel,state,error,next,part,parts,"
        + "CASE WHEN state='PENDING' AND NOT ("+allowed+") THEN 1 ELSE 0 END FROM tasks t WHERE message=?"
        + " ORDER BY id",args)) {
      while(c.moveToNext()) tasks.put(new JSONObject().put("id",c.getLong(0))
          .put("channel",c.getString(1)).put("state",c.getString(2)).put("reason",c.getString(3))
          .put("next",c.getLong(4)).put("part",c.getInt(5)).put("parts",c.getInt(6))
          .put("held",c.getInt(7)!=0));
    }
    return result.put("steps",steps).put("tasks",tasks);
  }

  static String clip(String s, int max) {
    return s == null ? "" : s.length() <= max ? s : s.substring(0,max);
  }
}
