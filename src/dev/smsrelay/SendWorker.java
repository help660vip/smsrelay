package dev.smsrelay;

import com.sun.mail.smtp.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.mail.*;
import javax.mail.internet.*;
import org.json.*;

/** Short lived process; parent kills the whole network worker at the hard deadline. */
public final class SendWorker {
  public static void run() {
    JSONObject result = new JSONObject();
    try {
      String line = new BufferedReader(new InputStreamReader(System.in, "UTF-8")).readLine();
      if (line == null || line.length() > 1048576) throw new IOException("input size");
      JSONObject job = new JSONObject(line);
      Config cfg = new Config(job.getString("settings"));
      Thread timeout =
          new Thread(
              () -> {
                try {
                  Thread.sleep(cfg.total * 1000L + 2000);
                } catch (InterruptedException ignored) {
                }
                System.exit(124);
              },
              "smsrelay-worker-deadline");
      timeout.setDaemon(true);
      timeout.start();
      if (job.getString("channel").equals("wecom")) wecom(cfg, job.getString("payload"));
      else smtp(cfg, job.getString("payload"), job.getString("message_id"));
      result.put("success", true).put("permanent", false).put("code", "server_accepted");
    } catch (Exception e) {
      boolean permanent =
          e instanceof AuthenticationFailedException
              || e instanceof IllegalArgumentException
              || e instanceof JSONException;
      int status = 0;
      if (e instanceof Rejected) {
        permanent = ((Rejected) e).permanent;
        status = ((Rejected) e).code;
      }
      Exception ex = e;
      for (int i = 0; i < 8 && ex != null; i++) {
        if (ex instanceof SMTPAddressFailedException)
          permanent = ((SMTPAddressFailedException) ex).getReturnCode() >= 500;
        if (ex instanceof SMTPSendFailedException)
          permanent = ((SMTPSendFailedException) ex).getReturnCode() >= 500;
        if (ex instanceof AuthenticationFailedException) permanent = true;
        ex = ex instanceof MessagingException ? ((MessagingException) ex).getNextException() : null;
      }
      try {
        result
            .put("success", false)
            .put("permanent", permanent)
            .put("code", e.getClass().getSimpleName() + (status != 0 ? "_" + status : ""));
      } catch (Exception ignored) {
      }
    }
    System.out.println(result.toString());
    System.out.flush();
    System.exit(0);
  }

  static final class Rejected extends IOException {
    final boolean permanent;
    final int code;

    Rejected(boolean p, int c) {
      super("remote rejected");
      permanent = p;
      code = c;
    }
  }

  static void wecom(Config cfg, String payload) throws Exception {
    if (payload.getBytes(StandardCharsets.UTF_8).length > 2048)
      throw new IllegalArgumentException("wecom bytes");
    HttpURLConnection c =
        (HttpURLConnection) new URL(cfg.wecom.getString("webhook")).openConnection();
    c.setInstanceFollowRedirects(false);
    c.setConnectTimeout(cfg.connect * 1000);
    c.setReadTimeout(cfg.io * 1000);
    try {
      c.setRequestMethod("POST");
      c.setDoOutput(true);
      c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      byte[] data =
          new JSONObject()
              .put("msgtype", "text")
              .put("text", new JSONObject().put("content", payload))
              .toString()
              .getBytes(StandardCharsets.UTF_8);
      c.setFixedLengthStreamingMode(data.length);
      try (OutputStream out = c.getOutputStream()) {
        out.write(data);
      }
      int http = c.getResponseCode();
      if (http < 200 || http >= 300)
        throw new Rejected(http >= 400 && http < 500 && http != 408 && http != 429, http);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (InputStream in = c.getInputStream()) {
        byte[] b = new byte[2048];
        int n;
        while ((n = in.read(b)) != -1) {
          bytes.write(b, 0, n);
          if (bytes.size() > 65536) throw new IOException("response limit");
        }
      }
      JSONObject response = new JSONObject(bytes.toString("UTF-8"));
      int code = response.getInt("errcode");
      if (code != 0) throw new Rejected(code != 45009 && code != -1 && code != 45011, code);
    } finally {
      c.disconnect();
    }
  }

  static void smtp(Config cfg, String payload, String stableId) throws Exception {
    JSONObject s = cfg.smtp;
    Properties p = new Properties();
    String prefix = "mail.smtp.";
    p.setProperty(prefix + "auth", "true");
    p.setProperty(prefix + "ssl.enable", "" + s.getString("tls").equals("implicit"));
    p.setProperty(prefix + "starttls.enable", "" + s.getString("tls").equals("starttls"));
    p.setProperty(prefix + "starttls.required", "" + s.getString("tls").equals("starttls"));
    p.setProperty(prefix + "ssl.checkserveridentity", "true");
    p.setProperty(prefix + "ssl.protocols", "TLSv1.2");
    p.setProperty(prefix + "connectiontimeout", "" + (cfg.connect * 1000));
    p.setProperty(prefix + "timeout", "" + (cfg.io * 1000));
    p.setProperty(prefix + "writetimeout", "" + (cfg.io * 1000));
    p.setProperty(prefix + "quitwait", "false");
    javax.activation.MailcapCommandMap map = new javax.activation.MailcapCommandMap();
    map.addMailcap("text/plain;; x-java-content-handler=com.sun.mail.handlers.text_plain");
    javax.activation.CommandMap.setDefaultCommandMap(map);
    Session session = Session.getInstance(p);
    session.setDebug(false);
    MimeMessage m =
        new MimeMessage(session) {
          @Override
          protected void updateMessageID() throws MessagingException {
            setHeader("Message-ID", "<smsrelay." + stableId + "@smsrelay.local>");
          }
        };
    m.setFrom(new InternetAddress(s.getString("from"), true));
    JSONArray to = s.getJSONArray("to");
    InternetAddress[] recipients = new InternetAddress[to.length()];
    for (int i = 0; i < to.length(); i++)
      recipients[i] = new InternetAddress(to.getString(i), true);
    m.setRecipients(Message.RecipientType.TO, recipients);
    m.setSubject(cfg.raw.optString("message_subject", "smsrelay 新短信"), "UTF-8");
    m.setSentDate(new Date());
    m.setText(payload, "UTF-8");
    m.saveChanges();
    SMTPTransport transport =
        new SMTPTransport(
            session, new URLName("smtp", s.getString("host"), s.getInt("port"), null, null, null));
    try {
      transport.connect(
          s.getString("host"), s.getInt("port"), s.getString("username"), s.getString("password"));
      transport.sendMessage(m, recipients);
    } finally {
      try {
        transport.close();
      } catch (Exception ignored) {
      }
    }
  }
}
