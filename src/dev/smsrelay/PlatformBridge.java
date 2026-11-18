package dev.smsrelay;

import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.lang.reflect.*;

/** 使用系统框架生成的 Binder 接口，避免依赖固定的事务编号。 */
public final class PlatformBridge {
  public final int api = Build.VERSION.SDK_INT;
  private final Object am, provider;
  private final IBinder token = new Binder();
  private Cursor signalCursor;
  public long queries;

  public PlatformBridge() throws Exception {
    if (android.os.Process.myUid() != 0) throw new SecurityException("root required");
    if (api >= 28) {
      Class<?> vm = Class.forName("dalvik.system.VMRuntime");
      Object runtime = call(vm, null, "getRuntime");
      call(vm, runtime, "setHiddenApiExemptions", (Object) new String[] {"L"});
    }
    if (api >= 26) am = call(Class.forName("android.app.ActivityManager"), null, "getService");
    else am = call(Class.forName("android.app.ActivityManagerNative"), null, "getDefault");
    Object holder =
        api >= 29
            ? invoke(am, "getContentProviderExternal", "sms", 0, token, "smsrelay")
            : invoke(am, "getContentProviderExternal", "sms", 0, token);
    if (holder == null)
      throw new IllegalStateException("SMS Provider unavailable; unlock and restart");
    Field field = holder.getClass().getField("provider");
    provider = field.get(holder);
  }

  public static Object call(Class<?> type, Object obj, String name, Object... args)
      throws Exception {
    Method chosen = null;
    for (Method m : type.getMethods()) {
      if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) continue;
      Class<?>[] p = m.getParameterTypes();
      boolean fits = true;
      for (int i = 0; i < p.length; i++)
        if (args[i] != null && !box(p[i]).isInstance(args[i])) fits = false;
      if (fits) {
        chosen = m;
        break;
      }
    }
    if (chosen == null)
      throw new NoSuchMethodException(type.getName() + "." + name + "/" + args.length);
    chosen.setAccessible(true);
    try {
      return chosen.invoke(obj, args);
    } catch (InvocationTargetException e) {
      Throwable t = e.getCause();
      if (t instanceof Exception) throw (Exception) t;
      throw e;
    }
  }

  private static Class<?> box(Class<?> p) {
    if (p == int.class) return Integer.class;
    if (p == boolean.class) return Boolean.class;
    if (p == long.class) return Long.class;
    return p;
  }

  public static Object invoke(Object obj, String name, Object... args) throws Exception {
    return call(obj.getClass(), obj, name, args);
  }

  public Cursor query(String[] columns, String where, String[] args, String sort) throws Exception {
    return queryAt("content://sms/inbox", columns, where, args, sort);
  }

  public Cursor queryAt(String address, String[] columns, String where, String[] args, String sort)
      throws Exception {
    queries++;
    Uri uri = Uri.parse(address);
    if (api <= 25)
      return (Cursor) invoke(provider, "query", "root", uri, columns, where, args, sort, null);
    Bundle b = new Bundle();
    b.putString("android:query-arg-sql-selection", where);
    b.putStringArray("android:query-arg-sql-selection-args", args);
    b.putString("android:query-arg-sql-sort-order", sort);
    if (api < 30) return (Cursor) invoke(provider, "query", "root", uri, columns, b, null);
    if (api == 30) return (Cursor) invoke(provider, "query", "root", null, uri, columns, b, null);
    return (Cursor) invoke(provider, "query", Attribution.create(), uri, columns, b, null);
  }

  private static final class Attribution {
    static Object create() throws Exception {
      return Class.forName("android.content.AttributionSource")
          .getConstructor(int.class, String.class, String.class)
          .newInstance(0, "root", null);
    }
  }

  public void observe(ContentObserver observer) throws Exception {
    // 由短信 Provider 为远程 Cursor 注册观察器，通过 Binder 将变更送到 root 进程。
    // 保留空结果的 Cursor 就能持续收通知，读取正文由后续采集负责。
    signalCursor = query(new String[] {"_id"}, "_id = -1", null, null);
    if (signalCursor == null)
      throw new IllegalStateException("Provider cursor has no notification URI");
    signalCursor.registerContentObserver(observer);
  }

  public void close() throws Exception {
    if (signalCursor != null) signalCursor.close();
    if (api >= 29) invoke(am, "removeContentProviderExternalAsUser", "sms", token, 0);
    else invoke(am, "removeContentProviderExternal", "sms", token);
  }

  public Object providerObject() {
    return provider;
  }

  public String branch() {
    return "api="
        + api
        + " query="
        + (api <= 25 ? "legacy" : api < 30 ? "bundle" : api == 30 ? "tag" : "attribution-root")
        + " observer=provider-cursor uid="
        + android.os.Process.myUid();
  }
}
