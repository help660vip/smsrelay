# smsrelay

**把 Android 手机上的短信、响铃来电和指定应用通知转发到企业微信或邮箱。**

smsrelay 是纯 Magisk 模块。三个功能可以独立启用，分别选择企业微信机器人、SMTP 邮箱或两个渠道。适合备用机和双卡手机。转发服务由 Magisk 启动，WebUI 是可选的管理入口。

[最新正式版](https://github.com/help660vip/smsrelay/releases/latest) · [WebUI 使用说明](docs/WEBUI.md) · [配置手册](docs/USAGE.md) · [兼容性](docs/COMPATIBILITY.md)

## 下载 v0.2.1

[Magisk 模块 ZIP](https://github.com/help660vip/smsrelay/releases/download/v0.2.1/smsrelay-0.2.1.zip) · [SHA-256 校验文件](https://github.com/help660vip/smsrelay/releases/download/v0.2.1/smsrelay-0.2.1.zip.sha256) · [版本说明](https://github.com/help660vip/smsrelay/releases/tag/v0.2.1)

### 这版修了什么

- **来电号码和双卡信息**　修复 Redmi K40 的来电提醒显示「未知号码」的问题。系统提供主叫号码时，在响铃阶段显示。短信和来电都显示接收卡、运营商以及该卡的本机号码。系统未提供的号码会显示为未知。
- **WebUI 响应**　首屏和常规保存各使用一次后台请求，等待时页面会显示进度。规则预览明确标为「仅预览」，应用勾选数量即时更新。
- **开机自启**　K40 上等到 Android 首次解锁再启动，避免提前打开加密的短信数据库。开机最多等约三分钟，超时后可在 WebUI 或 Magisk「操作」里启动。

## 安装与配置

已实测 **Redmi K40 / HyperOS 1.0.10.0 / Android 13**。使用其他设备可查看 [兼容矩阵](docs/COMPATIBILITY.md)。

1. 打开 Magisk → 模块 → 从本地安装，选中 ZIP。安装后重启并解锁一次。升级保留配置和队列。
2. 使用 [KsuWebUIStandalone](https://github.com/5ec1cff/KsuWebUIStandalone) 打开 `smsrelay`，在「规则与渠道」填写企业微信 Webhook 或 SMTP 设置，再勾选各功能使用的渠道。
3. 用「规则预览」检查匹配结果。点「测试这条路线」并确认后，会向配置的渠道发送一条带测试标记的消息。收发时间和逐渠道结果可在「收发记录」查看。

也可以用支持 root 的文件管理器编辑 `/data/adb/smsrelay/config.json`。文件支持 `//` 注释。改完后在 Magisk「操作」菜单选择「检查并重载配置」。首次安装默认关闭转发，首次启用短信会跳过历史短信。[带中文注释的配置模板](module/config/config.json)

## 日常使用

- 短信可按卡槽、发件号码和正文关键词筛选。应用通知按白名单转发应用名、标题和正文。来电提醒在进入响铃时触发一次。
- 断网时保存待发消息，网络恢复后继续发送。企业微信与邮件分别记录结果，可以补发原目标中尚未成功的任务。
- WebUI 可检查监听、查看收发记录和运行日志。完整记录保存在手机，分享时可以导出脱敏内容。
- 可从 Magisk「操作」停止服务。菜单用音量上键切换、音量下键确认。通过 Magisk 停用或卸载前可先停止服务，配置和队列默认保留。

遇到问题可附上脱敏诊断、手机型号和系统版本提交反馈。[提交 Issue](https://github.com/help660vip/smsrelay/issues/new)

## 从源码构建

在仓库根目录使用 PowerShell 7：

~~~powershell
.\scripts\prepare-tools.ps1 -Tools .\tools
.\scripts\build.ps1 -Tools .\tools
.\scripts\test-host.ps1 -Tools .\tools
~~~

模块 ZIP 和 SHA-256 校验文件生成在 `dist/`。[设备验收与离线测试](docs/TESTING.md) · [设计文档](docs/DESIGN.md) · [MIT 许可证](LICENSE)
