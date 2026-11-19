package dev.smsrelay;

import android.app.Notification;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;

/** 只输出指定合成通知与响铃状态，不输出其他通知和来电号码。 */
public final class EventProbe {
  public static void main(String[] args) throws Exception {
    boolean callsOnly = args.length > 0 && args[0].equals("--calls");
    Looper.prepareMainLooper();
    Handler h = new Handler(Looper.myLooper());
    PlatformBridge bridge = new PlatformBridge();
    Device d = new Device(h);
    d.refreshSims();
    EventSources events = new EventSources(d, new EventSources.Sink() {
      @Override public void ringing(int sub, String number) {
        Device.Sim sim = d.sim(sub);
        System.out.println("RINGING slot=" + sim.slot + " caller_available="
            + (number != null && !number.trim().isEmpty()) + " own_available=" + !sim.number.equals("未知"));
        if (callsOnly) h.postDelayed(() -> Looper.myLooper().quitSafely(),1000);
      }
      @Override public void posted(StatusBarNotification n) {
        CharSequence text = n.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT);
        if (text != null && text.toString().equals("SMSRELAY_EVENT_PROBE"))
          System.out.println("NOTIFICATION_PROBE_RECEIVED");
      }
      @Override public void removed(StatusBarNotification n) {}
    });
    try {
      events.configure(true, !callsOnly);
      h.postDelayed(() -> System.out.println("PROBE call=" + events.callState + " notification=" +
          events.notificationState + " active_sims=" + d.sims.size() + " ready_sims=" +
          events.readySubscriptions() + " caller_id_mode=" + events.callerIdSubscriptions()),1500);
      h.postDelayed(() -> {
        Exception error = events.callError != null ? events.callError : events.callerIdError;
        if (error != null) error.printStackTrace();
      },1800);
      h.postDelayed(() -> Looper.myLooper().quitSafely(), callsOnly ? 90000 : 8500);
      Looper.loop();
    } finally {
      events.close();
      d.close();
      bridge.close();
    }
  }
}
