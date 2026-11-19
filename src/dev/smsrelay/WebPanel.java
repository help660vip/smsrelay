package dev.smsrelay;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Base64;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import org.json.JSONArray;
import org.json.JSONObject;

final class WebPanel {
  private final RelayCore core;
  WebPanel(RelayCore core) { this.core=core; }

  static JSONObject decode(String request) throws Exception {
    if (!request.matches("[A-Za-z0-9_-]{1,49152}")) throw new IllegalArgumentException("bad_request");
    return new JSONObject(new String(Base64.decode(request,Base64.URL_SAFE|Base64.NO_PADDING|Base64.NO_WRAP),
        StandardCharsets.UTF_8));
  }

  static JSONObject error(String reason) throws Exception { return new JSONObject().put("error",reason); }

  String request(String token) throws Exception {
    JSONObject req=decode(token);
    try { return perform(req).toString(); }
    catch (Exception error) {
      core.log.error("web_"+operation(req),error);
      String hint=error.getMessage();
      if (!(error instanceof IllegalArgumentException) || hint==null || !hint.matches("[A-Za-z0-9_.]{1,64}"))
        hint="validation_failed";
      return error(hint).toString();
    }
  }

  private static String operation(JSONObject req) {
    String op=req.optString("op");
    return op.matches("[a-z_]{1,16}") ? op : "unknown";
  }

  private JSONObject perform(JSONObject req) throws Exception {
    switch(operation(req)) {
      case "status":
      case "start": return core.status();
      case "settings": return settings();
      case "save": return save(req);
      case "apps": return new JSONObject().put("apps",apps());
      case "history":
        return new JSONObject().put("rows",core.db.history.list(req.optString("kind"),req.optString("state"),
            req.optLong("before",Long.MAX_VALUE),req.optLong("from"),req.optString("channel"),
            History.clip(req.optString("search"),120)));
      case "detail": return core.db.history.detail(req.getLong("id"),core.db.allowed(core.cfg));
      case "share": return share(req.getLong("id"));
      case "logs": return new JSONObject().put("text",Control.tail(core.home,req.optBoolean("warnings",true)));
      case "export": {
        String path=Control.export(core.home,core.status().toString());
        return new JSONObject().put("path",path).put("text",Config.read(new File(path)));
      }
      case "retry":
        if (!core.db.retryOne(req.getLong("id"),core.cfg)) throw new IllegalArgumentException("retry_unavailable");
        core.dispatch();
        return new JSONObject().put("ok",true);
      case "preview": return preview(req,false);
      case "test": return preview(req,true);
      case "listeners":
        core.configureSources();
        return core.status();
      default: throw new IllegalArgumentException("bad_operation");
    }
  }

  private JSONObject share(long id) throws Exception {
    JSONObject redacted=core.db.history.detail(id,core.db.allowed(core.cfg));
    for(String key:new String[]{"body","source","title","pkg","category"}) redacted.remove(key);
    return new JSONObject().put("text",redacted.toString(2));
  }

  private JSONObject settings() throws Exception {
    Config c=core.cfg;
    String source=Config.read(new File(core.home,"config.json"));
    JSONObject smtp=new JSONObject(c.smtp.toString());
    smtp.remove("password");
    return new JSONObject()
        .put("revision",Rules.hash(source))
        .put("pending",core.db.pending())
        .put("routing",Routing.defaults(c))
        .put("wecom",new JSONObject().put("enabled",c.wecom.optBoolean("enabled"))
            .put("configured",!c.wecom.optString("webhook").contains("REPLACE-ME")
                && !c.wecom.optString("webhook").isEmpty()))
        .put("smtp",smtp.put("configured",!c.smtp.optString("password").isEmpty()
            && !c.smtp.optString("password").equals("REPLACE-ME")))
        .put("filters",c.filters)
        .put("history_days",c.retention.optInt("history_days",7));
  }

