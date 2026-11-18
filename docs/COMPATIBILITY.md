# Android / ROM 兼容矩阵

设计范围为 API 25—36；当前产品交付优先保证 K40 Android 13。最低 DEX API 25 是构建设置，不等于已在 Android 7.1 运行通过。

| API | 系统版本 | 查询分支 | 官方源码核对 | 运行验证 |
| --- | --- | --- | --- | --- |
| 25 | Android 7.1 | calling package + selection / args / sort | 对应 content 命令已读 | 未运行 |
| 26 | Android 8.0 | calling package + Bundle | 对应 content 命令已读 | 未运行 |
| 27—28 | Android 8.1—9 | calling package + Bundle | 按分界适配，未逐版本核对全部方法 | 未运行 |
| 29 | Android 10 | calling package + Bundle；external provider 增加 tag | 对应 content 命令已读 | 未运行 |
| 30 | Android 11 | calling package + attributionTag + Bundle | 对应 content 命令已读 | 未运行 |
| 31 | Android 12 | AttributionSource(uid 0, root) | 对应 content 命令已读 | 未运行 |
| 32 | Android 12L | AttributionSource | 按同分支适配 | 未运行 |
| 33 | Android 13 | AttributionSource；Provider Cursor 通知桥 | 已核对源码及设备实际方法 | **K40 HyperOS 1.0.10.0 实测** |
| 34—35 | Android 14—15 | AttributionSource | 按同分支适配，非完整逐方法验证 | 未运行 |
| 36 | Android 16 | AttributionSource | 对应 content 命令已读 | 未运行 |

API 25 用 ActivityManagerNative.getDefault，API 26 以上用 ActivityManager.getService。getContentProviderExternal 按参数分支调用，API 29 起携带 tag；旧系统使用 removeContentProviderExternal，新系统使用 AsUser。Binder 方法通过设备框架生成的封装与反射匹配，不硬编码 transaction 编号。

远程 Cursor 通知路径复用系统 ContentObserver 与 BulkCursor 封装，不伪造已安装 APK Context。网络和 PowerManager 使用真实 root 包标识与 uid 0；AttributionSource 新类型隔离在较新版本实现中。

模块不携带原生程序，ARM32、ARM64、x86 和 x86_64 共用 DEX，不存在本模块自带 ELF 的 ABI 或 16 KB 页面链接问题。实际只在 ARM64 K40 运行过，其他 ABI 的 app_process／框架适配仍未验证。

Java API 与依赖：没有 java.time、WorkManager、JobService 或 Manifest Receiver。Android 专用 JavaMail／Activation 1.6.7 上游最低 API 19。设备自测中的 String.join 需要较新系统，完整自测目前仅面向当前 API 33；不能用自测产物的 min-api 代替运行兼容验证。

参考源码：

- [Android 7.1 Content.java](https://android.googlesource.com/platform/frameworks/base/+/android-7.1.0_r1/cmds/content/src/com/android/commands/content/Content.java)
- [Android 8 Content.java](https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/cmds/content/src/com/android/commands/content/Content.java)
- [Android 10 Content.java](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/cmds/content/src/com/android/commands/content/Content.java)
- [Android 11 Content.java](https://android.googlesource.com/platform/frameworks/base/+/android-11.0.0_r1/cmds/content/src/com/android/commands/content/Content.java)
- [Android 12 Content.java](https://android.googlesource.com/platform/frameworks/base/+/android-12.0.0_r1/cmds/content/src/com/android/commands/content/Content.java)
- [Android 13 Content.java](https://android.googlesource.com/platform/frameworks/base/+/android-13.0.0_r1/cmds/content/src/com/android/commands/content/Content.java)
- [Android 16 Content.java](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/cmds/content/src/com/android/commands/content/Content.java)

这些是接口核对依据；ROM 的权限、私有收件箱和节电行为仍以真机证据为准。
