# ApplePods

ApplePods 是一个面向 **HyperOS 3** 的现代 LSPosed 模块，用于补全小米系统已内置的 AirPods 支持。

项目采用轻量注入方式：保留 HyperOS 原有的耳机详情页、控制中心卡片、连接弹窗和系统交互，只在原厂缺失的位置补充功能。目前仍处于早期测试阶段，不建议在无法恢复模块环境的主力设备上使用。

## 当前功能

- 在系统 AirPods 详情页的原厂降噪列表中加入第四项“自适应”。
- 在 HyperOS 控制中心耳机卡片的原厂降噪列表中加入“自适应”。
- 使用 AirPods AAP 的 `0x0D = 0x04` 指令切换自适应音频。
- 在原厂 AAP 会话建立后发送正确的 H2+ 扩展能力初始化包 `0x4D / D7`，使耳机能够真实回报 `0x04`，而不是降级到关闭或通透。
- 根据耳机实时回报码同步四种状态：
  - `01`：关闭
  - `02`：降噪
  - `03`：通透
  - `04`：自适应
- 在系统耳机设置中补充“对话感知”和“睡眠检测”开关，并读取耳机已有状态。
- 复用 HyperOS 原厂布局、图标和选中颜色；当前自适应暂时复用通透图标。

## 兼容要求

- HyperOS 3
- Android 15 及以上（模块 `minSdk 35`）
- 支持现代模块 API 的 LSPosed，API 版本 **102**
- 当前主要在 AirPods Pro 2 上验证

模块静态作用域：

- `com.android.settings`
- `com.xiaomi.bluetooth`
- `com.milink.service`

其他 HyperOS 版本、平板系统、国际版系统或不同 AirPods 型号可能使用不同的类名、资源 ID 和协议路径，暂未保证兼容。

## 安装

1. 从源码编译 APK，或安装项目提供的测试构建。
2. 在 LSPosed 中启用 ApplePods。
3. 确认模块作用域包含上述三个系统包。
4. 重启手机；开发调试时也可以分别重载设置、MiLink 和小米蓝牙进程。
5. 连接 AirPods，然后从系统蓝牙详情页或控制中心耳机卡片操作。

> 更新包含蓝牙 Hook 的版本后，仅重启设置页或控制中心进程是不够的。需要重启蓝牙服务或重启手机，新的 AAP 初始化代码才会加载。

## 从源码构建

需要 Android SDK、JDK 23，以及可用的 Gradle 依赖缓存或网络环境。

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Debug APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

当前版本号为 `0.2.0-alpha01`，包名为 `io.github.nathanxie.applepods`。

## 实现说明

HyperOS 的 AirPods 页面和控制中心使用两套独立实现：

- 设置页通过动态加载的 `plugin.settings.java.airpods.AncController` 管理原厂三态控件。模块从插件自身的运行时资源表加载布局和样式，并扩展模式编码与状态刷新。
- 控制中心由 `com.miui.circulateplus.world.headset` 管理。模块扩展其原厂 View、图标、标题和模式数组；HyperOS 会把未知的 AirPods `04` 映射成 `-1`，模块会回查原厂 AirPods Repository，仅在原始状态确为 `04` 时显示自适应状态。
- 蓝牙侧复用小米已有的 AirPods 传输通道，不另开第二条 AAP 连接，避免和原厂会话争用。

## 已知限制

- 这是针对当前 HyperOS 3 实现编写的版本，系统更新后混淆类名或动态插件资源可能变化。
- 自适应图标暂时使用原厂通透图标，后续会优先寻找并复用小米官方资源。
- 设置页和控制中心的具体布局可能因设备尺寸、语言或系统插件版本而不同。
- 尚未覆盖所有 AirPods/Beats 型号，也未完成长期稳定性测试。
- 当前仓库不包含正式发布签名；Debug APK 仅用于测试。

出现问题时，请附上系统版本、AirPods 型号与固件、LSPosed 版本、复现步骤，以及包含 `ApplePods-Bluetooth`、`ApplePods-Settings`、`ApplePods-MiLink` 的日志。

## 参考与致谢

协议与交互研究参考了以下项目：

- [CAPod](https://github.com/d4rken-org/capod)
- [LibrePods](https://github.com/kavishdevar/librepods)
- [HuaweiPods](https://modules.lsposed.org/module/moe.chenxy.huaweipods/)

Apple、AirPods、HyperOS、小米及相关名称和商标归各自权利人所有。本项目与 Apple 或小米没有隶属、赞助或官方关联。

## 许可

当前仓库尚未附加开源许可证。在许可证明确之前，代码默认保留全部权利；如需复用或分发，请先联系仓库所有者。
