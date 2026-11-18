# 实现与设计

[返回项目首页](../README.md) · [兼容矩阵](COMPATIBILITY.md) · [测试记录](TESTING.md)

## 事件入口和低功耗

K40 的系统 ContentService 会拒绝未登记在 ActivityManager 的独立 root 进程直接注册观察器（`Failed to find PID`）。本模块使用系统框架的 **远程 Cursor 通知桥**：短信 Provider 的 Cursor 在 Provider 进程注册通知观察器，经 `CursorToBulkCursorAdaptor / BulkCursorToCursorAdaptor` 的 Binder 回调送达 root 进程，再触发本地 `ContentObserver`。持续保留一个空结果 Cursor，收到事件后才读新增收件短信。这条路径已通过 K40 真实来信验证。

不存在短信轮询、进程定时巡检、心跳、周期 ping 或持续唤醒锁。监督进程阻塞 `waitFor()`，只在子进程退出后执行有限恢复；十分钟窗口内第三次连续异常退出后停止。两个实例锁均使用 Java FileChannel 同一种跨进程锁机制。

重启恢复先建立观察器，再补查增量。新增 ID、最近 48 小时和最近 512 条的有限重叠补查配合稳定指纹，处理重复通知和低 ID。首次历史指纹也覆盖这些重叠范围。不会周期全库扫描。很旧且落在补查范围外的数据库恢复记录无法保证被发现；手动重建状态库会改变历史处理边界。

有待发任务时才注册网络回调；网络不可用时不消耗重试次数。退避使用 Handler 的非唤醒延迟，深睡会推迟重试时刻，不承诺准点唤醒。处理短信采集和实际发送时持有 `smsrelay:work` 短时唤醒锁，带硬超时并在完成／取消路径释放。

## 队列、日志与恢复

完整任务、正文和采集进度在同一个 SQLite FULL 同步事务中提交，之后才发送。每个邮箱收件人、企业微信分段分别记录 PENDING、SENDING、SUCCEEDED、FAILED、次数和下次时间。启动时 SENDING 恢复为待处理；手动重启不清空队列。

企业微信按 UTF-8 字节分段，保留码点、顺序和段号；发送间隔至少约 3.1 秒。前段失败时后段标为阻塞失败，邮箱仍独立处理。企业微信同时校验 HTTP 状态和解析后的业务 errcode。SMTP 使用 Android 专用 JavaMail 1.6.7，465 端口实测为隐式 TLS；SMTP 接受不等于收件人已看到通知。

本地去重不能实现第三方端到端 exactly-once：服务端已接受但响应丢失、发送中进程被强杀，可能产生重复。邮件保留稳定 Message-ID，内部标识不会放入普通标题或正文。

数据库损坏会停止并保留文件，不自动删除重建。消息数量达到上限时采集事务失败且游标不前移，日志报告错误；不会丢弃待发消息腾空间。失败任务正文会保留供恢复，因而长期故障需要人工处理容量。

日志平时追加，仅在启动、当天首次实际写入、达到容量或手动维护时检查。按配置时区保留今天与前六个自然日；容量优先，异常爆量可能少于七天。维护同目录临时文件后原子替换，失败保留原文件。时钟明显跳变／回拨时暂缓按年龄删除，容量限制继续有效；同一次开机内跨重启可结合单调时钟识别跳变。跨整机重启的向前校时与真实长时间关机无法完全区分。空闲不为日志单独唤醒。

## 技术参考

- [Magisk 模块开发指南](https://topjohnwu.github.io/Magisk/guides.html)
- [Android Cursor 观察器](https://developer.android.com/reference/android/database/Cursor)
- [AOSP CursorToBulkCursorAdaptor](https://android.googlesource.com/platform/frameworks/base/+/android-13.0.0_r1/core/java/android/database/CursorToBulkCursorAdaptor.java)
- [JavaMail for Android（最低 API 19）](https://javaee.github.io/javamail/Android)
- [企业微信群机器人配置说明](https://developer.work.weixin.qq.com/document/path/91770)
- 各版本查询接口核对和实际限制见兼容记录。
