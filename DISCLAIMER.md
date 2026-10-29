# 免责声明 / Disclaimer

[English below]

## 中文

1. **非官方项目**。本软件是独立的个人开源项目，与 Xiaomi（小米）、HyperOS、MIUI 及其关联方**无任何关系**，未获得任何官方授权、赞助或背书。文中出现的「HyperOS」「澎湃」「小米」等名称及商标归各自权利人所有，仅用于描述软件运行所依赖的系统环境（指名性使用），不构成任何关联暗示。

2. **不含专有代码**。本仓库不包含任何小米或第三方的专有代码、固件二进制、框架 `dex`/`jar` 或反编译产物。软件工作原理是通过 LSPosed 在运行时 hook 系统服务中**公开可见的方法名**，所有分析仅用于确定方法签名与调用关系。

3. **使用风险自负**。运行本软件需要 root 权限及 LSPosed 框架，存在导致设备不稳定、启动循环或其他不可预期后果的潜在风险。作者不对任何因使用、修改或分发本软件造成的损失负责。

4. **仅供学习研究**。请在你所适用的法律框架内使用本软件。若你所在地区对逆向工程、系统修改或此类工具的使用有特殊限制，请遵守当地法律，作者不承担任何违法使用的连带责任。

## English

1. **Unofficial.** This is an independent personal open-source project with **no affiliation** to Xiaomi, HyperOS, MIUI, or any of their associates. It is not authorized, sponsored, or endorsed by any official entity. Names such as "HyperOS", "Pengpai", and "Xiaomi" are trademarks of their respective owners and are used here solely to describe the target system environment (nominative use).

2. **No proprietary code.** This repository contains no proprietary code, firmware binaries, framework `dex`/`jar` files, or decompiled artifacts from Xiaomi or any third party. The software works by hooking **publicly observable method names** of system services at runtime through LSPosed; all analysis was limited to determining method signatures and call relationships.

3. **Use at your own risk.** Running this software requires root access and the LSPosed framework, and may cause device instability, boot loops, or other unintended consequences. The author is not liable for any damage arising from the use, modification, or distribution of this software.

4. **For research / educational use.** Use this software only within the bounds of applicable law in your jurisdiction. The author bears no liability for any unlawful use.
