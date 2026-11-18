# smsrelay

纯 Magisk 短信转发模块。无需 APK、LSPosed 或桌面应用。收到标准短信 Provider 的新收件短信后，转发到企业微信机器人和／或 SMTP 邮箱。号码、运营商和卡槽来自当前活动订阅，换卡时通过订阅事件更新。

当前主要交付目标是 **Redmi K40 / HyperOS 1.0.10.0 / Android 13（API 33）**。Android 7.1—16 是接口适配目标，不能据此认为所有系统或 ROM 已验证。详细证据见 [测试记录](docs/TESTING.md) 和 [兼容矩阵](docs/COMPATIBILITY.md)。

## 安装与启用

1. 下载或构建 `smsrelay-0.1.0.zip`，通过 Magisk“模块 → 从本地安装”。不需要安装 APK。
2. 首次配置模板位于 `/data/adb/smsrelay/config.json`，默认 `enabled: false`，两个渠道也默认关闭。
3. 用 root 文件管理器编辑配置，填写所需渠道，将对应 `enabled` 及顶层 `enabled` 设为 `true`。
4. 重启手机，或用模块“操作”菜单启动服务。状态必须显示 `observer_registered: true` 后才算启动成功。
5. 首次初始化跳过已有短信。未启用期间的短信也不会在启用后突然补发。

已经使用本模块的升级会保留配置、队列、去重记录和日志。安装程序不会把模板覆盖到现有配置上。

## 文件位置

| 内容 | 位置 |
| --- | --- |
| 用户配置 | `/data/adb/smsrelay/config.json` |
| 最近一次有效配置 | `/data/adb/smsrelay/config.active.json` |
| 事务队列、去重和采集进度 | `/data/adb/smsrelay/state.db` |
| 唯一正式日志 | `/data/adb/smsrelay/logs/service.log` |
| 维护元数据 | `/data/adb/smsrelay/logs/maintenance.json` |
| 运行锁、就绪信息和启动失败原因 | `/data/adb/smsrelay/run/` |
| 手动导出的脱敏诊断 | `/data/adb/smsrelay/exports/` |
| 模块程序 | `/data/adb/modules/smsrelay/` |

私有目录为 `0700`，配置和数据库为 `0600`。状态库包含待发正文和任务所需的目标配置，勿公开上传。诊断导出不包含配置、短信正文、完整本机号码或设备序列号。

## 配置

完整示例见 [默认配置](module/config/config.json)。所有配置都按 JSON 数据解析，不执行配置文件。

- `schema_version`：当前为 `1`。
- `enabled`：全局转发开关。关闭后保留监听、进度和已有队列，暂停发送。
- `filters.sim_slots`：用户可读卡槽号，例如 `[1,2]`。空列表匹配全部；卡槽不是 subscription ID。
- `filters.numbers`：发件人精确匹配列表。格式与 Provider 中地址一致。
- `filters.keywords`：正文包含任一关键词时匹配。
- 三类过滤条件之间是“且”，每类内部是“或”；空列表不限制。
- `wecom.enabled / webhook`：独立控制企业微信机器人。仅接受官方 HTTPS Webhook 地址，不跟随重定向。
- `smtp`：主机、端口、`tls`、用户名、密码／授权码、发件人、收件人列表。
- `smtp.tls`：`implicit` 为连接即 TLS（常见端口 465）；`starttls` 为强制 STARTTLS。两种模式都验证证书和主机名，不提供明文 SMTP 模式。
- `retry_seconds`：默认 `[30,120,600]`，即首次失败后最多三次重试。永久鉴权／参数错误不盲目重试。
- `timeouts`：连接、读写、整次调用超时，单位秒。总超时通过终止独立发送进程实施。
- `logging`：默认 INFO、Asia/Shanghai、七个自然日、2 MiB 上限，满后裁到约 1 MiB。
- `retention`：默认去重保留 30 日，最多 20000 条消息；所有任务成功后，正文在后续活动维护时按 24 小时策略移除。

