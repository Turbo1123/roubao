> 旧版 Android 文档，暂未随 iPhone 2.0 更新；Android 源码仍在仓库根目录。 [肉包 2.0 for iPhone](../ios/README.md) · [项目首页](../README.md)

<p align="center">
  <img src="images/logo.png" alt="肉包 Roubao - AI Android Automation" width="120" height="120">
</p>

<h1 align="center">肉包 Roubao</h1>

<p align="center">
  <strong>首款无需电脑的开源 AI 手机自动化助手 | AI Phone Automation Assistant</strong>
</p>

<p align="center">
  基于视觉语言模型 (VLM) · 原生 Android Kotlin · 多 Agent 协作架构
</p>

<p align="center">
  <a href="ANDROID_EN.md">English</a> | 简体中文
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform">
  <img src="https://img.shields.io/badge/Min%20SDK-26-blue.svg" alt="Min SDK">
  <img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License">
  <img src="https://img.shields.io/badge/Language-Kotlin-purple.svg" alt="Language">
</p>

<p align="center">
  <img src="demo/demo.gif" width="280" alt="演示">
</p>

<p align="center">
  <img src="screenshots/1.jpg" width="180" alt="首页">
  <img src="screenshots/3.jpg" width="180" alt="能力">
  <img src="screenshots/2.jpg" width="180" alt="执行记录">
  <img src="screenshots/4.jpg" width="180" alt="设置">
</p>

---

## 项目背景

2025 年 12 月，字节跳动联合中兴发布了「豆包手机助手」，一款能够自动操作手机完成复杂任务的 AI 助手。它能帮你比价下单、批量投简历、刷视频，甚至代打游戏。

首批 3 万台工程机定价 3499 元，上线当天即告售罄，二手市场一度炒到 5000+。

**买不到？那就自己做一个。**

于是有了肉包——一个完全开源的 AI 手机自动化助手。

为什么叫「肉包」？因为作者不爱吃素。🥟

---

## 与同类项目的对比

| 特性 | 肉包 | 豆包手机 | 其他开源方案 |
|------|------|----------|--------------|
| 需要电脑 | ❌ 不需要 | ❌ 不需要 | ✅ 大多需要 |
| 需要购买硬件 | ❌ 不需要 | ✅ 需要 3499+ | ❌ 不需要 |
| 原生 Android 实现 | ✅ Kotlin | ✅ 原生 | ❌ Python |
| 开源 | ✅ MIT | ❌ 闭源 | ✅ 开源 |
| Skills/Tools 架构 | ✅ 完整 | ❓ 未知 | ❌ 无 |
| UI 设计 | ⭐⭐⭐½ | ⭐⭐⭐⭐ | ⭐⭐ |
| 自定义模型 | ✅ 支持 | ❌ 仅豆包 | ✅ 部分支持 |

### 我们解决了什么问题？

**传统的手机自动化方案痛点：**

- 必须连接电脑运行 ADB 命令
- 需要部署 Python 环境和各种依赖
- 只能在电脑端操作，手机必须通过数据线连接
- 技术门槛高，普通用户难以使用

**肉包的解决方案：**

一个 App，装上就能用。无需电脑、无需数据线、无需任何技术背景。

打开 App → 配置 API Key → 说出你想做的事 → 完成。

---

## 为什么选择肉包？

### 原生 Android 实现，不是 Python 脚本的封装

市面上几乎所有手机自动化开源项目（包括阿里的 MobileAgent）都是 **Python 实现**，需要：
- 在电脑上运行 Python 脚本
- 手机通过 USB/WiFi ADB 连接电脑
- 截图通过 ADB 传输到电脑，处理后再把操作指令传回手机

**肉包完全不同。**

我们用 **Kotlin 重写了整个 MobileAgent 框架**，原生运行在 Android 设备上：
- 截图、分析、执行全部在手机本地完成
- 无需电脑中转，延迟更低
- 利用 Shizuku 获得系统级权限，而非繁琐的 ADB 命令

