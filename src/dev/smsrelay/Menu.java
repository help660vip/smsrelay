package dev.smsrelay;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class Menu {
  static final String[] labels = {
    "查看服务状态 / SIM 识别状态",
    "启动服务",
    "停止服务",
    "重启服务（保留队列）",
    "检查并重载配置",
    "企业微信固定测试消息",
    "SMTP 固定测试消息",
    "查看队列",
    "手动重试失败任务",
    "最近日志",
    "仅 WARN / ERROR",
    "导出脱敏诊断",
    "手动维护日志（保留待发短信）",
    "退出"
  };
  static final String[] commands = {
    "status",
    "start",
    "stop",
    "restart",
    "reload",
    "test-wecom",
    "test-smtp",
    "queue",
    "retry",
    "logs",
    "warnings",
    "export",
    "maintain",
    "exit"
  };

  public static void run(File home) throws Exception {
    Process capabilities = new ProcessBuilder("/system/bin/getevent", "-lp").start();
    List<String> devices = new ArrayList<>();
    String current = null;
    boolean volume = false;
    try (BufferedReader r =
        new BufferedReader(new InputStreamReader(capabilities.getInputStream()))) {
      String line;
      while ((line = r.readLine()) != null) {
        if (line.startsWith("add device")) {
          if (current != null && volume) devices.add(current);
          current = line.substring(line.indexOf(": ") + 2).trim();
          volume = false;
        }
        if (line.contains("KEY_VOLUMEUP") || line.contains("KEY_VOLUMEDOWN")) volume = true;
      }
    }
    if (current != null && volume) devices.add(current);
    capabilities.waitFor();
    if (devices.isEmpty()) {
      System.out.println("找不到音量按键设备；请使用模块 smsrelay 命令操作。");
      return;
    }
    BlockingQueue<String> keys = new LinkedBlockingQueue<>(32);
    List<Process> inputs = new ArrayList<>();
    List<Thread> readers = new ArrayList<>();
    java.util.concurrent.atomic.AtomicInteger open =
        new java.util.concurrent.atomic.AtomicInteger(devices.size());
    for (String path : devices) {
      // AOSP getevent accepts one device path, so own one reader per key device.
      Process input = new ProcessBuilder("/system/bin/getevent", "-ql", path).start();
      inputs.add(input);
      Thread reader =
          new Thread(
              () -> {
                try (BufferedReader r =
                    new BufferedReader(new InputStreamReader(input.getInputStream()))) {
                  String line;
                  while ((line = r.readLine()) != null) {
                    if (isPress(line)) {
                      if (line.contains("KEY_VOLUMEUP")) keys.offer("up");
                      if (line.contains("KEY_VOLUMEDOWN")) keys.offer("down");
                    }
                  }
                } catch (Exception ignored) {
                } finally {
                  if (open.decrementAndGet() == 0) keys.offer("closed");
                }
              },
              "smsrelay-menu-input");
      reader.setDaemon(true);
      reader.start();
      readers.add(reader);
    }
    int index = 0;
    try {
      show(index);
      while (true) {
        String key = keys.poll(60, TimeUnit.SECONDS);
        if (key == null || key.equals("closed")) break;
        if (key.equals("up")) {
          index = (index + 1) % labels.length;
          show(index);
          continue;
        }
        String cmd = commands[index];
        if (cmd.equals("exit")) break;
        try {
          if (cmd.equals("start")) {
            Main.start(home);
            System.out.println(Main.await(home));
          } else if (cmd.equals("restart")) {
            try {
              Control.request(home, "stop");
            } catch (IOException ignored) {
            }
            Main.waitStopped(home);
            Main.start(home);
            System.out.println(Main.await(home));
          } else {
            try {
              System.out.println(Control.request(home, cmd));
            } catch (IOException offline) {
              System.out.println(Main.offline(home, cmd));
            }
          }
        } catch (Exception e) {
          System.out.println("操作失败：" + e.getClass().getSimpleName() + "；原配置和待发队列保留。");
        }
        show(index);
      }
    } finally {
      for (Process input : inputs) input.destroy();
      for (Thread reader : readers) reader.join(1000);
      System.out.println("菜单已退出，音量键监听已清理。");
    }
  }

  static boolean isPress(String line) {
    return line.contains("EV_KEY") && line.trim().endsWith(" DOWN");
  }

  static void show(int index) {
    System.out.println(
        "\n=== smsrelay ===\n音量上：切换  音量下：执行  60 秒空闲退出\n"
            + (index + 1)
            + "/"
            + labels.length
            + "  "
            + labels[index]);
  }
}