系统提供的本机号码为空时显示“未知”，不会从短信发件人、ICCID 或其他卡猜号码。显示的运营商来自该 subscription 的 `carrierName`。物理换卡后的完整端到端测试仍需有实际换卡条件。

重载先校验完整配置，再原子保存有效配置并应用。重载失败保留当前内存配置；重启时若用户文件损坏，则尝试最近一次有效配置。首次配置损坏且无有效备份时停止启动。

## 操作菜单

Magisk“操作”执行 `action.sh`。音量上切换选项，音量下执行，约 60 秒无按键自动退出。按键设备只在菜单存续时监听，松开不重复执行。K40 的上下音量键分属两个输入设备，因此各有一个临时 `getevent` 子进程，退出时仅清理本次创建的子进程。

选项包括：状态、启动、停止、保留队列的重启、配置检查／重载、企业微信测试、SMTP 测试、队列、失败任务手动重试、最近日志、WARN／ERROR、导出诊断、日志维护、退出。

也可通过 root 终端操作：

```sh
su -c '/data/adb/modules/smsrelay/smsrelay status'
su -c '/data/adb/modules/smsrelay/smsrelay start'
su -c '/data/adb/modules/smsrelay/smsrelay stop'
su -c '/data/adb/modules/smsrelay/smsrelay restart'
su -c '/data/adb/modules/smsrelay/smsrelay check'
su -c '/data/adb/modules/smsrelay/smsrelay reload'
su -c '/data/adb/modules/smsrelay/smsrelay test-wecom'
su -c '/data/adb/modules/smsrelay/smsrelay test-smtp'
su -c '/data/adb/modules/smsrelay/smsrelay queue'
su -c '/data/adb/modules/smsrelay/smsrelay retry'
su -c '/data/adb/modules/smsrelay/smsrelay warnings'
su -c '/data/adb/modules/smsrelay/smsrelay export'
su -c '/data/adb/modules/smsrelay/smsrelay maintain'
```

失败任务的手动重试使用当前相同目标的新配置，例如修正 SMTP 端口或密码后补发。已经成功的目标不会重发；已移除或已改成其他地址的旧目标不会被静默重定向。

## 事件入口和低功耗

K40 的系统 ContentService 会拒绝未登记在 ActivityManager 的独立 root 进程直接注册观察器（`Failed to find PID`）。本模块使用系统框架的 **远程 Cursor 通知桥**：短信 Provider 的 Cursor 在 Provider 进程注册通知观察器，经 `CursorToBulkCursorAdaptor / BulkCursorToCursorAdaptor` 的 Binder 回调送达 root 进程，再触发本地 `ContentObserver`。持续保留一个空结果 Cursor，收到事件后才读新增收件短信。这条路径已通过 K40 真实来信验证。

不存在短信轮询、进程定时巡检、心跳、周期 ping 或持续唤醒锁。监督进程阻塞 `waitFor()`，只在子进程退出后执行有限恢复；十分钟窗口内第三次连续异常退出后停止。两个实例锁均使用 Java FileChannel 同一种跨进程锁机制。

重启恢复先建立观察器，再补查增量。新增 ID、最近 48 小时和最近 512 条的有限重叠补查配合稳定指纹，处理重复通知和低 ID。首次历史指纹也覆盖这些重叠范围。不会周期全库扫描。很旧且落在补查范围外的数据库恢复记录无法保证被发现；手动重建状态库会改变历史处理边界。

有待发任务时才注册网络回调；网络不可用时不消耗重试次数。退避使用 Handler 的非唤醒延迟，深睡会推迟重试时刻，不承诺准点唤醒。实际发送才持有 `smsrelay:work` 短时唤醒锁，带硬超时并在完成／取消路径释放。

## 队列、日志与恢复

完整任务、正文和采集进度在同一个 SQLite FULL 同步事务中提交，之后才发送。每个邮箱收件人、企业微信分段分别记录 PENDING、SENDING、SUCCEEDED、FAILED、次数和下次时间。启动时 SENDING 恢复为待处理；手动重启不清空队列。

