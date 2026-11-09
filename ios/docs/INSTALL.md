# 肉包 2.0 安装教程：从零到 iPhone 独立运行

[English](INSTALL.en.md) · [返回首页](../README.md)

本教程面向自行编译签名的开发者。首次需要 Mac、USB 和你自己的 Apple 账号；配置后任务运行不依赖 Mac、不要求 Wi-Fi、不需要越狱或额外控制硬件。**不需要安装 WDA/WebDriverAgent**，也不用运行 Appium 服务。这里只发布源码，不提供可直接安装到所有手机的通用已签名 IPA。

## 1. 准备环境

| 项目 | 要求 |
|---|---|
| iPhone | 工程最低 iOS 26，开发验证为 iPhone 15 Pro Max / 17 Pro Max、iOS 27.0.1。其他组合需自行验证 |
| Mac | 能运行支持你手机系统版本的完整 Xcode；本次发布构建环境是 Xcode 27.1。仅 Command Line Tools 不够 |
| USB 数据线 | 首次信任、安装及配对使用，完成后可拔掉 |
| Apple 账号 | 免费 Personal Team 或有 Network Extension 权限的付费开发团队，见第 4 节 |
| Rust | `rustup`、`cargo` 和 `aarch64-apple-ios` target；Mac 能访问 GitHub、SwiftPM、crates.io |
| 模型 | 自备 API Key。主模型需要工具调用与视觉能力；兼容接口并不保证模型能力 |
| 手机联网 | Wi-Fi 或蜂窝均可；纯蜂窝首次连接需第 7 节的快捷指令助手 |

