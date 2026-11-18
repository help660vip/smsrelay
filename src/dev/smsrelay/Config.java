package dev.smsrelay;

import java.io.*;
import java.net.*;
import java.util.*;
import org.json.*;

public final class Config {
  public final JSONObject raw, wecom, smtp, filters, logging, retention;
  public final boolean enabled;
  public final int[] retry;
  public final int connect, io, total;

  public Config(String text) throws Exception {
    raw = new JSONObject(text);
    if (raw.getInt("schema_version") != 1) throw new IllegalArgumentException("schema_version");
    enabled = raw.getBoolean("enabled");
    filters = raw.getJSONObject("filters");
    wecom = raw.getJSONObject("wecom");
    smtp = raw.getJSONObject("smtp");
    logging = raw.getJSONObject("logging");
    retention = raw.getJSONObject("retention");
    for (String k : new String[] {"sim_slots", "numbers", "keywords"}) filters.getJSONArray(k);
    JSONArray slots = filters.getJSONArray("sim_slots");
    for (int i = 0; i < slots.length(); i++)
      if (slots.getInt(i) < 1 || slots.getInt(i) > 8)
        throw new IllegalArgumentException("sim_slots");
    JSONArray a = raw.getJSONArray("retry_seconds");
    if (a.length() > 8) throw new IllegalArgumentException("retry_seconds");
    retry = new int[a.length()];
    for (int i = 0; i < retry.length; i++) {
      retry[i] = a.getInt(i);
      if (retry[i] < 1 || retry[i] > 86400) throw new IllegalArgumentException("retry_seconds");
    }
    JSONObject t = raw.getJSONObject("timeouts");
    connect = bounded(t, "connect_seconds", 1, 60);
    io = bounded(t, "io_seconds", 1, 60);
    total = bounded(t, "total_seconds", 5, 180);
    if (wecom.getBoolean("enabled")) {
      URI u = new URI(wecom.getString("webhook"));
      if (!"https".equals(u.getScheme())
          || !"qyapi.weixin.qq.com".equals(u.getHost())
          || !"/cgi-bin/webhook/send".equals(u.getPath())
          || u.getUserInfo() != null
          || u.getFragment() != null
          || u.getPort() != -1
          || u.getRawQuery() == null
          || !u.getRawQuery().matches("key=[A-Za-z0-9-]+"))
        throw new IllegalArgumentException("wecom.webhook");
    }
    if (smtp.getBoolean("enabled")) {
      String mode = smtp.getString("tls");
      if (!mode.equals("implicit") && !mode.equals("starttls"))
        throw new IllegalArgumentException("smtp.tls");
      bounded(smtp, "port", 1, 65535);
      for (String k : new String[] {"host", "username", "password", "from"})
        if (smtp.getString(k).isEmpty() || smtp.getString(k).matches("(?s).*[\r\n].*"))
          throw new IllegalArgumentException("smtp." + k);
      if (!smtp.getString("host").matches("[A-Za-z0-9.-]+"))
        throw new IllegalArgumentException("smtp.host");
      javax.mail.internet.InternetAddress from =
          new javax.mail.internet.InternetAddress(smtp.getString("from"), true);
      from.validate();
      JSONArray to = smtp.getJSONArray("to");
      if (to.length() == 0 || to.length() > 20) throw new IllegalArgumentException("smtp.to");
      for (int i = 0; i < to.length(); i++) {
        javax.mail.internet.InternetAddress addr =
            new javax.mail.internet.InternetAddress(to.getString(i), true);
        addr.validate();
      }
    }
    if (enabled && !wecom.getBoolean("enabled") && !smtp.getBoolean("enabled"))
      throw new IllegalArgumentException("no enabled channel");
    if (!Arrays.asList("DEBUG", "INFO", "WARN", "ERROR").contains(logging.getString("level")))
      throw new IllegalArgumentException("logging.level");
    if (!Arrays.asList(TimeZone.getAvailableIDs()).contains(logging.getString("timezone")))
      throw new IllegalArgumentException("logging.timezone");
    bounded(logging, "days", 1, 30);
    int max = bounded(logging, "max_bytes", 65536, 16777216);
    if (bounded(logging, "trim_bytes", 32768, max) >= max)
      throw new IllegalArgumentException("logging.trim_bytes");
    bounded(retention, "dedup_days", 1, 365);
    bounded(retention, "max_messages", 1000, 100000);
    bounded(retention, "completed_body_hours", 0, 168);
  }

  static int bounded(JSONObject j, String key, int min, int max) throws Exception {
    int n = j.getInt(key);
    if (n < min || n > max) throw new IllegalArgumentException(key);
    return n;
  }

  public boolean accepts(int slot, String address, String body) throws Exception {
    return matches(filters.getJSONArray("sim_slots"), "" + slot, false)
        && matches(filters.getJSONArray("numbers"), address, false)
        && matches(filters.getJSONArray("keywords"), body, true);
  }

  static boolean matches(JSONArray a, String value, boolean contains) throws Exception {
    if (a.length() == 0) return true;
    for (int i = 0; i < a.length(); i++) {
      String s = a.getString(i);
      if (contains ? value.contains(s) : value.equals(s)) return true;
    }
    return false;
  }

  public static String read(File f) throws IOException {
    try (FileInputStream in = new FileInputStream(f)) {
      if (f.length() > 262144) throw new IOException("config too large");
      ByteArrayOutputStream b = new ByteArrayOutputStream();
      byte[] x = new byte[4096];
      int n;
      while ((n = in.read(x)) != -1) b.write(x, 0, n);
      return b.toString("UTF-8");
    }
  }

  public static Config load(File home) throws Exception {
    return new Config(read(new File(home, "config.json")));
  }
}
