# 配置与使用手册

首次安装会在手机上创建 `/data/adb/smsrelay/config.json`。请在已有文件中修改相应字段，保留其余字段。修改后执行 Magisk「操作」里的「检查并重载配置」。升级会保留已有配置和队列。

配置支持带注释的 JSON（JSONC）。文件名仍为 `config.json`，用 UTF-8 保存。可以写 `//` 行注释，也可以用 `/* ... */` 写块注释。字段名和字符串用双引号，同组最后一项省略逗号。旧的纯 JSON 配置也可以继续使用。

## 渠道设置

可只开启一个渠道，也可以两个一起开启。同时将配置根节点的 `enabled` 设为 `true`。

企业微信群需要一个机器人 Webhook。将下面的值填进配置文件的 `wecom` 对象：

~~~json
"wecom": {
  "enabled": true,
  "webhook": "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=REPLACE-ME"
}
~~~

邮件需要 SMTP 主机、账号及密码或授权码。按你邮箱服务商的设置填写 `smtp` 对象：

~~~json
"smtp": {
  "enabled": true,
  "host": "smtp.example.invalid",
  "port": 465,
  "tls": "implicit",
  "username": "sender@example.invalid",
  "password": "REPLACE-ME",
  "from": "sender@example.invalid",
  "to": ["recipient@example.invalid"]
}
~~~

465 端口通常使用 `implicit`（建立连接时即启用 TLS）。如果服务商要求 STARTTLS，例如常见的 587 端口，设置 `tls` 为 `starttls`。两种模式都验证服务器证书和主机名。企业微信渠道仅接受官方 HTTPS Webhook 地址。

## 筛选短信

在 `filters` 下按需填写列表：

| 字段 | 例子 | 规则 |
| --- | --- | --- |
| `sim_slots` | `[1, 2]` | 按手机卡槽编号匹配 |
| `numbers` | `["10086"]` | 与系统收件箱中的发送号码精确匹配 |
| `keywords` | `["账单", "余额"]` | 正文包含其中一个关键词 |

同一列表匹配任意一项即可。填了多个列表时，需要同时满足这些条件。空列表表示不限制该项。运营商和本机号码取自系统的活动订阅，系统未提供号码时，通知会显示「未知」。

## 操作菜单与命令

在 Magisk 模块卡片点「操作」。音量上切换选项，音量下确认，约 60 秒无操作会退出。菜单可启动或停止服务、重载配置、测试两个渠道、查看队列、重试失败任务、查看日志和导出脱敏诊断。

也可从手机的 root 终端操作：

~~~sh
su -c '/data/adb/modules/smsrelay/smsrelay status'
su -c '/data/adb/modules/smsrelay/smsrelay check'
su -c '/data/adb/modules/smsrelay/smsrelay reload'
su -c '/data/adb/modules/smsrelay/smsrelay test-wecom'
su -c '/data/adb/modules/smsrelay/smsrelay test-smtp'
su -c '/data/adb/modules/smsrelay/smsrelay queue'
su -c '/data/adb/modules/smsrelay/smsrelay retry'
su -c '/data/adb/modules/smsrelay/smsrelay warnings'
su -c '/data/adb/modules/smsrelay/smsrelay export'
~~~

同一命令还支持 `start`、`stop`、`restart`、`logs` 和 `maintain`。重启保留队列。手动重试使用原发送目标当前的有效配置，适合修正密码或端口后补发。更换收件地址时，旧任务仍归原目标。

## 其他配置

完整字段和默认值见 [配置模板](../module/config/config.json)。`schema_version` 当前为 1。关闭顶层 `enabled` 会暂停发送并保留已有队列，期间收到的短信会随采集进度跳过。配置校验失败时，服务继续使用上一次有效配置。

- `retry_seconds` 默认 `[30, 120, 600]`，分别为失败后各次重试的等待秒数。永久鉴权或参数错误需要先修正配置。网络不可用期间不计入重试次数。
- `timeouts` 分别控制连接、读写和一次发送的时限，单位为秒。
- `logging` 默认 INFO、Asia/Shanghai、最近七个自然日。达到 2 MiB 时优先裁剪到约 1 MiB。
- `retention` 默认保留 30 天去重记录，最多 20,000 条消息。全部任务成功后，正文会在后续维护时按 24 小时保留期清理。

## 排错与卸载

| 内容 | 手机路径 |
| --- | --- |
| 配置 | `/data/adb/smsrelay/config.json` |
| 队列和去重记录 | `/data/adb/smsrelay/state.db` |
| 服务日志 | `/data/adb/smsrelay/logs/service.log` |
| 脱敏诊断 | `/data/adb/smsrelay/exports/` |

- **服务没开始监听**：先解锁手机，在菜单重启服务。仍失败时查看 `/data/adb/smsrelay/run/last-error.txt` 和最近日志。
- **渠道测试没收到**：查看队列和 WARN / ERROR。SMTP 服务器接受后，请继续检查收件箱或垃圾邮件。
- **导出日志**：用菜单的「导出脱敏诊断」。配置文件和状态数据库可能包含凭据及待发正文，请妥善保管。

要立即停用，先在菜单执行「停止服务」，再关闭 Magisk 模块开关。要卸载，停止服务后从 Magisk 移除模块。卸载保留 `/data/adb/smsrelay` 下的配置、队列和日志，方便重装恢复。确定不再需要待发数据后，可以手动删除该私有目录。模块不会更改系统短信或已读状态。

[返回项目首页](../README.md)