先安装 [Xcode](https://developer.apple.com/xcode/) 和 [Rust/rustup](https://rustup.rs/)，打开 Xcode 一次，完成组件下载和许可提示。如果系统选中的开发目录不对：

```sh
sudo xcode-select --switch /Applications/Xcode.app/Contents/Developer
xcodebuild -version
rustup target add aarch64-apple-ios
```

Xcode 安装路径不同时替换路径。依赖编译若提示 `pkg-config`、libusb 或 OpenSSL 缺失，按报错安装对应开发库；Homebrew 环境可用 `brew install pkg-config libusb openssl@3`。

## 2. 信任 Mac 并开启开发者模式

1. USB 连接 iPhone 和 Mac，解锁手机。手机点“信任此电脑”，如要求设备密码，由你本人输入。
2. Xcode → **Window → Devices and Simulators**，确认设备已识别，等待准备完成。
3. iPhone → **设置 → 隐私与安全性 → 开发者模式**，打开开关。
4. 按提示重启；重启后解锁，再确认启用开发者模式，输入密码。
5. 如果找不到开关，先完成 Xcode 识别/尝试开发安装，再回到设置查看；不要去找“越狱”工具。

参考 [Apple 开发者模式说明](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device)。信任电脑、开发者模式和应用签名是三件不同的事，不能互相代替。

## 3. 克隆源码并编译设备库

```sh
git clone https://github.com/Turbo1123/roubao.git
cd roubao/ios
rustup target add aarch64-apple-ios
scripts/build-idevice.sh
```

脚本下载固定版本 `jkcoxson/idevice @ 3854a5d`，应用仓库中的补丁，生成 `iPhoneUse/idevice/libidevice_ffi.a`。首次较慢。`.build/` 是生成目录，不要把签名配置或编译产物提交到 Git。

## 4. 选择签名和 VPN 方案

| 方案 | 编译设置 | 手机侧 | 注意 |
|---|---|---|---|
| 有 Network Extension 权限的付费团队 | `BUILTIN_VPN=YES`，默认 | 使用肉包内置 VPN，允许系统添加配置 | 证书/profile 必须允许 Packet Tunnel Network Extension |
| 免费 Personal Team | `BUILTIN_VPN=NO` | 另装 [LocalDevVPN](https://apps.apple.com/app/localdevvpn/id6755608044)，启动其 VPN；肉包选择 LocalDevVPN | Apple 免费 profile 通常 7 天到期，需要重新签名安装；此分支未完成完整真机验收 |

免费账号的有效期和配额见 [Apple Personal Team 说明](https://developer.apple.com/help/account/basics/about-your-developer-account)。付费团队也有证书/profile 有效期，不是永久免维护。安装 LocalDevVPN 可能受所在地区的 App Store 可用性影响。

在 Xcode 中：

1. 打开 `iPhoneUse.xcodeproj`；Xcode → Settings → Apple Accounts 登录你的账号。
2. 项目 Build Settings 设置 **`ROUBAO_BUNDLE_ID`** 为自己的唯一标识，例如 `com.yourname.roubao`。示例只是占位，请替换。
3. 主应用 `iPhoneUse`、扩展 `AgentWidget`、`TunnelProvider` 的标识会依次变成主 ID、`.AgentWidget`、`.TunnelProvider`，**不要仅修改一个 target**。
4. 三个 target 的 Signing & Capabilities 选择同一团队，启用自动签名。项目级 `DEVELOPMENT_TEAM` 也可统一配置。
5. 免费团队在项目 Build Settings 把 `BUILTIN_VPN` 设为 `NO`（Debug/Release 都检查），去掉 Network Extension entitlement；否则常见错误是 profile 不支持网络扩展。
6. 顶部选择 **iPhoneUse scheme → 你的真实 iPhone**，点击 Run。

如手机提示开发者不受信任，在 **设置 → 通用 → VPN 与设备管理** 中按系统提示信任自己的开发者；不要信任陌生签名。保持手机解锁，首次权限提示需由你确认。

### 可选：命令行签名安装

在 `.build/Signing.xcconfig` 本地创建以下内容；所有值都要换成自己的，文件不要提交：

```xcconfig
DEVELOPMENT_TEAM = YOUR_TEAM_ID
ROUBAO_BUNDLE_ID = com.yourname.roubao
BUILTIN_VPN = YES
```

免费团队把最后一项改为 `NO`。然后：

```sh
xcrun devicectl list devices
# 替换为你刚查到的设备标识，不要照抄占位符
xcodebuild -project iPhoneUse.xcodeproj -scheme iPhoneUse \
  -configuration Release -destination 'generic/platform=iOS' \
  -derivedDataPath .build/DeviceBuild -xcconfig .build/Signing.xcconfig \
  -allowProvisioningUpdates build
xcrun devicectl device install app --device 'YOUR_DEVICE_ID' \
  .build/DeviceBuild/Build/Products/Release-iphoneos/iPhoneUse.app
```

先通过 Xcode 完成账号登录及必要 profile 注册，自动签名才有相应权限。无需把 Apple 账号密码写进命令或仓库。

## 5. 生成这台手机自己的 Remote Pairing 文件

继续保持 USB 连接、信任完成、开发者模式开启、手机解锁。下面命令均从仓库根目录执行：

```sh
cargo build --manifest-path .build/idevice/Cargo.toml \
  --release -p idevice-tools --bin idevice-tools --no-default-features --features ring
mkdir -p .runtime
umask 077
.build/idevice/target/release/idevice-tools --udid 'YOUR_DEVICE_UDID' \
  remotepairing_lockdown pair iPhoneUse .runtime/pairingFile.plist
chmod 600 .runtime/pairingFile.plist
```

`YOUR_DEVICE_UDID` 换成 Xcode 设备详情中的 UDID（不要混用设备名称、Bonjour ID）。`iPhoneUse` 是此实现使用的配对主机名，不要随意替换。若连接了多台手机，务必指定目标。命令应显示已写入文件；失败或一直等待不算成功，不要用空文件继续。

将文件通过 AirDrop/“文件”App 等自己的渠道传到目标 iPhone，然后肉包 → 设置 → 配对文件 → 导入。也可直接复制进应用容器：

```sh
xcrun devicectl device copy to --device 'YOUR_DEVICE_ID' \
  --domain-type appDataContainer --domain-identifier 'com.yourname.roubao' \
  --source .runtime/pairingFile.plist --destination Documents/pairingFile.plist
```

Bundle ID 换成第 4 节自己的主应用 ID；复制后重新打开肉包。如果使用导入按钮，以实际导入成功状态为准。文件只适用于生成它的设备，**不要放进 GitHub、截图、Issue、公共网盘或发给其他人**。重置信任、关闭开发者模式、系统升级或记录失效后可能需要重新生成；换手机必须重做。

## 6. 建立本机连接

1. 打开肉包的设置 → 手机连接，选择第 4 节对应模式。
2. 内置模式允许“肉包想添加 VPN 配置”。如要求设备密码，在手机上本人输入；弹窗消失不代表配置已保存，要检查系统 VPN 配置和应用状态。
3. LocalDevVPN 模式先在 LocalDevVPN 中启动 VPN，再回肉包重连；内置失败不会悄悄自动切换模式。
4. 允许“本地网络”。需要后台执行时按设置引导开启相应后台保活权限；位置“始终允许”用于现有后台方案，不是地图定位功能的验收。
5. 等待 Developer Disk Image 下载并挂载，直至显示已连接。首次需要手机联网。已经挂载时会复用；下载网络失败要处理原始错误，不必反复重签名。

本地回环 VPN 用来访问这台手机自身的开发服务，不是代理模型请求的远程 VPN。其他 VPN 可能被它顶掉。系统可能出现“从 Mac 远程查看”提示，这是开发服务提示，不表示任务还要靠 Mac。

## 7. Wi-Fi 关闭，只用蜂窝数据

1. 在手机“设置”中关闭 Wi-Fi，开启蜂窝数据，确认肉包、模型服务和快捷指令的联网没有被系统限制。
2. 在肉包首页连接提示或 **设置 → 手机连接** 点“添加蜂窝连接助手”。
3. 在快捷指令中查看并添加 **`[iPhone Use] Toggle Cellular`**，保留原名。当前链接：[添加助手](https://www.icloud.com/shortcuts/3b7bbc4cd1f243968423cfb0e40ed5b6)。
4. 回肉包点“已添加助手，一键连接”，允许正常的快捷指令执行提示。
5. 肉包先尝试直接连接；按需短暂关闭蜂窝、建立本机隧道，再恢复蜂窝以调用模型。**整个过程不要求加入 Wi-Fi。**
6. 如果提示未确认恢复，去控制中心手动开启蜂窝，再检查网络；快捷指令被取消/回调丢失不算成功。

如果链接失效，可在快捷指令中创建同名助手：**如果“快捷指令输入”有任何值 → 将蜂窝数据设为打开；否则 → 设为关闭**。使用“设定”为开/关，不要使用“切换”。该助手只为本机建连恢复路由，不会让模型离线可用。纯蜂窝、重启后冷连接及不同运营商仍应在你的设备验证。

## 8. 配置主模型、可选 JEV 和语音

- **设置 → AI 模型**：选择自定义兼容接口或支持的 provider，填写 Base URL、自己的 API Key、准确模型 ID；按提供商规则填写 URL，不要把完整 `/chat/completions` 路径误当成 Base URL。点击连接测试并查看具体错误。
- 主模型需要可用的工具调用。图片定位需要视觉模型；仅支持文字或仅声称“OpenAI 兼容”的接口可能无法执行。连接测试通过也不是复杂任务成功率保证。
- **可选 JEV**：设置 → 任务执行 → JEV 视觉定位，填写自己的 TypeSafe Key。JEV 不是必需，未配置可走主模型。出现 401/403/404/451 时该轮交还主模型，具体可用地区、权限以服务商为准。[JEV 细节](JEV.md)
- **可选语音**：设置中的语音输入配置豆包/火山引擎 ASR Key；相关权限/产品需由你在提供商侧开通。开启麦克风权限后按录音，部分识别即时进入输入框，结束后可以编辑，再手动发送。不开通不影响文字任务。
- 开启通知权限，肉包才能在询问和步数扩展等待时提醒你；动态岛需要系统支持及 Live Activities 开关。

Key 存设备钥匙串，不写入源码。使用 JEV 或云 ASR 会把对应截图/音频发给其服务；主模型同样接收任务上下文。

## 9. 首个验收任务与独立运行检查

发送：“打开计算器，清空后计算 23×17，读取结果。”预期 **391**。然后：

1. 拔掉 USB，关掉 Mac，再执行同一测试。
2. 保持 Wi-Fi 关闭、蜂窝开启，主动重连后再测试，不能只检查旧连接仍在。
3. 试一次问答卡、停止、历史任务继续、生成 PDF 后追问修改。
4. 后台可能被系统限制；首次先保持屏幕亮着。只有自己的设备实测成功，才算这个组合完成验收。

## 10. 常见问题

| 现象 | 处理 |
|---|---|
| 找不到开发者模式 | USB 信任、Xcode Devices 准备，重启再确认；参照 Apple 文档 |
| profile 不支持 Network Extension | 核对团队权限；免费团队 `BUILTIN_VPN=NO` + LocalDevVPN |
| Bundle ID 不可用 | 换自己的唯一 `ROUBAO_BUNDLE_ID`，保持两个扩展后缀对应，不要使用作者的注册身份 |
| 点允许后 VPN 没加上 | 完成设备密码验证，检查系统配置；保留原始错误，不将权限弹窗关闭视作成功 |
| 提示开启 Wi-Fi | 按第 7 节添加助手并重连；不需要去连接 Wi-Fi 路由器 |
| pairing invalid / expired | 确认对应这台手机和主机名；按第 5 节重新生成导入 |
| DDI 下载失败 | 检查手机到下载源网络、稍后重试；不要因此删除信任或配对文件 |
| 锁屏后不能启动/操作 | 解锁；不要把解锁限制当作模型错误 |
| 免费签名几天后打不开 | 按 Apple profile 到期规则重新签名安装；先备份重要任务文件，不要直接卸载丢数据 |
| 模型 401/403/451、无图片或工具 | 检查 Key、服务地区/权限、模型功能和 Base URL，勿把凭据贴进 Issue |
| 点击偏移/页面停滞 | 保留脱敏日志，主模型应重新观察定位；停止后可继续原会话，勿反复重放状态未知的最终动作 |
| 输入框没变化 | 先看焦点、系统粘贴提示和新截图；剪贴板回读成功不等于控件输入成功 |

## 开发与未签名 IPA

```sh
# 不使用签名账号的编译检查；产物不能直接安装
xcodebuild -project iPhoneUse.xcodeproj -scheme iPhoneUse \
  -configuration Release -destination 'generic/platform=iOS' \
  -derivedDataPath .build/UnsignedBuild CODE_SIGNING_ALLOWED=NO build
scripts/package-ipa.sh \
  "$PWD/.build/UnsignedBuild/Build/Products/Release-iphoneos/iPhoneUse.app" \
  "$PWD/output/Roubao-2.0-unsigned.ipa"
# 离线协议/harness 测试
swift test --scratch-path .build/ModelTests
```

未签名 IPA 仍需自己的签名和相应权限；不能仅改后缀就装。请勿公开上传带开发者证书/profile、设备列表、Key、配对记录或私人截图的包。模拟器仅检查布局，不能验收真实 CoreDevice/VPN：`rustup target add aarch64-apple-ios-sim && scripts/build-idevice.sh sim`，再按上游开发说明配置 simulator 库路径。
