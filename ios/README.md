# 肉包 Roubao 2.0 for iPhone · iPhone AI Agent

**让 AI 直接操作你的 iPhone：看屏幕、点按、输入、跨应用完成任务。**

**运行时无需 Wi-Fi、无需越狱、无需电脑、无需额外硬件。支持纯蜂窝网络，在 iPhone 上独立运行 Agent。**

[English](README.en.md) · [详细安装教程](docs/INSTALL.md) · [使用指南](docs/USAGE.md) · [验证与限制](docs/VALIDATION.md) · [架构](docs/ARCHITECTURE.md)

![iOS 26+](https://img.shields.io/badge/iOS-26%2B-black)
![Swift and Rust](https://img.shields.io/badge/Swift%20%2B%20Rust-orange)
[![AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue)](LICENSE)

> **首次配置仍需 Mac + USB**：编译签名、安装、开启开发者模式、生成并导入本机配对文件。配置完成后，Mac 可以关机、USB 可以拔掉。签名到期或配对失效时需要维护。“独立运行”指任务循环和手机控制在本机，模型推理使用你配置的联网 API，**不代表离线大模型**。当前发布为开发者源码预览版。

## 把“AI 手机”的跨应用能力带到 iPhone

你说目标，肉包观察实际页面，规划并执行步骤；需要你选择或授权时发通知、出卡片，收到回答后继续。可以把它理解为把类似“豆包手机”的 AI 操作体验带到现有 iPhone，无需更换手机或外接控制板。本项目与豆包、字节跳动及 Apple 无官方关联，不承诺功能等同。

肉包 2.0 是 **open-source iPhone AI agent / iOS automation / phone-use harness**，基于 [iphone-use-app](https://github.com/EliotAndres/iphone-use-app) 扩展，保留上游贡献和 AGPL-3.0 许可。[Android 旧版](../docs/ANDROID.md) 保留在仓库根目录；这里是 `ios/` 中的 iPhone 版。

## 能做什么

| 能力 | 使用方式 |
|---|---|
| 跨应用操作 | 启动应用、截图、点按、长按、滑动、中文/英文/Emoji 输入、返回主屏幕 |
| 搜索与整理 | 查询地图地点、查商品页面、整理不同应用里的可见信息 |
| 比价与 PDF | 比较指定平台的同款商品、配置、可见价格及优惠条件，生成带表格/截图的报告 |
| 完成后继续聊 | “把刚才报告缩成两页”“补一个平台”；保留本会话历史、文件和已完成工作 |
| 用户参与决策 | 一次一张问题卡，可返回修改，最后统一提交；等待时发送本地通知 |
| 长任务与恢复 | 默认 100 步，到达后询问是否加 50 步；检查点和继续任务保留进度 |
| 动态岛进度 | 查看阶段和步骤、随时停止；刷新频率由 iOS 决定 |
| 文件与分享 | 原生 PDF、文件列表、QuickLook 预览及关闭、iOS 系统分享面板；报告可继续修改 |
| 流式语音输入 | 可选豆包/火山引擎 ASR，边说边显示，结束后先手动改再发送 |
| 自选模型 | 手机内填写兼容接口 Base URL、Key、模型 ID；可选 TypeSafe JEV 加速执行 |
| 卡住时主模型接管 | 本地 OCR 辅助定位，两次定位无进展/失败后要求主模型多模态重新定位 |

上述是实现能力，**不是每项业务都经过完整验收**。已有计算器、信息查询、比价 PDF、语音等开发验证；不保证无人值守发送、下单和支付可靠。[验证范围与已知问题](docs/VALIDATION.md)

## 要安装 WDA 吗？

**不用。当前版本不依赖 WebDriverAgent、Appium 或 XCTest runner。**

控制路径：`AgentRunKit → CoreDevice / RemoteXPC → idevice → 本机回环 VPN`。需要开发者模式、本机 Remote Pairing 文件和本地 VPN；不需要 ESP32、树莓派或另一台手机。[技术说明](docs/ARCHITECTURE.md)

## 从零开始安装

完整可复制步骤见 **[中文安装教程](docs/INSTALL.md)**：

1. 准备 iOS 26+ iPhone、Mac、支持该系统的完整 Xcode、Rust 和模型 API。开发验证设备为 iPhone 15/17 Pro Max、iOS 27.0.1；iOS 26 是工程最低版本，未覆盖所有组合。
2. USB 连接、解锁并“信任此电脑”；**设置 → 隐私与安全性 → 开发者模式**，开启、重启、确认。找不到开关时先让 Xcode 识别设备。
3. 克隆源码，运行 `scripts/build-idevice.sh`；Xcode 配置自己的团队及唯一 Bundle ID。
4. **有 Network Extension 权限的付费团队**使用内置 VPN；**免费 Personal Team** 设置 `BUILTIN_VPN=NO`，另装并启用 [LocalDevVPN](https://apps.apple.com/app/localdevvpn/id6755608044)。免费签名通常 7 天到期。
5. 构建安装肉包，经 USB 生成并导入这台手机自己的配对文件。**它包含私密信任信息，不可共享或借用。**
6. 允许 VPN、本地网络等权限；等待首次 Developer Disk Image（DDI）下载并挂载。
7. 纯蜂窝场景在肉包内添加 **“[iPhone Use] Toggle Cellular”** 快捷指令，再点“已添加助手，一键连接”。它短暂关闭蜂窝以建立本机通道，然后恢复；**不要求连接 Wi-Fi**。
8. 配置并测试模型。先执行“打开计算器，清空后计算 23×17，读取结果”，应得到 **391**。
9. 拔 USB、关 Mac，保持手机解锁和联网再跑任务，验证自己的独立运行和纯蜂窝重连。

## 任务示例

```text
打开计算器，清空后计算 23×17，读取结果。
在地图查询附近三家咖啡店，整理距离和营业信息，先不要下单。
在指定的三个电商平台比较同型号、同容量的手机，记录价格条件和截图，生成 PDF。
继续刚才的报告，标注哪些价格需要会员或优惠券，另存新版。
```

地点、账号、地址等缺失选择由肉包向你询问。发送或购买等最终操作请核对目标和内容；不要用真实支付做首次测试。

## 构建与开发

```sh
git clone https://github.com/Turbo1123/roubao.git
cd roubao/ios
rustup target add aarch64-apple-ios
scripts/build-idevice.sh
open iPhoneUse.xcodeproj
# 离线 harness / 协议测试，不使用 API Key、不操作手机
swift test --scratch-path .build/ModelTests
```

Xcode 工程、target 和 module 沿用 `iPhoneUse`。设置 `ROUBAO_BUNDLE_ID` 可统一修改主应用和两个扩展标识。签名、配对、免费团队、命令行安装、未签名 IPA 和排错见 [INSTALL.md](docs/INSTALL.md)。仓库不提供开发者的证书、账号、Key 或配对记录。

## 隐私和边界

- Key 保存在设备钥匙串；你选择的模型服务会收到任务文本、截图和相关上下文。开启 JEV/ASR 后，相应服务也接收其所需数据。
- 对话、截图、检查点和报告保存在应用沙盒，分享前检查私人内容。“沙盒”指 iOS 应用存储，不是任意代码执行平台。
- 本地 VPN 与其他 VPN 不能保证同时工作；系统可能显示“从 Mac 远程查看”开发服务提示，即使控制端就是本机。
- 需要手机解锁；后台限制、签名到期、系统升级、应用改版可能中断运行。恢复不保证外部动作恰好执行一次，状态不明确时应先核对页面。
- 不绕过登录、验证码、付款验证和系统授权。审批及重复动作保护尚未证明覆盖所有业务按钮，重要操作请在旁确认。

[安全说明](SECURITY.md) · [参与贡献](CONTRIBUTING.md) · [第三方致谢](THIRD_PARTY_NOTICES.md)

## 开源与致谢

延续上游 **[AGPL-3.0](LICENSE)**，保留原作者及第三方许可。感谢 iphone-use-app、idevice、StikDebug、LocalDevVPN/StosVPN、AgentRunKit、SwiftOpenAI，以及 ESP32 iPhone Agent、JEV 社区。[上游历史文档](docs/upstream/README.md)中的演示素材属于上游，不代表肉包 2.0 的验收。

搜索主题：**iPhone AI Agent、iOS 自动化、手机智能体、非越狱 iPhone、蜂窝网络 Agent、手机端 harness、computer use、phone use、mobile AI agent、no-jailbreak iOS automation、standalone iPhone agent、on-device orchestration**。
