package dev.smsrelay;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import java.util.ArrayList;
import java.util.List;

/** 系统事件在服务线程串行处理，关闭 WebUI 不影响监听。 */
final class EventSources implements AutoCloseable {
  interface Sink {
    void ringing(int subscription, String number);
    void posted(StatusBarNotification notification);
    void removed(StatusBarNotification notification);
  }

  final Device device;
  final Sink sink;
  private final List<CallListener> calls = new ArrayList<>();
  private Object registry, numberRegistry;
  private int[] numberEvents;
  private java.lang.reflect.Field numberCallback;
  private int callGeneration;
  private NotificationListenerService notifications;
  String callState = "disabled";
  Exception callError, callerIdError;
  String notificationState = "disabled";

  EventSources(Device device, Sink sink) {
    this.device = device;
    this.sink = sink;
  }

  void configure(boolean callEnabled, boolean notifyEnabled) {
    if (!callEnabled) closeCalls();
    else if (calls.isEmpty() || callState.startsWith("unavailable")) refreshCalls();
    if (!notifyEnabled) closeNotifications();
    else if (notifications == null || notificationState.equals("disconnected")) {
      closeNotifications();
      startNotifications();
    }
  }

  void refreshCalls() {
    closeCalls();
    callError = null;
    callerIdError = null;
    try {
      if (Build.VERSION.SDK_INT >= 31) {
        registry = Class.forName("android.telephony.TelephonyRegistryManager")
            .getConstructor(Context.class).newInstance(device.context);
      }
      List<Integer> subscriptions = new ArrayList<>(device.sims.keySet());
      if (subscriptions.isEmpty()) subscriptions.add(Integer.MAX_VALUE);
      for (int id : subscriptions) {
        CallListener listener = new CallListener(id);
        if (Build.VERSION.SDK_INT < 31) {
          TelephonyManager manager = TelephonyManager.class.getConstructor(Context.class)
              .newInstance(device.context);
          listener.manager = id == Integer.MAX_VALUE ? manager : manager.createForSubscriptionId(id);
        }
        calls.add(listener);
        listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
      }
      callState = "connecting";
      int generation = callGeneration;
      new android.os.Handler(device.context.getMainLooper()).postDelayed(() -> {
        if (generation == callGeneration && callState.equals("connecting"))
          tryStateOnlyAfterTimeout(generation);
      },5000);
    } catch (Exception | LinkageError e) {
      if (e instanceof Exception) callError = (Exception)e;
      closeCalls();
      callState = "unavailable:" + e.getClass().getSimpleName();
    }
  }

  private void tryStateOnlyAfterTimeout(int generation) {
    callState = "unavailable:callback_timeout";
    boolean fallingBack = false;
    for (CallListener listener : calls) {
      if (listener.initialized || !listener.legacy) continue;
      try {
        listenWithNumber(listener,PhoneStateListener.LISTEN_NONE);
        listener.legacy = false;
        listenStateOnly(listener);
        fallingBack = true;
      } catch (Exception e) {
        callerIdError = e;
      }
    }
    if (fallingBack) {
      callState = "connecting";
      new android.os.Handler(device.context.getMainLooper()).postDelayed(() -> {
        if (generation == callGeneration && callState.equals("connecting"))
          callState = "unavailable:callback_timeout";
      },5000);
    }
  }

  private void listen(CallListener listener, int flags) throws Exception {
    if (registry == null) {
      listener.manager.listen(listener,flags);
      return;
    }
    if (flags == PhoneStateListener.LISTEN_NONE) {
      if (listener.legacy) listenWithNumber(listener,flags);
      else if (listener.modern != null)
        PlatformBridge.invoke(registry,"unregisterTelephonyCallback",listener.subscription,
            "root",null,listener.modern,false);
      return;
    }
    try {
      // 沿用系统 Binder 封装和 APKless root 身份，订阅带号码的旧事件。
      listenWithNumber(listener,flags);
      listener.legacy = true;
      return;
    } catch (Exception legacyUnavailable) {
      if (afterRegistrationAppOpsReport(legacyUnavailable)) {
        // HyperOS 在读取 Binder 回包的 AppOps 诊断信息时抛错。注册是否成功以
        // 系统初始回调为准；超时后再切回仅状态监听。
        listener.legacy = true;
        return;
      }
      callerIdError = legacyUnavailable;
      try { listenWithNumber(listener,PhoneStateListener.LISTEN_NONE); }
      catch (Exception ignored) {}
    }
    listenStateOnly(listener);
  }

