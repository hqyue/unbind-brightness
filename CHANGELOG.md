# 更新日志

本文件格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0] - 2026-10-23

首个正式版本。

### 新增

- 核心钩子：将 `DisplayPowerControllerImpl.requestBrightnessSync(float)` 置为 no-op，从源头掐断 HyperOS 4 的"前台应用亮度同步"管道，使行为退回 HyperOS 3。
- 平滑回落：退出视频后 6 秒窗口内，把 `animateScreenBrightness` 的速率改写为 `|目标 − 当前| / 2.5s`，消除 Fast 斜率导致的断崖式跳变。
- 自动亮度安全网：`getAutomaticScreenBrightness` 在传感器暂停空窗期返回进入视频前的基线值，防止闪屏。
- 自动模式 neutralize：`getOverrideLimitBrightness`、`getCustomBrightnessForRefactorPolicy`、`setMinLogicalNit`、`updateBrightnessStateForIPA` 四个钩子，剥离 HyperOS 4 新增的自动亮度逻辑。
- 正式命名「亮度解绑」，包名 `com.unbind.brightness`，图标、模块描述、版本号、SDK 37 全部补齐。

### 说明

- 仅适配 HyperOS 4 / Android 17（SDK 37），libxposed API 102。
[1.0]: https://github.com/hqyue/unbind-brightness/releases/tag/v1.0
