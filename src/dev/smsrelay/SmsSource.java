package dev.smsrelay;

import android.database.*;
import android.os.*;
import java.util.*;

public final class SmsSource implements AutoCloseable {
  public interface Input {
    Cursor query(String[] columns, String where, String[] args, String sort) throws Exception;

    void observe(ContentObserver observer) throws Exception;

    void death(Runnable callback) throws Exception;

    void close() throws Exception;

    String branch();

    long queries();
  }

  static final class BridgeInput implements Input {
    final PlatformBridge b;

    BridgeInput() throws Exception {
      b = new PlatformBridge();
    }

    public Cursor query(String[] c, String w, String[] a, String s) throws Exception {
      return b.query(c, w, a, s);
    }

    public void observe(ContentObserver o) throws Exception {
      b.observe(o);
    }

    public void death(Runnable r) throws Exception {
      ((android.os.IBinder) PlatformBridge.invoke(b.providerObject(), "asBinder"))
          .linkToDeath(() -> r.run(), 0);
    }

    public void close() throws Exception {
      b.close();
    }

    public String branch() {
      return b.branch();
    }

    public long queries() {
      return b.queries;
    }
  }

  private final Input bridge;
  private final Handler handler;
  private final StateDb db;
  private final Device device;
  private final Logger log;
  private final Runnable queued;
  private android.os.PowerManager.WakeLock eventWake;
  private Config cfg;
  private boolean scheduled, registered;
  public long callbacks, scans, accepted;
  private final ContentObserver observer;

  public SmsSource(Handler h, StateDb d, Device dev, Logger l, Config c, Runnable after)
      throws Exception {
    this(h, d, dev, l, c, after, new BridgeInput());
  }

  public SmsSource(
      Handler h, StateDb d, Device dev, Logger l, Config c, Runnable after, Input input)
      throws Exception {
    handler = h;
    db = d;
    device = dev;
    log = l;
    cfg = c;
    bridge = input;
    queued =
        () -> {
          scheduled = false;
          try {
            scan();
            after.run();
          } catch (Exception e) {
            log.error("source_scan", e);
          } finally {
            releaseEventWake();
          }
        };
    observer =
        new ContentObserver(h) {
          @Override
          public void onChange(boolean self) {
            callbacks++;
            requestScan();
          }
        };
    bridge.observe(observer);
    registered = true;
    bridge.death(
        () ->
            handler.post(
                () -> {
                  registered = false;
                  log.log("ERROR", "sms_provider_died; supervisor recovery");
                  System.exit(70);
                }));
    log.log("INFO", "observer_registered " + bridge.branch());
    scan();
  }

  public void configure(Config c) {
    cfg = c;
  }

  public void requestScan() {
    if (!scheduled) {
      scheduled = true;
      if (device != null) eventWake = device.wake(15000);
      handler.postDelayed(queued, 200);
    }
  }

  private List<StateDb.Sms> read(String where, String[] args, String sort) throws Exception {
    ArrayList<StateDb.Sms> list = new ArrayList<>();
    try (Cursor c =
        bridge.query(
            new String[] {"_id", "date", "sub_id", "address", "body"}, where, args, sort)) {
      if (c == null) throw new IllegalStateException("SMS unavailable; unlock and restart");
      while (c.moveToNext()) {
        StateDb.Sms s = new StateDb.Sms();
        s.providerId = c.getLong(0);
        s.date = c.getLong(1);
        s.sub = c.getInt(2);
        s.address = c.isNull(3) ? "" : c.getString(3);
        s.body = c.isNull(4) ? "" : c.getString(4);
        Device.Sim sim = device == null ? new Device.Sim() : device.sim(s.sub);
        s.slot = sim.slot;
        s.number = sim.number;
        s.carrier = sim.carrier;
        list.add(s);
      }
    }
    return list;
  }

  public void scan() throws Exception {
    android.os.PowerManager.WakeLock capture = device == null ? null : device.wake(15000);
    try {
      performScan();
    } finally {
      if (capture != null && capture.isHeld()) capture.release();
    }
  }

  private void releaseEventWake() {
    if (eventWake != null && eventWake.isHeld()) eventWake.release();
    eventWake = null;
  }

  private void performScan() throws Exception {
    scans++;
    boolean first = !db.get("initialized", "false").equals("true");
    long high = db.value("max_id"), now = System.currentTimeMillis();
    long snapshot = 0;
    try (Cursor top = bridge.query(new String[] {"_id"}, null, null, "_id DESC LIMIT 1")) {
      if (top != null && top.moveToFirst()) snapshot = top.getLong(0);
    }
    // Finite overlap catches changed IDs/rebuilt databases; no timer runs this query.
    List<StateDb.Sms> rows =
        first
            ? read("date >= ?", new String[] {"" + (now - 172800000)}, "_id ASC")
            : read(
                "_id > ? OR date >= ?",
                new String[] {"" + high, "" + (now - 172800000)},
                "_id ASC");
    rows.addAll(read(null, null, "date DESC, _id DESC LIMIT 512"));
    long max = snapshot;
    int added = 0;
    db.begin();
    try {
      for (StateDb.Sms s : rows) if (db.add(s, cfg, first && s.providerId <= snapshot)) added++;
      db.put("initialized", "true");
      db.put("max_id", "" + max);
      db.put("capture_wall", "" + now);
      db.commit();
    } finally {
      db.end();
    }
    accepted += added;
    if (first) log.log("INFO", "baseline_initialized existing_sms_skipped=true");
    if (added > 0) log.log("INFO", "enqueued messages=" + added);
  }

  public String branch() {
    return bridge.branch();
  }

  public boolean registered() {
    return registered;
  }

  public long queries() {
    return bridge.queries();
  }

  public void close() throws Exception {
    handler.removeCallbacks(queued);
    releaseEventWake();
    bridge.close();
    registered = false;
  }
}
