# 掌钥 (PalmKey)

> 用手机的指纹，解锁你的电脑。

「掌钥」是开源项目 [PC Bio Unlock](https://github.com/MeisApps/pcbu-desktop) 的 **Android 客户端开源实现**。上游项目提供 Windows / macOS / Linux 桌面端，也有官方移动端 App，但**未开源**、且在国内难以获取；本仓库补上了一个开源的 Android 端：手机与电脑配对后，靠近电脑即可用指纹验证直接解锁。

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