企业微信按 UTF-8 字节分段，保留码点、顺序和段号；发送间隔至少约 3.1 秒。前段失败时后段标为阻塞失败，邮箱仍独立处理。企业微信同时校验 HTTP 状态和解析后的业务 errcode。SMTP 使用 Android 专用 JavaMail 1.6.7，465 端口实测为隐式 TLS；SMTP 接受不等于收件人已看到通知。

本地去重不能实现第三方端到端 exactly-once：服务端已接受但响应丢失、发送中进程被强杀，可能产生重复。邮件保留稳定 Message-ID，内部标识不会放入普通标题或正文。

数据库损坏会停止并保留文件，不自动删除重建。消息数量达到上限时采集事务失败且游标不前移，日志报告错误；不会丢弃待发消息腾空间。失败任务正文会保留供恢复，因而长期故障需要人工处理容量。

日志平时追加，仅在启动、当天首次实际写入、达到容量或手动维护时检查。按配置时区保留今天与前六个自然日；容量优先，异常爆量可能少于七天。维护同目录临时文件后原子替换，失败保留原文件。时钟明显跳变／回拨时暂缓按年龄删除，容量限制继续有效；同一次开机内跨重启可结合单调时钟识别跳变。跨整机重启的向前校时与真实长时间关机无法完全区分。空闲不为日志单独唤醒。

## 构建和离线测试（PowerShell）

本地推荐把整个目录放在 `E:\smsrelay`，工具也放 E 盘：

```powershell
cd E:\smsrelay\repo
.\scripts\prepare-tools.ps1 -Tools E:\smsrelay\tools
.\scripts\test-host.ps1 -Tools E:\smsrelay\tools
.\scripts\build.ps1 -Tools E:\smsrelay\tools
# 接入有 root 的指定设备；不访问真实短信库，不联网发送
.\scripts\test-device-offline.ps1 -Serial <实际序列号> -Tools E:\smsrelay\tools
```

构建固定 JDK、SDK、D8、Android JavaMail／Activation 的下载地址与 SHA-256，见 `scripts/tools.lock.json`。DEX 最低 API 25，无自带原生二进制。ZIP 使用文件白名单和固定 ZIP 时间戳，产物及校验文件位于 `dist/`；ZIP 不包含源码目录、工具、私有配置或运行数据。若已有依赖，离线可直接构建和跑主机测试。

真实短信 API 测试工具 `scripts/send-test-sms.ps1` 从仓库外的私有配置读取端点和密钥，固定目标来自该配置；每次请求前持久化计数，最多 100 次，正确编码查询参数，不打印完整 URL 或响应正文。它不是公共 CI 的一部分。

## 停用与卸载

立即停止使用菜单或 `smsrelay stop`。Magisk 模块开关主要在下次启动时生效；本模块没有定时监测开关文件，不能承诺开关切换后立即停止现有服务。要立即停用，先执行停止，再关闭模块开关。

卸载脚本只请求本模块自己的控制接口停止服务。默认保留 `/data/adb/smsrelay` 的配置、队列、去重和日志，供重装恢复；需要删除时由用户在确认不再需要待发数据后手动删除此目录。不会修改、删除系统短信，不改已读状态，不更换默认短信应用，不卸载其他模块。

首次解锁前若短信 Provider 或加密数据不可用，启动会停止并在 `run/last-error.txt` 提示解锁后重启；没有无限轮询。解锁后请通过菜单明确重启。此次未重启日常使用中的手机进行首次解锁前实测。

## 技术参考

- [Magisk 模块开发指南](https://topjohnwu.github.io/Magisk/guides.html)
- [Android Cursor 观察器](https://developer.android.com/reference/android/database/Cursor)
- [AOSP CursorToBulkCursorAdaptor](https://android.googlesource.com/platform/frameworks/base/+/android-13.0.0_r1/core/java/android/database/CursorToBulkCursorAdaptor.java)
- [JavaMail for Android（最低 API 19）](https://javaee.github.io/javamail/Android)
- [企业微信群机器人配置说明](https://developer.work.weixin.qq.com/document/path/91770)
- 各版本查询接口核对和实际限制见兼容记录。
