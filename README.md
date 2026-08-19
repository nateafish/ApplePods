# ApplePods

ApplePods 是一个当前主要适配 **HyperOS 4**、同时保留 **HyperOS 3** 兼容路径的现代 LSPosed 模块，用于补全小米系统已内置的 AirPods 支持。

项目采用轻量注入方式：保留 HyperOS 原有的耳机详情页、控制中心卡片、连接弹窗和系统交互，只在原厂缺失的位置补充功能。目前仍处于早期测试阶段，不建议在无法恢复模块环境的主力设备上使用。

## 当前功能

- 在系统 AirPods 详情页的原厂降噪列表中加入第四项“自适应”。
- 在 HyperOS 控制中心耳机卡片的原厂降噪列表中加入“自适应”。
- 使用 AirPods AAP 的 `0x0D = 0x04` 指令切换自适应音频。
- 在原厂 AAP 会话建立后发送正确的 H2+ 扩展能力初始化包 `0x4D / D7`，使耳机能够真实回报 `0x04`，而不是降级到关闭或通透。
- 根据耳机实时回报码同步四种听音模式状态：
  - `01`：关闭
  - `02`：降噪
  - `03`：通透
  - `04`：自适应
- 在系统耳机设置中补充“对话感知”和“睡眠检测”开关，并读取耳机已有状态。
- 复用 HyperOS 原厂布局、图标和选中颜色；当前自适应暂时复用通透图标。
- 自适应状态采用 CAPod 风格的 pending/confirmed 机制：发送 `04` 后等待耳机真实回报码 `04`，忽略短暂的原生旧状态，避免状态被系统回写覆盖。

### 自适应滑块独立状态

自适应滑块不是 `01~04` 的听音模式状态，而是独立的自动降噪强度状态。它使用 AAP `0x2E`（`ID_AUTO_ANC_STRENGTH`）回报码同步，界面值范围为 `0~100`：

- 数值越大，表示增强的环境声越多。
- 耳机回报码与界面值采用反向映射：界面值 = `100 -` 耳机回报码。
- 设置页和控制中心都根据耳机实时回报更新滑块位置，并在写入后等待确认。

## 兼容要求

- HyperOS 4（当前主要适配和实机验证版本）
- HyperOS 3（保留兼容路径，验证覆盖低于 HyperOS 4）
- Android 15 及以上（模块 `minSdk 35`）
- 支持现代模块 API 的 LSPosed，API 版本 **102**
- 当前主要在 AirPods Pro 2 上验证

模块静态作用域：

- `com.android.settings`
- `com.xiaomi.bluetooth`
- `com.milink.service`

HyperOS 4 的蓝牙扩展仍运行在 `com.xiaomi.bluetooth`；其中 AirPods Repository 实现从 HyperOS 3 的 `p0.a` 迁移为 `k1.a`，AAP 接收入口为 `x2.b.f()`。其他系统版本、平板系统、国际版系统或不同 AirPods 型号可能使用不同的类名、资源 ID 和协议路径，暂未保证兼容。

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

当前版本号为 `0.2.0-alpha01`，包名为 `io.github.nateafish.applepods`。

## 实现说明

HyperOS 的 AirPods 页面和控制中心使用两套独立实现：

- 设置页通过动态加载的 `plugin.settings.java.airpods.AncController` 管理原厂三态控件。模块从插件自身的运行时资源表加载布局和样式，并扩展模式编码与状态刷新。
- HyperOS 4 设置页通过 Qigsaw 动态加载。模块在 `JavaActivity` 进入 `onCreate` 时取得 split ClassLoader，Hook 插件自身的 Fragment/`AncController` 生命周期：功能开关在 Preference XML 创建后加入，自适应按钮在原生 `initView()` 完成后同帧加入，不依赖固定延时。
- “对话感知”和“睡眠检测”位于原生 `profile_container`（通话音频卡片）之前，并放在同一个无内部间隔的 Preference 分组中。
- HyperOS 4 控制中心由 `com.miui.circulateplus.world.headset.r` 管理。模块把第四个 `AncModeConfig` 注入原生列表，继续由系统的 `HeadsetSelectCardView.a()` 创建和布局，不手动 inflate 或 addView；通透图标复用系统 `headset_transparency_selector` 资源。
- HyperOS 3 控制中心保留旧的原生数组兼容路径。两套路径都只替换自适应的最终指令，其他三种模式继续使用系统原生处理。
- 蓝牙侧复用小米已有的 AirPods 传输通道，不另开第二条 AAP 连接，避免和原厂会话争用。自适应状态使用 pending/confirmed 状态机，并在 AAP 会话重建时重新发送扩展能力初始化包。

## 已知限制

- 系统更新后混淆类名或动态插件资源可能变化；HyperOS 3 与 HyperOS 4 使用不同的蓝牙实现，模块会按运行时类名选择对应路径。
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