  private static boolean afterRegistrationAppOpsReport(Exception error) {
    if (!(error instanceof NullPointerException)) return false;
    for (StackTraceElement frame : error.getStackTrace()) {
      if (frame.getClassName().equals("android.app.AppOpsManager$1")
          && frame.getMethodName().equals("reportStackTraceIfNeeded")) return true;
    }
    return false;
  }

  private void listenStateOnly(CallListener listener) throws Exception {
    listener.modern = Api31.create(listener);
    android.os.Handler main = new android.os.Handler(device.context.getMainLooper());
    PlatformBridge.invoke(registry,"registerTelephonyCallback",true,true,
        (java.util.concurrent.Executor)main::post, listener.subscription, "root",null,listener.modern,true);
  }

  private void listenWithNumber(CallListener listener, int flags) throws Exception {
    boolean enable = flags != PhoneStateListener.LISTEN_NONE;
    if (numberCallback == null) {
      numberRegistry = Device.service("telephony.registry","com.android.internal.telephony.ITelephonyRegistry");
      java.lang.reflect.Field event = Class.forName("android.telephony.TelephonyCallback")
          .getDeclaredField("EVENT_LEGACY_CALL_STATE_CHANGED");
      event.setAccessible(true);
      numberEvents = new int[]{event.getInt(null)};
      java.lang.reflect.Field callback = PhoneStateListener.class.getDeclaredField("callback");
      callback.setAccessible(true);
      numberCallback = callback;
    }
    PlatformBridge.invoke(numberRegistry,"listenWithEventList",true,true,listener.subscription,
        "root",null,numberCallback.get(listener),enable?numberEvents:new int[0],enable);
  }

  private static final class Api31 extends android.telephony.TelephonyCallback
      implements android.telephony.TelephonyCallback.CallStateListener {
    private final CallListener listener;
    private Api31(CallListener listener) { this.listener = listener; }
    static Object create(CallListener listener) { return new Api31(listener); }
    @Override public void onCallStateChanged(int state) { listener.onCallStateChanged(state,null); }
  }

  int readySubscriptions() {
    int ready = 0;
    for (CallListener call : calls) if (call.initialized) ready++;
    return ready;
  }

  int watchedSubscriptions() { return calls.size(); }

  int callerIdSubscriptions() {
    int count = 0;
    for (CallListener call : calls) if (call.legacy || call.manager != null) count++;
    return count;
  }

  private final class CallListener extends PhoneStateListener {
    final int subscription;
    TelephonyManager manager;
    Object modern;
    boolean initialized, ringing, legacy;
    CallListener(int subscription) { this.subscription = subscription; }

    @Override public void onCallStateChanged(int state, String number) {
      if (!calls.contains(this)) return;
      boolean next = state == TelephonyManager.CALL_STATE_RINGING;
      boolean newlyRinging = initialized && next && !ringing;
      initialized = true;
      ringing = next;
      if (readySubscriptions() == watchedSubscriptions()) callState = "ready";
      if (newlyRinging) sink.ringing(subscription, number);
    }
  }

  private void startNotifications() {
    NotificationListenerService listener = new NotificationListenerService() {
      @Override public void onListenerConnected() {
        if (notifications == this) notificationState = "ready";
      }
      @Override public void onListenerDisconnected() {
        if (notifications == this) notificationState = "disconnected";
      }
      @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (notifications == this && "ready".equals(notificationState)) sink.posted(sbn);
      }
      @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        if (notifications == this) sink.removed(sbn);
      }
    };
    try {
      notificationState = "connecting";
      notifications = listener;
      PlatformBridge.invoke(listener, "registerAsSystemService", device.context,
          new ComponentName("dev.smsrelay", "NotificationRelay"), 0);
      new android.os.Handler(device.context.getMainLooper()).postDelayed(() -> {
        if(notifications==listener && notificationState.equals("connecting")) {
          closeNotifications();
          notificationState="unavailable:callback_timeout";
        }
      },5000);
    } catch (Exception | LinkageError e) {
      closeNotifications();
      notificationState = "unavailable:" + e.getClass().getSimpleName();
    }
  }

  private void closeCalls() {
    callGeneration++;
    for (CallListener call : calls) {
      try { listen(call, PhoneStateListener.LISTEN_NONE); } catch (Exception ignored) {}
    }
    calls.clear();
    registry = null;
    callState = "disabled";
  }

  private void closeNotifications() {
    NotificationListenerService listener = notifications;
    notifications = null;
    notificationState = "disabled";
    if (listener != null) {
      try { PlatformBridge.invoke(listener, "unregisterAsSystemService"); }
      catch (Exception ignored) {}
    }
  }

  @Override public void close() {
    closeCalls();
    closeNotifications();
  }
}
