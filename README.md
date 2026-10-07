# 掌钥 (PalmKey)

> 用手机的指纹，解锁你的电脑。

「掌钥」是开源项目 [PC Bio Unlock](https://github.com/MeisApps/pcbu-desktop) 的 **Android 客户端开源实现**。上游项目提供 Windows / macOS / Linux 桌面端，也有官方移动端 App，但**未开源**、且在国内难以获取；本仓库补上了一个开源的 Android 端：手机与电脑配对后，靠近电脑即可用指纹验证直接解锁。

---

## ⚠️ AI 生成内容声明

**本仓库不是全部由 AI 生成。** 项目主体、以及截至 `8201c23` 的历史，均由人类作者完成。
**从 `40c0ebd` 起的以下提交，其代码、注释、提交信息与 README 章节由 AI 生成**，请自行复核后再用于生产：

| 提交 | 内容 |
| --- | --- |
| `40c0ebd` | 修正解锁握手协议（包 ID / 明文设备 ID / 请求方向 / 配对方式判定） |
| `a686b0d` | 配对密钥落盘加密（原先明文存于 SQLite） |
| `54bfead` | 息屏与后台可靠性（去重窗口、保活链、监听可恢复、端点刷新策略） |
| `08b1728` | 界面重构与权限状态显示修正 |
| `e2dc948` | 死代码与依赖清理、权限声明修正、文档 |
| `b18b394` | 新增「不保活」模式 |
| `2ad5100` | 「不保活」模式的两个收敛缺口 |

关于可信度，如实说明：

- 上述改动在真机（vivo V2301A / Android 15）上逐项验证过；**验证范围与未验证项都写在各自的
  提交信息里**，未跑到的项已明确标注为"未验证"，没有当作通过。
- 其中 `tools/fake_pc_unlock_server.py`（协议仿真器）同样由 AI 生成。
- **尚未做过真实的端到端解锁验证**：一直只用自建的协议仿真器与回环注入验证握手，
  没有对着真正的 PC Bio Unlock 桌面端跑通「锁屏 → 手机解锁」全流程。
- AI 生成的内容仍可能有错。发现问题的正当方式是开 issue 或 pull request，而不是默认它是对的。

后续由 AI 参与的提交，会在提交信息里带 `AI-Generated: true` 标记，便于检索。

---

## 功能

- **配对**：扫描电脑端二维码，或手动输入 IP / 端口 / 加密密钥
- **解锁验证**：生物识别（指纹 / 面部），走系统 BiometricPrompt
- **连接链路**：UDP 广播自动发现 + 蓝牙 RFCOMM + TCP
- **国产 ROM 深度适配**（vivo / OriginOS 实测）：
  - 前台服务 + 常驻唤醒锁，息屏也能收到解锁请求
  - 锁屏时只点亮屏幕等待系统解锁，解锁后**自动放行、无需二次指纹**
  - 亮屏 / 桌面场景直接弹出验证界面（悬浮窗豁免后台启动限制）

## 构建

需要 JDK 17；Windows 下把 `./gradlew` 换成 `gradlew.bat`。

```bash
git clone https://github.com/JingYiWeiRan/PalmKey.git
cd PalmKey
./gradlew :app:assembleDebug
```

- minSdk 28 / targetSdk 36 / Kotlin 2.0 / Jetpack Compose (Material 3)
- 应用图标的源图形与生成脚本在 `app/design/icon/`——脚本里写死的是作者本机的绝对路径，换机器使用前需先修改脚本顶部的 `SRC` / `RES` / `OUT` 三个常量

## 息屏无法解锁时该查什么

症状：电脑发起解锁，手机**毫无反应**（连验证界面/通知都没有）；点亮屏幕后，之前那次请求才「迟到」地弹出来。

这是一个**系统冻结**问题，不是应用崩溃。实测（vivo V2301A / Android 15，本项目的目标机型）：

| 现象 | 实测证据 |
| --- | --- |
| 屏幕一熄，进程被放进 freezer | `/sys/fs/cgroup/uid_<uid>/pid_<pid>/cgroup.freeze` = `1` |
| 它的唤醒锁被系统强制禁用 | `dumpsys power`：`'PcbuApk::ServiceKeepAlive' DISABLED … mIsFrozen`，`mWakeLockSummary=0x0` |
| 于是 CPU 挂起、接收线程不再被调度 | 熄屏时注入 UDP 广播：处理数 **0**；点亮屏幕后同一条报文才被处理 |
| 该机型默认禁止后台启动界面 | `dumpsys activity`：`default_background_activity_starts_enabled=false` |

**关键结论：持有 `PARTIAL_WAKE_LOCK` 挡不住这种冻结** —— Android 会禁用已冻结进程持有的唤醒锁，
所以唤醒锁是被冻结的*结果*，而不是它的对手。任何代码改动都无法绕过。

**解决方式（用户操作，应用内已引导）**：在系统设置里把「掌钥」加入
**自启动 / 后台运行**白名单（vivo：`i管家 → 应用管理 → 权限管理 → 自启动`），
否则息屏后系统会持续冻结本应用。另外「后台弹出界面 / 锁屏显示」决定**界面能否被拉到前台** ——
即使请求被处理了，没放行这项也只能退化成一条需要手点的通知。

应用内位置：`设置 → 权限与保活`（每项右侧即跳转按钮），以及首次启动的引导弹窗。

## 本地化

**本应用只提供中文，这是产品定位，不是遗漏。**

目标用户是国内的 vivo / 小米 / OPPO 机型用户（见下面的适配说明），因此界面文案直接写在
Compose 代码里，`app/src/main/res/values/strings.xml` 只保留 `app_name`（供 AndroidManifest
的 `android:label` 使用）。原先那里还有 41 条翻译条目，但它们在代码里零引用 —— 属于死资源，
会让人误以为这个 App 已经做过本地化，已删除。

⚠️ 如果将来确实要支持多语言，**不能只把字面量搬进 `strings.xml`**：service 层目前把中文
错误消息当作返回值一路送到 snackbar（例如 `UnlockService.unlock()` 返回 `String?`），
而 `ui/components/ToastPill.kt` 还靠中文关键字（`contains("失败")` 等）判断图标与颜色，
文案一变就会静默失效。正确的落地顺序是先把「字符串结果」重构为「错误类型」，再由 UI 层映射到资源。

## 使用前提

- 电脑端安装上游 [PC Bio Unlock](https://github.com/MeisApps/pcbu-desktop) 桌面端
- 打开 App，扫码或手动输入完成配对；解锁通过 UDP 广播自动发现或蓝牙
- 想要「免点击直接弹指纹」的体验，请按 App 内引导开启悬浮窗、后台弹出界面、自启动等权限（vivo / 小米等国产 ROM 必开）

## 仓库结构

| 目录 | 说明 |
| --- | --- |
| `app/` | Android 客户端（本仓库主体，独立实现） |
| `app/design/icon/` | 应用图标的源图形与再生成脚本 |
| `server/pcbu-desktop/` | 上游桌面端源码（原样 vendor，供联调与协议对照） |

## 许可

- `app/` 目录（Android 客户端）：[MIT](LICENSE)
- `server/pcbu-desktop/`：来自上游项目，遵循其原始 **GPL-3.0** 许可（见该目录内的 `LICENSE`）

> 独立实现说明：Android 端未复用上游代码，仅按其公开的配对 / 解锁协议实现，
> 因此可以采用与上游不同的许可。感谢上游项目 [MeisApps/pcbu-desktop](https://github.com/MeisApps/pcbu-desktop)。
