# smsrelay

**把 Android 手机收到的新短信转发到企业微信或邮箱。**

smsrelay 是一个纯 Magisk 模块。可以任选企业微信机器人、SMTP 邮箱，或同时发送到两个渠道。适合把备用机、双卡手机上的来信集中到常用的群或邮箱。

[下载模块](https://github.com/help660vip/smsrelay/actions/workflows/build.yml) · [配置手册](docs/USAGE.md) · [兼容性](docs/COMPATIBILITY.md) · [反馈问题](https://github.com/help660vip/smsrelay/issues/new)

## 能做什么

- **选择要转发的短信**：按 SIM 卡槽、发送号码和正文关键词筛选。通知附带发送号码、接收卡、运营商和短信原文。
- **断网时先存下来**：待发任务保存在手机上，网络恢复后继续发送。两个渠道分别记录结果和重试。
- **在 Magisk 里操作**：用模块的「操作」菜单查看状态、重载配置、发送测试消息或重试失败任务。
- **按来信事件触发**：空闲时不定时查询短信。安装只需要 Magisk 模块 ZIP，无需额外 APK 或 LSPosed。

## 快速上手

需要已获取 root 权限且安装 Magisk 的 Android 手机。**已实测 Redmi K40 / HyperOS 1.0.10.0 / Android 13**。其他设备的验证情况见 [兼容矩阵](docs/COMPATIBILITY.md)。

1. **下载 ZIP。** 登录 GitHub，打开 [Actions 构建列表](https://github.com/help660vip/smsrelay/actions/workflows/build.yml)，进入最近一次成功的构建，在 **Artifacts** 下载 `smsrelay-module`。解压下载的外层文件，取出里面的 `smsrelay-*.zip`。
2. **安装并重启。** 打开 Magisk → 模块 → 从本地安装，选中解压得到的 `smsrelay-*.zip`。
3. **填写配置。** 用支持 root 的文件管理器打开 `/data/adb/smsrelay/config.json`。把顶层 `enabled` 设为 `true`，再打开所需渠道并填写下表中的字段。首次安装默认关闭转发，首次启用会跳过历史短信。
4. **保存并测试。** 回到 Magisk → 模块 → smsrelay →「操作」，用音量上键切换、音量下键确认，执行「检查并重载配置」。选择对应渠道的固定测试消息，再检查群里或收件箱是否收到。

| 转发到 | 要填的配置 |
| --- | --- |
| 企业微信群 | 开启 `wecom.enabled`，填入 `wecom.webhook` |
| 邮箱 | 开启 `smtp.enabled`，填写 `host`、`port`、`tls`、`username`、`password`、`from` 和收件人列表 `to` |

[默认配置](module/config/config.json)和[企业微信、SMTP 配置示例](docs/USAGE.md#渠道设置)可以直接对照填写。三个筛选列表均为空时，会转发所有新收件短信。

## 日常使用

- **查看是否正常运行**：在「操作」中选「查看服务状态 / SIM 识别状态」。`observer_registered` 为 `true` 表示监听已就绪。
- **改好密码后补发**：先重载配置，再选「手动重试失败任务」，只补发同一目标中尚未成功的任务。
- **临时停止**：选「停止服务」。如果要停用或卸载，先停止服务，再在 Magisk 中操作。配置和待发队列默认保留。

遇到问题可用「导出脱敏诊断」，记录手机型号、系统版本、Magisk 版本和失败渠道后提交 [Issue](https://github.com/help660vip/smsrelay/issues/new)。

## 从源码构建

在仓库根目录打开 PowerShell 7，准备依赖后再构建：

~~~powershell
.\scripts\prepare-tools.ps1 -Tools .\tools
.\scripts\build.ps1 -Tools .\tools
.\scripts\test-host.ps1 -Tools .\tools
~~~

模块 ZIP 和 SHA-256 校验文件会生成在 `dist/`。[设备验收与离线测试](docs/TESTING.md) · [实现与设计](docs/DESIGN.md) · [MIT 许可证](LICENSE)
