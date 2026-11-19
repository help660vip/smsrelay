package dev.smsrelay;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

final class Routing {
  static final String[] KINDS = {"sms", "call", "notification"};
  static final String[] CHANNELS = {"wecom", "smtp"};

  static boolean isKind(String kind) {
    for (String k : KINDS) if (k.equals(kind)) return true;
    return false;
  }

  static JSONObject defaults(Config c) throws Exception {
    JSONObject result = new JSONObject();
    for (String kind : KINDS) {
      JSONObject rule = rule(c, kind);
      result.put(kind, new JSONObject(rule.toString()));
    }
    return result;
  }

  static JSONObject rule(Config c, String kind) throws Exception {
    if (!isKind(kind)) throw new IllegalArgumentException("kind");
    JSONObject set = c.raw.optJSONObject("routing");
    JSONObject entry = set == null ? null : set.optJSONObject(kind);
    if (entry != null) return entry;
    return new JSONObject()
        .put("enabled", kind.equals("sms") && c.enabled)
        .put("wecom", kind.equals("sms"))
        .put("smtp", true)
        .put("apps", new JSONArray())
        .put("exclude_channels", new JSONArray());
  }

  static void validate(Config c) throws Exception {
    if (c.retention.has("history_days")) Config.bounded(c.retention,"history_days",1,30);
    if (!c.raw.has("routing")) return;
    JSONObject set = c.raw.getJSONObject("routing");
    for (String kind : KINDS) {
      if (!set.has(kind)) continue;
      JSONObject r = set.getJSONObject(kind);
      for (String field : new String[] {"enabled","wecom","smtp"}) {
        if (!(r.get(field) instanceof Boolean)) throw new IllegalArgumentException("routing."+kind+"."+field);
      }
      if (kind.equals("notification")) {
        for (String field : new String[] {"apps","exclude_channels"}) {
          if (!r.has(field)) continue;
          JSONArray values = r.getJSONArray(field);
          if (values.length() > 200) throw new IllegalArgumentException(field);
          for (int i=0;i<values.length();i++) {
            if (!(values.get(i) instanceof String) || values.getString(i).length() > 300)
              throw new IllegalArgumentException(field);
          }
        }
      }
    }
  }

  static boolean enabled(Config c, String kind) {
    try { return rule(c, kind).getBoolean("enabled"); }
    catch (Exception e) { return false; }
  }

  static boolean channel(Config c, String key) {
    return (key.equals("wecom") ? c.wecom : c.smtp).optBoolean("enabled");
  }

  static boolean selected(Config c, String kind, String channel) {
    try { return rule(c,kind).getBoolean(channel); }
    catch (Exception e) { return false; }
  }

  static boolean contains(JSONArray values, String value) {
    if (values != null) for (int i=0;i<values.length();i++) if (value.equals(values.optString(i))) return true;
    return false;
  }

  static boolean selectedApp(Config c, String name) throws Exception {
    return contains(rule(c, "notification").optJSONArray("apps"),name);
  }

  static String effect(Config c, Event event, String marker) throws Exception {
    if (!enabled(c,event.kind)) return "feature_disabled";
    if (event.kind.equals("sms") && !c.accepts(event.slot,event.source,event.body))
      return "sms_filtered";
    if (event.kind.equals("notification")) {
      if (!selectedApp(c,event.pkg)) return "app_not_selected";
      if (event.title.contains(marker) || event.body.contains(marker)) return "loop_prevented";
      if (contains(rule(c,"notification").optJSONArray("exclude_channels"),event.pkg+"/"+event.category))
        return "notification_channel_excluded";
      if (event.noisy) return "ongoing_or_group_summary";
    }
    for (String key : CHANNELS) if (selected(c,event.kind,key) && channel(c,key)) return "queued";
    return "no_active_channel";
  }

  static final class Event {
    final String kind, key, source, title, body, pkg, category, receiverNumber, carrier;
    final long occurred;
    final int slot;
    final boolean noisy;
    Event(String kind, String key, String source, String title, String body, String pkg,
        String category, long occurred, int slot, boolean noisy) {
      this(kind,key,source,title,body,pkg,category,occurred,slot,noisy,"未知","未知");
    }
    Event(String kind, String key, String source, String title, String body, String pkg,
        String category, long occurred, int slot, boolean noisy, String receiverNumber, String carrier) {
      this.receiverNumber=receiverNumber;
      this.carrier=carrier;
      this.kind=kind; this.key=key; this.source=source; this.title=title; this.body=body;
      this.pkg=pkg; this.category=category; this.occurred=occurred; this.slot=slot; this.noisy=noisy;
    }
    static Event sms(StateDb.Sms sms) {
      return new Event("sms",sms.fingerprint(),sms.address,sms.subject(),sms.body,"","",
          sms.date,sms.slot,false,sms.number,sms.carrier);
    }
    String message() {
      String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date(occurred));
      String sim = "\n接收卡："+(slot>0?"卡"+slot:"未知")+" · "+carrier+"\n本机号码："+receiverNumber;
      if (kind.equals("sms"))
        return "发件人："+source+sim+"\n时间："+time+"\n\n"+body;
      if (kind.equals("call"))
        return "来电响铃提醒\n来电号码："+source+sim+"\n响铃时间："+time
            + (body.isEmpty()?"":"\n\n"+body);
      return "应用："+source+"\n标题："+title+"\n时间："+time+"\n\n"+body;
    }
  }
}