  private JSONObject save(JSONObject req) throws Exception {
    File file=new File(core.home,"config.json");
    String previous=Config.read(file);
    if (!Rules.hash(previous).equals(req.getString("revision")))
      throw new IllegalArgumentException("config_changed");
    // 在人工修改尚未生效时先阻止保存，避免表单盖掉新值。
    if (!new Config(previous).raw.toString().equals(core.cfg.raw.toString()))
      throw new IllegalArgumentException("config_not_applied");
    JSONObject changes=req.getJSONObject("changes");
    if (changes.length()>32) throw new IllegalArgumentException("too_many_fields");
    String content=previous;
    java.util.Iterator<String> keys=changes.keys();
    while(keys.hasNext()) {
      String key=keys.next();
      if (!key.matches("routing\\.(sms|call|notification)\\.(enabled|wecom|smtp|apps|exclude_channels)"
          +"|wecom\\.(enabled|webhook)|smtp\\.(enabled|host|port|tls|username|password|from|to)"
          +"|filters\\.(sim_slots|numbers|keywords)|retention\\.history_days"))
        throw new IllegalArgumentException("unknown_field");
      Object value=changes.get(key);
      if ((key.equals("wecom.webhook") || key.equals("smtp.password")) && "".equals(value)) continue;
      boolean array=key.matches("filters\\..*|.*\\.(apps|exclude_channels|to)");
      boolean flag=key.matches(".*\\.(enabled|wecom|smtp)");
      boolean number=key.equals("smtp.port") || key.equals("retention.history_days");
      if (!(array ? value instanceof JSONArray : flag ? value instanceof Boolean :
          number ? value instanceof Integer : value instanceof String))
        throw new IllegalArgumentException("invalid_field");
      content=JsonEdits.set(content,key,value);
    }
    Config next=new Config(content);
    long held=core.db.held(next);
    if (held > core.db.held(core.cfg) && !req.optBoolean("confirm_hold"))
      return new JSONObject().put("confirmation","hold_pending").put("count",held);
    if (!previous.equals(Config.read(file))) throw new IllegalArgumentException("config_changed");
    Logger.atomic(file,content);
    try { core.apply(next); }
    catch (Exception e) { Logger.atomic(file,previous); throw e; }
    return settings().put("saved",true).put("held",held);
  }

  private JSONArray apps() throws Exception {
    PackageManager pm=core.device.context.getPackageManager();
    ArrayList<JSONObject> result=new ArrayList<>();
    for(ApplicationInfo info:pm.getInstalledApplications(0)){
      String label;
      try { label=pm.getApplicationLabel(info).toString(); } catch (RuntimeException e) { label=info.packageName; }
      result.add(new JSONObject().put("label",History.clip(label,120)).put("pkg",info.packageName)
          .put("system",(info.flags&ApplicationInfo.FLAG_SYSTEM)!=0));
    }
    Collections.sort(result,Comparator.comparing((JSONObject o)->o.optString("label"),String.CASE_INSENSITIVE_ORDER));
    return new JSONArray(result);
  }

  private JSONObject preview(JSONObject req, boolean send) throws Exception {
    String kind=req.getString("kind");
    if (!Routing.isKind(kind)) throw new IllegalArgumentException("kind");
    String pkg=History.clip(req.optString("pkg"),220);
    String simulatedSource=History.clip(req.optString("source","模拟号码"),100);
    String source=kind.equals("notification") ? label(pkg) : simulatedSource;
    String title=History.clip(req.optString("title","测试通知").replace('\r',' ').replace('\n',' '),120);
    String body=History.clip(req.optString("body","这是一条模拟消息"),1800);
    long now=System.currentTimeMillis();
    Routing.Event event=new Routing.Event(kind,"web-test:"+java.util.UUID.randomUUID(),source,
        kind.equals("call") ? "来电提醒 · "+source : kind.equals("sms") ? "新短信 · "+source : title,
        body,pkg,History.clip(req.optString("category"),120),now,
        req.optInt("slot",1),req.optBoolean("noisy",false));
    String decision=Routing.effect(core.cfg,event,core.db.marker);
    JSONArray channels=new JSONArray();
    if (decision.equals("queued")) for(String channel:Routing.CHANNELS)
      if(Routing.channel(core.cfg,channel)&&Routing.selected(core.cfg,kind,channel)) channels.put(channel);
    JSONObject result=new JSONObject().put("decision",decision).put("channels",channels)
        .put("title",event.title).put("body",event.message());
    if (!send || !decision.equals("queued")) return result;
    if (!req.optBoolean("confirmed")) throw new IllegalArgumentException("test_confirmation_required");
    if (now-core.db.value("last_web_test")<15000) throw new IllegalArgumentException("test_rate_limit");
    Routing.Event sample=new Routing.Event(kind,event.key,source,"[测试] "+event.title,
        body,pkg,event.category,now,event.slot,event.noisy);
    long id;
    core.db.begin();
    try { id=core.db.addEvent(sample,core.cfg,true,null); core.db.put("last_web_test",String.valueOf(now)); core.db.commit(); }
    finally { core.db.end(); }
    core.dispatch();
    return result.put("id",id);
  }

  private String label(String pkg) {
    try {
      PackageManager pm=core.device.context.getPackageManager();
      return pm.getApplicationLabel(pm.getApplicationInfo(pkg,0)).toString();
    } catch (PackageManager.NameNotFoundException e) { return pkg; }
  }
}