### 为什么需要 Shizuku？

Android 系统出于安全考虑，普通 App 无法：
- 模拟用户点击、滑动屏幕
- 读取其他 App 的界面内容
- 执行 `input tap`、`screencap` 等系统命令

传统方案需要连接电脑执行 ADB 命令。而 **Shizuku** 是一个优雅的解决方案：

1. 通过无线调试或电脑 ADB **启动一次** Shizuku 服务
2. 之后普通 App 就可以获得 ADB 级别的权限
3. **无需 Root**，无需每次都连接电脑

这让肉包可以直接在手机上执行截图、点击、输入等操作，真正实现「一个 App 搞定一切」。

### 类 Claude Code 的 Tools/Skills 双层架构

受 [Claude Code](https://claude.ai/claude-code) 架构启发，肉包实现了 **Tools + Skills 双层 Agent 框架**：

```
用户: "帮我点份外卖"
         │
         ▼
   ┌─────────────┐
   │ SkillManager │  ← 意图识别
   └─────────────┘
         │
    ┌────┴────┐
    │         │
    ▼         ▼
🚀 快速路径    🤖 标准路径
(Delegation)  (GUI 自动化)
    │              │
    ▼              ▼
直接 DeepLink   Agent 循环
打开小美 AI     操作美团 App
```

**Tools 层（原子能力）**

底层工具集，每个 Tool 完成一个独立操作：

| Tool | 功能 |
|------|------|
| `search_apps` | 智能搜索已安装应用（支持拼音、语义） |
| `open_app` | 打开应用 |
| `deep_link` | 通过 DeepLink 跳转到 App 特定页面 |
| `clipboard` | 读写剪贴板 |
| `shell` | 执行 Shell 命令 |
| `http` | HTTP 请求（调用外部 API） |

**Skills 层（用户意图）**

面向用户的任务层，将自然语言映射到具体操作：

| Skill | 类型 | 描述 |
|-------|------|------|
| 点外卖(小美) | Delegation | 直接打开小美 AI 让它帮你点 |
| 点外卖(美团) | GUI 自动化 | 在美团 App 上一步步操作 |
| 导航(高德) | Delegation | DeepLink 直达高德搜索 |
| 生成图片(即梦) | Delegation | 打开即梦 AI 生成图片 |
| 发微信 | GUI 自动化 | 自动操作微信发消息 |

**两种执行模式：**

1. **Delegation（委托）**：高置信度匹配时，直接通过 DeepLink 打开有 AI 能力的 App（如小美、豆包、即梦），让它们完成任务。**快速、一步到位。**

2. **GUI 自动化**：没有 AI 能力的 App（如美团、微信），通过传统的截图-分析-操作循环完成。Skill 会提供操作步骤指导，提高成功率。

---

## 核心特性

### 🤖 智能 AI Agent

- 基于先进的视觉语言模型（VLM），能够"看懂"屏幕内容
- 自然语言指令，说人话就能操作手机
- 智能决策，根据屏幕状态自动规划下一步操作

### 🎨 精心设计的 UI

**这可能是所有手机自动化开源项目中 UI 做得最好看的。**

- 现代化 Material 3 设计语言
- 流畅的动画效果
- 深色/浅色主题自适应
- 精心设计的首次使用引导
- 完整的中英文双语支持

### 🔧 高度可定制

- 支持多种 VLM：阿里云通义千问、OpenAI GPT-4V、Claude 等
- 预设 API 服务商：阿里云、OpenAI、OpenRouter 一键切换
- 从 API 动态获取可用模型列表，支持模糊搜索
- 可配置自定义 API 端点，支持本地模型（Ollama、vLLM 等）

### 🔐 安全保护

- API Key 使用 AES-256-GCM 加密存储
- 检测到支付、密码等敏感页面自动停止
- 任务执行全程可视，悬浮窗显示进度
- 随时可以手动停止任务
- 可选的云端崩溃上报（可在设置中关闭）

### 🔓 Root 模式支持

当 Shizuku 以 Root 权限运行时，肉包可以启用 Root 模式：

- **Root 模式**：解锁更多系统级操作能力
- **su 命令**：允许执行 `su -c` 命令（需谨慎使用）
- **自动检测**：自动检测 Shizuku 权限等级（ADB/Root），非 Root 环境下该选项为灰色不可用

---

## 快速开始

### 前置要求

1. **Android 8.0 (API 26)** 或更高版本
2. **WiFi 网络** - Shizuku 无线调试依赖 WiFi 连接，确保手机已连接 WiFi
3. **Shizuku** - 用于获取系统级控制权限
4. **VLM API Key** - 需要视觉语言模型的 API 密钥（如阿里云通义千问）

### 安装步骤

#### 1. 安装并启动 Shizuku

Shizuku 是一个开源工具，可以让普通应用获得 ADB 权限，无需 Root。

- [Google Play](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api)
- [GitHub Releases](https://github.com/RikkaApps/Shizuku/releases)

**启动方式（二选一）：**

**无线调试（推荐，需 Android 11+）**
1. 进入 `设置 > 开发者选项 > 无线调试`
2. 开启无线调试
3. 在 Shizuku App 中选择"无线调试"方式启动

**电脑 ADB**
1. 手机连接电脑，开启 USB 调试
2. 执行：`adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh`

#### 2. 安装肉包

从 [Releases](https://github.com/Turbo1123/roubao/releases/tag/V1.4.2) 页面下载最新 APK 安装。

#### 3. 授权与配置

1. 打开肉包 App
2. 在 Shizuku 中授权肉包
3. **⚠️ 重要：进入设置页面，配置你的 API Key**

### 获取 API Key

**阿里云通义千问（推荐国内用户）**
1. 访问 [阿里云百炼平台](https://bailian.console.aliyun.com/)
2. 开通 DashScope 服务
3. 在 API-KEY 管理中创建密钥

**OpenAI（需要代理）**
1. 访问 [OpenAI Platform](https://platform.openai.com/)
2. 创建 API Key

---

## 使用示例

```
帮我点个附近好吃的汉堡
打开网易云音乐播放每日推荐
帮我把最后一张照片发送到微博
帮我在美团点一份猪脚饭
打开B站看热门视频
```

---

## 技术架构

```
┌──────────────────────────────────────────────────────────────┐
│                         肉包 App                              │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│   ┌─────────────────────────────────────────────────────┐   │
│   │                    UI 层 (Compose)                   │   │
│   │          HomeScreen / Settings / History            │   │
│   └─────────────────────────────────────────────────────┘   │
│                            │                                 │
│   ┌────────────────────────▼────────────────────────────┐   │
│   │                   Skills 层                          │   │
│   │    SkillManager → 意图识别 → 快速路径/标准路径        │   │
│   │    ┌─────────────────────────────────────────────┐  │   │
│   │    │ 点外卖 │ 导航 │ 打车 │ 发微信 │ AI画图 │ ... │  │   │
│   │    └─────────────────────────────────────────────┘  │   │
│   └─────────────────────────────────────────────────────┘   │
│                            │                                 │
│   ┌────────────────────────▼────────────────────────────┐   │
│   │                   Tools 层                           │   │
│   │    ToolManager → 原子能力封装                        │   │
│   │    ┌─────────────────────────────────────────────┐  │   │
│   │    │ search_apps │ open_app │ deep_link │ clipboard │  │
│   │    │ shell │ http │ screenshot │ tap │ swipe │ type │  │
│   │    └─────────────────────────────────────────────┘  │   │
│   └─────────────────────────────────────────────────────┘   │
│                            │                                 │
│   ┌────────────────────────▼────────────────────────────┐   │
│   │                  Agent 层                            │   │
│   │    MobileAgent (移植自 MobileAgent-v3)               │   │
│   │    ┌───────────┬───────────┬───────────┬──────────┐ │   │
│   │    │  Manager  │ Executor  │ Reflector │ Notetaker│ │   │
│   │    │  (规划)   │  (执行)   │  (反思)   │  (记录)  │ │   │
│   │    └───────────┴───────────┴───────────┴──────────┘ │   │
│   └─────────────────────────────────────────────────────┘   │
│                            │                                 │
│   ┌────────────────────────▼────────────────────────────┐   │
│   │                  VLM Client                          │   │
│   │           Qwen-VL / GPT-4V / Claude                  │   │
│   └─────────────────────────────────────────────────────┘   │
│                            │                                 │
├────────────────────────────┼────────────────────────────────┤
│                            ▼                                 │
│   ┌─────────────────────────────────────────────────────┐   │
│   │                    Shizuku                           │   │
│   │              System-level Control                    │   │
│   │     screencap │ input tap │ input swipe │ am start  │   │
│   └─────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

### 工作流程

```
用户输入指令
      │
      ▼
┌─────────────────┐
│  Skills 匹配     │ ← 检查是否有对应的 Skill
└─────────────────┘
      │
      ├── 高置信度 Delegation Skill ──▶ 直接 DeepLink 跳转 ──▶ 完成
      │
      ▼
┌─────────────────┐
│  标准 Agent 循环 │
└─────────────────┘
      │
      ▼
   ┌──────────────────────────────────────────────┐
   │  1. 截图 - Shizuku screencap                 │
   │  2. Manager 规划 - VLM 分析当前状态          │
   │  3. Executor 决策 - 确定下一步操作           │
   │  4. 执行动作 - tap/swipe/type/open_app       │
   │  5. Reflector 反思 - 评估操作效果            │
   │  6. 循环直到完成或安全限制                   │
   └──────────────────────────────────────────────┘
```

### 项目结构

```
app/src/main/java/com/roubao/autopilot/
├── agent/                    # AI Agent 核心 (移植自 MobileAgent-v3)
│   ├── MobileAgent.kt        # Agent 主循环
│   ├── Manager.kt            # 规划 Agent
│   ├── Executor.kt           # 执行 Agent
│   ├── ActionReflector.kt    # 反思 Agent
│   ├── Notetaker.kt          # 笔记 Agent
│   └── InfoPool.kt           # 状态池
│
├── tools/                    # Tools 层 - 原子能力
│   ├── Tool.kt               # Tool 接口定义
│   ├── ToolManager.kt        # 工具管理器
│   ├── SearchAppsTool.kt     # 应用搜索
│   ├── OpenAppTool.kt        # 打开应用
│   ├── DeepLinkTool.kt       # DeepLink 跳转
│   ├── ClipboardTool.kt      # 剪贴板操作
│   ├── ShellTool.kt          # Shell 命令
│   └── HttpTool.kt           # HTTP 请求
│
├── skills/                   # Skills 层 - 用户意图
│   ├── Skill.kt              # Skill 接口定义
│   ├── SkillRegistry.kt      # Skill 注册表
│   └── SkillManager.kt       # Skill 管理器
│
├── controller/               # 设备控制
│   ├── DeviceController.kt   # Shizuku 控制器
│   └── AppScanner.kt         # 应用扫描 (拼音/语义搜索)
│
├── vlm/                      # VLM 客户端
│   └── VLMClient.kt          # API 调用封装
│
├── ui/                       # 用户界面
│   ├── screens/              # 各个页面
│   ├── theme/                # 主题定义
│   └── OverlayService.kt     # 悬浮窗服务
│
├── data/                     # 数据层
│   └── SettingsManager.kt    # 设置管理
│
└── App.kt                    # Application 入口

app/src/main/assets/
└── skills.json               # Skills 配置文件
```

---

## 路线图

### 已完成 (v1.x)

- [x] **原生 Android 实现** - Kotlin 重写 MobileAgent，摆脱 Python 依赖
- [x] **Tools 层** - 原子能力封装（search_apps、deep_link、clipboard 等）
- [x] **Skills 层** - 用户意图映射，支持 Delegation 和 GUI 自动化两种模式
- [x] **智能应用搜索** - 拼音、语义、分类多维度匹配
- [x] **快速路径** - 高置信度 Skill 直接 DeepLink 跳转

### 🚀 v2.0 开发中

> 正在开发的重大更新，目前在 `roubao2.0+AccessibilityService` 分支

- [ ] **无障碍服务混合模式** - 集成 AccessibilityService，实现更精准的 UI 操作
  - 优先使用元素索引点击（不受屏幕变化影响）
  - 智能回退：索引模式失败时自动切换到坐标模式
  - 无需 Root，进一步降低使用门槛

- [ ] **UI 树感知** - Agent 能够获取完整的 UI 结构
  - 识别可点击元素、输入框、滚动区域
  - 为 LLM 提供结构化 UI 上下文
  - 减少纯视觉误判

- [ ] **宏脚本系统** - 录制、存储、回放操作序列
  - 将执行过程录制为可重复播放的脚本
  - 支持循环播放、延时控制
  - 脚本管理界面（新增"脚本"导航页）

- [ ] **设置增强**
  - 无障碍服务开关与引导
  - 混合模式状态展示

### 近期计划

- [ ] **MCP (Model Context Protocol)** - 接入更多能力扩展，如日历、邮件、文件管理等
- [ ] **执行录屏** - 保存任务执行过程视频，方便回顾和调试
- [ ] **更多 Skills** - 扩充内置 Skills，支持用户自定义

### 中期计划

- [ ] **更多设备支持** - 适配更多 Android 设备和定制系统（MIUI、ColorOS、HarmonyOS 等）
- [ ] **本地模型** - 支持在设备端运行小型 VLM，实现离线使用
- [ ] **任务模板** - 保存和分享常用任务

### 长期愿景

- [ ] **多应用协作** - 跨 App 联动完成复杂工作流
- [ ] **智能学习** - 从用户操作习惯中学习，优化执行策略
- [ ] **语音控制** - 语音唤醒和语音指令

---

## 开发

### 环境要求

- Android Studio Hedgehog 或更高版本
- JDK 17
- Android SDK 34

### 构建

```bash
# 克隆仓库
git clone https://github.com/yourusername/roubao.git
cd roubao

# 构建 Debug 版本
./gradlew assembleDebug

# 安装到设备
./gradlew installDebug
```

---

## 问题反馈

遇到崩溃或 Bug？请通过以下方式反馈：

### 导出日志

1. 打开肉包 App → 设置
2. 找到「反馈与调试」分组
3. 点击「导出日志」
4. 选择分享方式（微信、邮件等）发送给开发者

### 日志包含的信息

- 设备型号和 Android 版本
- 应用版本号
- 崩溃堆栈信息（如有）
- 操作日志

> 💡 日志文件不包含您的 API Key 或个人隐私信息

### 提交 Issue

请在 [GitHub Issues](https://github.com/Turbo1123/roubao/issues) 提交问题，附上：
- 问题描述
- 复现步骤
- 导出的日志文件

---

## 贡献

欢迎提交 Issue 和 Pull Request！

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add some amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 开启 Pull Request

---

## 许可证

本项目基于 MIT 许可证开源。详见 [LICENSE](../LICENSE) 文件。

---

## 致谢

- [MobileAgent](https://github.com/X-PLUG/MobileAgent) - 阿里达摩院 X-PLUG 团队开源的移动端 Agent 框架，为本项目提供了重要的技术参考
- [Shizuku](https://github.com/RikkaApps/Shizuku) - 优秀的 Android 权限管理框架

---

<p align="center">
  Made with ❤️ by Roubao Team
</p>
