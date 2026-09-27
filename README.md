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

```bash
git clone https://github.com/<you>/PalmKey.git
cd PalmKey
./gradlew :app:assembleDebug
```

- minSdk 28 / targetSdk 36 / Kotlin 2.0 / Jetpack Compose (Material 3)
- 图标等设计源文件在 `app/design/icon/`，可用其中的脚本重新生成

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
