# 亮度解绑 · Unbind Brightness

> 让 HyperOS 4 上的第三方视频 App 亮度只作用于显示层，不再偷偷改掉你的系统亮度。

> ⚠️ **非官方项目**：与 Xiaomi / HyperOS / MIUI 官方无任何关联，未获官方授权或背书。「HyperOS」「澎湃」等为小米商标，此处仅作兼容性说明使用。

[![Release](https://img.shields.io/github/v/release/hqyue/unbind-brightness?label=release&color=blue)](https://github.com/hqyue/unbind-brightness/releases/latest)
[![License](https://img.shields.io/github/license/hqyue/unbind-brightness?color=green)](LICENSE)
[![API](https://img.shields.io/badge/libxposed-102-orange)](https://github.com/libxposed/api)
[![Platform](https://img.shields.io/badge/HyperOS-4%20%2F%20Android%2017-red)]()

<!-- 徽章里的 hqyue/unbind-brightness 记得换成你的 GitHub 用户名和仓库名 -->

LSPosed / libxposed 模块。作用域仅 **系统框架（system）**，只挂载 `system_server`。APK 体积 21 KB。

---

## 这个模块解决什么问题

在 HyperOS 4（澎湃 4）上，用 B 站、抖音、YouTube 这类 App 播放视频时，App 会调高屏幕亮度。这在 HyperOS 3 上是纯粹的"显示层覆盖"——退出视频，亮度立刻回到你原本设置的值，系统亮度滑块全程不动。

HyperOS 4 改变了这个行为：视频亮度会被**写回**成系统亮度基值。结果就是——

- 退出视频后，屏幕亮着不动，系统亮度/滑块被永久抬高；
- 想回到原来的亮度，得手动把滑块拖回去；
- 自动亮度模式下更糟，下一次环境光变化会以这个被污染的基值重新计算。

本模块做的事很简单：**把 HyperOS 4 多出来的这条写回管道掐断，让行为退回到 HyperOS 3**。视频该亮还是亮，只是不再污染系统亮度。

## 效果

| 场景 | HyperOS 4 原生 | 安装本模块后 |
|---|---|---|
| 视频中调高亮度 | 屏幕变亮，滑块跟着涨 | 屏幕变亮，**滑块不动** |
| 退出视频 | 亮度停留在高位 | 约 2.5 秒平滑回落到原值 |
| 自动亮度模式 | 基值被污染，后续计算偏移 | 基值保持干净，行为同 HyperOS 3 |

## 安装

1. 从 [Releases](../../releases/latest) 下载最新 APK，安装。
2. 在 LSPosed / Vector 中启用「亮度解绑」。
3. 作用域**只勾「系统框架」（system）**——不是「Android 系统」，勾错了不会生效。
4. **重启手机**。`system_server` 必须重启才会重新加载模块。

> 需要 root + LSPosed（libxposed API 102 及以上）。仅适配 HyperOS 4 / Android 17（SDK 37）。

## 原理

以下结论来自真机 framework 的 smali 字节码对比，不是对 AOSP 源码的推测。

HyperOS 4 相比 HyperOS 3，多出整条"前台应用亮度同步"管道：

```
requestBrightnessSync(F)                    // p4 新增，p3 完全没有
  → consumePendingBrightnessSync()          // 恒返回 true
  → syncBrightnessToSettingAndUi()
      → updateScreenBrightnessSetting(FFF)  // 改内存里的系统亮度基值
      → saveBrightnessIfNeeded()            // 落盘 Settings.System.SCREEN_BRIGHTNESS
```

方法签名差异是最直接的证据：

| 方法 | HyperOS 3 | HyperOS 4 |
|---|---|---|
| `updateScreenBrightnessSetting` | `(F F)V` | `(F F` **`F`** `)V` |

多出来的第三个 `float` 就是 override 回写值。

真机日志统计也印证了这条链是单向的：拦截 `requestBrightnessSync` 后，下游 `updateScreenBrightnessSetting` 与 `saveBrightnessIfNeeded` 命中 **0 次**；不拦截时 `saveBrightnessIfNeeded` 被拦截 **41 次**。也就是说下游全是死代码，在源头做一次 no-op 就能断掉整条管道，不需要逐个封堵。

### 钩子清单

| 序号 | 目标方法 | 职责 |
|---|---|---|
| 核心 | `DisplayPowerControllerImpl.requestBrightnessSync(F)V` | no-op，源头掐断写回管道（等于还原 HyperOS 3 行为） |
| 1 | `DisplayPowerController.setBrightnessOverrideRequest(...)` | 观察视频 override 的进入/退出，退出时开启回落窗口并记录自动亮度基线 |
| 2 | `AutomaticBrightnessController.getAutomaticScreenBrightness(...)` | NaN 桥接：传感器暂停的空窗期用进视频前的基线顶住，防止闪屏 |
| 3 | `DisplayPowerController.animateScreenBrightness(F F F Z)V` | 退出后 6 秒窗口内强制慢速回落 |
| 4–7 | `getOverrideLimitBrightness` / `getCustomBrightnessForRefactorPolicy` / `setMinLogicalNit` / `updateBrightnessStateForIPA` | 仅在自动模式首次进入视频时装载，neutralize 掉 HyperOS 4 的自动亮度新增逻辑 |

每个钩子独立 try-catch，任何一个失败都不会影响 `system_server` 稳定性，也不会导致系统崩溃。

### 关于"断崖式回落"

`animateScreenBrightness(target, sdrTarget, rate, userInitiated)` 的第 3 个参数 `rate` 单位是**亮度单位/秒**（`RampAnimator.performNextAnimationStep` 每帧位移 = `mRate × 帧时长`）。

系统自带 Slow / Fast 两套速率，而 override 退出走的是 Fast——所以原生表现是直接跳变。本模块在退出窗口内把 `rate` 改写为：

```
rate = |目标 − 当前| / 2.5s
```

即固定 2.5 秒完成过渡。当前亮度实时读自 `mScreenBrightnessRampAnimator.mFirst.mCurrentValue`（屏幕上正在显示的值），所以无论跨度多大，观感都是同一个节奏。仅当跨度 > 0.04 时才强制，避免拖慢自动模式的正常微调。

手动模式与自动模式共用同一个钩子，没有分模式分支。

## 构建

依赖：

| 项 | 要求 |
|---|---|
| JDK | 17 |
| Android SDK Build-Tools | 34.0.0（`aapt2` / `d8` / `zipalign` / `apksigner`） |
| Android Platform | `platforms/android-34`（仅作 `android.jar` 用，产物仍是 minSdk 37） |
| libxposed API | 102（已附 `libs/libxposed-api.jar`） |

```bash
export ANDROID_SDK=/path/to/Android/Sdk
bash build.sh
# 产物：out/unbind-brightness_v1.0.apk
```

首次构建会自动生成 `key.jks`（已被 `.gitignore` 排除）。**请务必备份**：后续发版必须用同一个密钥签名，否则用户无法覆盖升级。

> 注意：`META-INF/xposed/` 下的三件套——`module.prop`、`scope.list`、`java_init.list`——必须全部打进 APK，缺 `java_init.list` 模块不会被加载。

## 可调参数

都在 `UnbindBrightnessModule.java` 顶部：

| 常量 | 默认值 | 说明 |
|---|---|---|
| `GRADUAL_WINDOW_MS` | `6000L` | 退出视频后慢速回落的生效窗口（毫秒） |
| `GRADUAL_DURATION_SEC` | `2.5f` | 期望的回落总时长（秒）。嫌慢就调小，嫌快就调大 |
| `GRADUAL_DELTA_THRESHOLD` | `0.04f` | 差值超过此值才强制慢速，避免干扰自动模式细微调整 |

## 兼容性与风险

- 仅适配 HyperOS 4 / Android 17（SDK 37）。其他版本类名或方法签名可能不同，钩子会静默失败（不会崩溃）。
- 系统更新若改动方法名，模块仍可装载但对应钩子失效，此时 LSPosed 日志里会有提示。
- **不影响任何持久化配置**，模块只拦截内存中的运行时调用。回滚方式：在 LSPosed 中关闭模块并重启。
- 卸载模块不会改动你当前的系统亮度设置。

## 排查

模块只在 LSPosed 日志里输出 3 条低频事件，不写文件、不加高频日志，对功耗无可见影响：

```
亮度解绑 v1.0 已装载：单点掐断前台同步管道 + 固定时长慢速回落 + 自动安全网
【进入视频】亮度=0.031373 模式=手动 屏幕已变亮，系统亮度/滑块保持真实值
【退出视频】模式=手动 已启用慢速回落
```

看不到第一条 → 模块没加载（检查作用域是否为「系统框架」、是否重启过）。
看到「进入视频」但滑块仍在动 → 核心钩子未命中，多半是系统版本不匹配。

```bash
logcat -s 亮度解绑
```

## 许可

[GPL-3.0](LICENSE)。

`libs/libxposed-api.jar` 来自 [libxposed/api](https://github.com/libxposed/api)，遵循 Apache-2.0，仅作为编译期依赖分发。

本项目不包含任何小米专有代码或反编译产物，逆向分析仅用于确定方法签名与调用关系。
