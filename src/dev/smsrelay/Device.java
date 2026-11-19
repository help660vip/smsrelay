package dev.smsrelay;

import android.content.*;
import android.content.pm.ApplicationInfo;
import android.net.*;
import android.os.*;
import android.telephony.*;
import java.lang.reflect.*;
import java.util.*;

/** 使用 root 身份访问系统服务，模块无需依附已安装应用的 Context。 */
public final class Device {
  public final Context context;
  public final ConnectivityManager network;
  public final PowerManager power;
  private final Handler handler;
  private Object subService, registry;
  private SubscriptionManager.OnSubscriptionsChangedListener subListener;
  public final Map<Integer, Sim> sims = new HashMap<>();
  public String simStatus = "not read";

  public static final class Sim {
    public int id, slot;
    public String number = "未知", carrier = "未知";
  }

  static class RootContext extends ContextWrapper {
    RootContext(Context base) {
      super(base);
    }

    @Override
    public String getPackageName() {
      return "root";
    }

    @Override
    public String getOpPackageName() {
      return "root";
    }

    @Override
    public String getAttributionTag() {
      return null;
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
      ApplicationInfo a = new ApplicationInfo();
      a.uid = 0;
      a.packageName = "root";
      a.targetSdkVersion = Build.VERSION.SDK_INT;
      return a;
    }
  }

  static final class RootContext31 extends RootContext {
    RootContext31(Context base) {
      super(base);
    }

    @Override
    public android.content.AttributionSource getAttributionSource() {
      return new android.content.AttributionSource.Builder(0).setPackageName("root").build();
    }
  }

  public static Object service(String name, String stub) throws Exception {
    Object binder =
        PlatformBridge.call(Class.forName("android.os.ServiceManager"), null, "getService", name);
    return PlatformBridge.call(Class.forName(stub + "$Stub"), null, "asInterface", binder);
  }

  public Device(Handler h) throws Exception {
    handler = h;
    Object thread =
        PlatformBridge.call(Class.forName("android.app.ActivityThread"), null, "systemMain");
    Context base = (Context) PlatformBridge.invoke(thread, "getSystemContext");
    context = Build.VERSION.SDK_INT >= 31 ? new RootContext31(base) : new RootContext(base);
    Object net = service("connectivity", "android.net.IConnectivityManager");
    network =
        (ConnectivityManager)
            Class.forName("android.net.ConnectivityManager")
                .getConstructor(Context.class, Class.forName("android.net.IConnectivityManager"))
                .newInstance(context, net);
    Object p = service("power", "android.os.IPowerManager");
    PowerManager pm = null;
    for (Constructor<?> ctor : PowerManager.class.getDeclaredConstructors()) {
      Class<?>[] types = ctor.getParameterTypes();
      if (types.length < 3 || types[0] != Context.class) continue;
      Object[] args = new Object[types.length];
      boolean supported = true;
      for (int i = 0; i < types.length; i++) {
        if (types[i] == Context.class) args[i] = context;
        else if (types[i] == Handler.class) args[i] = h;
        else if (types[i].getName().equals("android.os.IPowerManager")) args[i] = p;
        else if (types[i].getName().equals("android.os.IThermalService"))
          args[i] = service("thermalservice", "android.os.IThermalService");
        else supported = false;
      }
      if (supported) {
        ctor.setAccessible(true);
        pm = (PowerManager) ctor.newInstance(args);
        break;
      }
    }
    if (pm == null) throw new IllegalStateException("Unsupported PowerManager constructor");
    power = pm;
    subService = service("isub", "com.android.internal.telephony.ISub");
  }

  public boolean online() {
    Network n = network.getActiveNetwork();
    if (n == null) return false;
    NetworkCapabilities c = network.getNetworkCapabilities(n);
    return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
  }

  public PowerManager.WakeLock wake(long timeout) {
    PowerManager.WakeLock w = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smsrelay:work");
    w.setReferenceCounted(false);
    w.acquire(timeout);
    return w;
  }

  public void refreshSims() {
    try {
      Object list;
      if (Build.VERSION.SDK_INT >= 30)
        list = PlatformBridge.invoke(subService, "getActiveSubscriptionInfoList", "root", null);
      else list = PlatformBridge.invoke(subService, "getActiveSubscriptionInfoList", "root");
      Map<Integer, Sim> next = new HashMap<>();
      if (list != null)
        for (Object row : (List<?>) list) {
          SubscriptionInfo info = (SubscriptionInfo) row;
          Sim s = new Sim();
          s.id = info.getSubscriptionId();
          s.slot = info.getSimSlotIndex() + 1;
          CharSequence carrier = info.getCarrierName();
          if (carrier != null && !carrier.toString().trim().isEmpty())
            s.carrier = carrier.toString();
          String number = info.getNumber();
          if (Build.VERSION.SDK_INT >= 33)
            try {
              String n =
                  (String)
                      PlatformBridge.invoke(
                          subService, "getPhoneNumberFromFirstAvailableSource", s.id, "root", null);
              if (n != null && !n.isEmpty()) number = n;
            } catch (Exception ignored) {
            }
          if (number != null && !number.trim().isEmpty()) s.number = number.trim();
          next.put(s.id, s);
        }
      sims.clear();
      sims.putAll(next);
      simStatus =
          "active="
              + sims.size()
              + " number_available="
              + sims.values().stream().filter(s -> !s.number.equals("未知")).count();
    } catch (Exception e) {
      sims.clear();
      simStatus = "unavailable:" + e.getClass().getSimpleName();
    }
  }

  public Sim sim(int id) {
    Sim s = sims.get(id);
    if (s != null) return s;
    Sim u = new Sim();
    u.id = id;
    u.slot = 0;
    return u;
  }

  Sim simAtSlot(int slot) {
    if (slot > 0) for (Sim sim : sims.values()) if (sim.slot == slot) return sim;
    return sim(-1);
  }

  public void watchSims(Runnable changed) throws Exception {
    subListener =
        new SubscriptionManager.OnSubscriptionsChangedListener() {
          @Override
          public void onSubscriptionsChanged() {
            handler.post(
                () -> {
                  refreshSims();
                  changed.run();
                });
          }
        };
    if (Build.VERSION.SDK_INT >= 31) {
      Class<?> cls = Class.forName("android.telephony.TelephonyRegistryManager");
      registry = cls.getConstructor(Context.class).newInstance(context);
      PlatformBridge.invoke(
          registry,
          "addOnSubscriptionsChangedListener",
          subListener,
          (java.util.concurrent.Executor) r -> handler.post(r));
    } else {
      registry =
          SubscriptionManager.class.getDeclaredConstructor(Context.class).newInstance(context);
      PlatformBridge.invoke(registry, "addOnSubscriptionsChangedListener", subListener);
    }
  }

  public void close() {
    if (registry != null && subListener != null)
      try {
        PlatformBridge.invoke(registry, "removeOnSubscriptionsChangedListener", subListener);
      } catch (Exception ignored) {
      }
  }
}
