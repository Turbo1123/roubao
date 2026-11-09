# Roubao 2.0 for iPhone — Open-source iPhone AI Agent

**Let AI operate your iPhone: see the screen, tap, type, and work across apps, including owner-authorized Micro-X UI control.**

**No Wi-Fi, jailbreak, computer, or extra hardware required at runtime. Run the agent independently on your iPhone, including over cellular data.**

[简体中文](README.md) · [Step-by-step setup](ios/docs/INSTALL.en.md) · [User guide](ios/docs/USAGE.en.md) · [Validation and limits](ios/docs/VALIDATION.md) · [Architecture](ios/docs/ARCHITECTURE.md)

![iOS 26+](https://img.shields.io/badge/iOS-26%2B-black)
![Swift and Rust](https://img.shields.io/badge/Swift%20%2B%20Rust-orange)
[![AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue)](ios/LICENSE)

> **Initial setup requires a Mac and USB** to build, sign, install, enable Developer Mode, and create/import a device-specific pairing file. Then unplug USB and shut down the Mac. Signing expiration or invalid pairing may require maintenance. The agent loop and device control run on the phone; **model inference uses your configured online API**, rather than an offline local LLM. This release is a developer source preview.

## Platform versions and entry points

| Platform | Status | Source and guides | License |
|---|---|---|---|
| **iPhone · Roubao 2.0** | Current developer preview | [iOS source](ios/) · [Setup](ios/docs/INSTALL.en.md) · [User guide](ios/docs/USAGE.en.md) | [AGPL-3.0](ios/LICENSE) |
| **Android · legacy** | Not updated alongside iPhone 2.0 | Root `app/` · [Legacy Android guide](docs/ANDROID_EN.md) · [Android V1.4.2](https://github.com/Turbo1123/roubao/releases/tag/V1.4.2) | [MIT](LICENSE) |

The content below describes **Roubao 2.0 for iPhone**. Existing Android source and build layout are retained; iPhone 2.0 features do not imply an Android update. [License scopes](LICENSING.md)

## Give your existing iPhone an AI operator

**Keep your iPhone. Choose your models, operate Micro-X, resume tasks, and create/share PDF reports—all from the phone you already own.**

Describe a goal. Roubao observes the screen, plans, and operates apps. When it needs your preference or approval, it notifies you, shows question cards, and resumes with your answer. It brings the idea of a Doubao-style AI phone experience to an existing iPhone without replacing the phone or attaching a controller. This independent project has no official affiliation with Doubao, ByteDance, or Apple, and makes no claim of feature equivalence.

Roubao is an **open-source iPhone AI agent, iOS automation client, and phone-use harness**, built on [iphone-use-app](https://github.com/EliotAndres/iphone-use-app) with upstream credits and AGPL-3.0 licensing preserved. Legacy Android source remains at this repository’s root; current iOS source lives in `ios/`.

## What it can do

| Capability | How it works |
|---|---|
| Cross-app control | Launch apps, capture screens, tap, long-press, swipe, enter Unicode text, and return Home |
| **Micro-X control (experimental)** | Owner-authorized UI operation and file sharing; development testing delivered a generated PDF to a specified chat |
| Research and comparison | Read map listings or product pages and organize visible information across apps |
| PDF reports | Compare matching products and visible price conditions; create reports with tables and screenshot evidence |
| Follow-up conversations | Revise a completed report or add a platform, retaining the conversation and files |
| Human-in-the-loop questions | One card at a time, back navigation, final review, and local waiting notifications |
| Long tasks and continuation | Default 100 steps; ask to add 50 rather than discard progress; checkpoints and resume |
| Dynamic Island | View stages/steps and stop tasks; iOS controls refresh frequency |
| Files and sharing | Native PDF creation, file list, dismissible QuickLook preview, and the iOS share sheet |
| Streaming voice drafts | Optional Doubao/Volcengine ASR; partial text appears in the composer, editable before sending |
| Bring your own models | Configure an OpenAI-compatible endpoint, key, and model; optional TypeSafe JEV acceleration |
| Visual recovery | OCR assists grounding; two failed locating attempts require fresh primary-model image grounding |

These are implemented capabilities, **not a guarantee of successful completion for every workflow**. Calculator, research, PDF, and voice development checks exist; reliable unattended messaging, checkout, and payment have not been established. [Validation and limits](ios/docs/VALIDATION.md)

## Micro-X support, too

Roubao can operate Micro-X with your authorization through the same screenshot, tap, input, and native sharing tools used across apps. A physical-device development flow delivered a generated PDF to a specified chat. Example: “Share the PDF from this conversation to the Micro-X chat I specify. Let me verify the recipient and file before sending.”

Micro-X support remains experimental. Verify recipients, files, and content before sending. Historical tests also exposed duplicate delivery and an unintended tap. General authorization/duplicate-action protections were added, but the full sending flow has not been revalidated afterward. Reliable unattended sending or coverage of every Micro-X screen is not claimed. See [validation and known limits](ios/docs/VALIDATION.md). Login, verification, and system permissions are not bypassed.

## Do I need WebDriverAgent?

**No. This version does not require WDA, Appium, or an XCTest runner.**

Control path: `AgentRunKit → CoreDevice / RemoteXPC → idevice → local loopback VPN`. Developer Mode, this phone's Remote Pairing record, and a local VPN are required. An ESP32, Raspberry Pi, second phone, or continuously running Mac is not. [Architecture](ios/docs/ARCHITECTURE.md)

## Install from scratch

Follow the **[complete English installation guide](ios/docs/INSTALL.en.md)**:

1. Prepare an iOS 26+ iPhone, Mac, full Xcode supporting its OS, Rust, and model API. Development devices were iPhone 15/17 Pro Max on iOS 27.0.1. iOS 26 is the deployment target, not a tested compatibility matrix.
2. Connect USB, unlock, and trust the Mac. Enable **Settings → Privacy & Security → Developer Mode**, restart, and confirm. If missing, first let Xcode recognize the device.
3. Clone this repository, run `scripts/build-idevice.sh`, and configure your signing team and unique Bundle ID.
4. A paid team with Network Extension provisioning can use the built-in VPN. A free Personal Team must use `BUILTIN_VPN=NO` and separately install/enable [LocalDevVPN](https://apps.apple.com/app/localdevvpn/id6755608044). Free profiles normally expire after seven days.
5. Build/install and generate this phone's Remote Pairing file over USB. Import it into Roubao. **Pairing records are private trust credentials; do not share or borrow them.**
6. Allow VPN configuration and Local Network permissions; wait for the initial Developer Disk Image (DDI) download and mount.
7. For cellular-only use, add **“[iPhone Use] Toggle Cellular”** from connection settings, then use the helper reconnect action. It briefly disables cellular to establish the local tunnel, then restores it. No Wi-Fi network is needed.
8. Configure/test your model. Try “Open Calculator, clear it, calculate 23×17, and read the result.” Expected: **391**.
9. Disconnect USB, turn off the Mac, keep the phone unlocked and online, and run again. Validate independent execution and cellular reconnection on your own device.

## Example tasks

```text
Open Calculator, clear it, calculate 23×17, and read the result.
Find three nearby coffee shops in Maps and summarize distance and opening information. Do not order.
Compare the same phone model and storage size on three specified shopping apps, record price conditions and screenshots, and create a PDF.
Continue the report: flag membership/coupon requirements and save a revised version.
Share the PDF to the Micro-X chat I specify. Let me verify the recipient and file before sending.
```

Missing choices such as locations or accounts should be asked inside Roubao. Review destinations and content before final actions. Do not use a real payment as your first connection test.

## Build and develop

```sh
git clone https://github.com/Turbo1123/roubao.git
cd roubao/ios
rustup target add aarch64-apple-ios
scripts/build-idevice.sh
open iPhoneUse.xcodeproj
# Offline harness/protocol tests: no API keys or phone actions
swift test --scratch-path .build/ModelTests
```

The Xcode project, target, and module remain `iPhoneUse`. Set `ROUBAO_BUNDLE_ID` to configure app and extension identifiers together. The [setup guide](ios/docs/INSTALL.en.md) covers signing, free-team builds, CLI installation, pairing, unsigned IPA packaging, and troubleshooting. No developer credentials, keys, certificates, or device trust records are distributed.

## Privacy and limits

- Keys are stored in the device Keychain. Your model receives task text, screenshots, and relevant context. Optional JEV/ASR services receive the data required by those features.
- Conversations, screenshots, checkpoints, and reports live in the app sandbox. Check personal information before sharing. This is iOS app storage, not a general code-execution sandbox.
- The local VPN may conflict with other VPNs. iOS can show a “viewed remotely from Mac” notice even when the phone controls itself.
- Keep the phone unlocked. Background limits, signing expiration, OS updates, and app UI changes can interrupt execution. Resume does not guarantee exactly-once external actions; verify ambiguous outcomes before retrying.
- Login, CAPTCHA, payment authentication, and system permissions are not bypassed. Approval and duplicate-action protection do not prove coverage of every business button. Supervise important operations.

[Security](ios/SECURITY.md) · [Contributing](ios/CONTRIBUTING.md) · [Third-party credits](ios/THIRD_PARTY_NOTICES.md)

## License and acknowledgments

Distributed under the upstream **[AGPL-3.0 license](ios/LICENSE)** with original notices retained. Thanks to iphone-use-app, idevice, StikDebug, LocalDevVPN/StosVPN, AgentRunKit, SwiftOpenAI, ESP32 iPhone Agent, and the JEV community. [Archived upstream documentation](ios/docs/upstream/README.md) and demo assets describe upstream work, not Roubao 2.0 acceptance tests.

Discovery terms: **iPhone AI agent, iOS automation, mobile AI agent, phone use, computer use, no-jailbreak iPhone automation, cellular-data agent, standalone iPhone agent, on-device agent harness, on-device orchestration, SwiftUI, Rust, CoreDevice, multimodal automation**.
