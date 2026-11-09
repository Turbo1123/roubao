# Roubao 2.0: complete iPhone setup guide

[中文](INSTALL.md) · [Home](../README.en.md)

This guide covers building and signing your own copy. **Initial setup requires a Mac and USB. Runtime does not require a computer, Wi-Fi, jailbreak, or an external controller. No WDA/WebDriverAgent, Appium server, or XCTest runner is needed.** This is a source release, not a universally installable signed IPA.

## 1. Prerequisites

| Item | Requirement |
|---|---|
| iPhone | Deployment target iOS 26. Development devices: iPhone 15/17 Pro Max on iOS 27.0.1. Other combinations need testing |
| Mac | Full Xcode supporting the phone's OS. Release preparation used Xcode 27.1; Command Line Tools alone are insufficient |
| USB cable | Initial trust, installation, and pairing; disconnect after setup |
| Apple Account | Free Personal Team, or paid team with Network Extension provisioning |
| Rust | rustup, cargo, and the aarch64-apple-ios target; access to GitHub, SwiftPM, and crates.io |
| Model API | Your own key and a working tool-calling model with vision |
| Phone internet | Wi-Fi or cellular; cellular reconnection may require the shortcut in section 7 |

Install [Xcode](https://developer.apple.com/xcode/) and [Rust](https://rustup.rs/). Open Xcode once to accept its license and install components. If necessary, select your full Xcode installation (adjust its path):

```sh
sudo xcode-select --switch /Applications/Xcode.app/Contents/Developer
xcodebuild -version
rustup target add aarch64-apple-ios
```

If dependency compilation reports missing pkg-config, libusb, or OpenSSL, install the corresponding development libraries. With Homebrew: `brew install pkg-config libusb openssl@3`.

## 2. Trust the Mac and enable Developer Mode

1. Connect USB, unlock the phone, tap **Trust This Computer**, and personally enter your device passcode if requested.
2. Open Xcode → **Window → Devices and Simulators**. Wait until the phone is recognized and prepared.
3. On iPhone, open **Settings → Privacy & Security → Developer Mode** and enable it.
4. Restart when prompted, unlock, and confirm Developer Mode after restart.
5. If the switch is missing, first let Xcode recognize the device or attempt a development installation, then check again.

[Apple's Developer Mode documentation](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device). Trust, Developer Mode, and app signing are separate requirements.

## 3. Clone and build the native device library

```sh
git clone https://github.com/Turbo1123/roubao.git
cd roubao/ios
rustup target add aarch64-apple-ios
scripts/build-idevice.sh
```

The script downloads pinned `jkcoxson/idevice @ 3854a5d`, applies the included patch, and builds `iPhoneUse/idevice/libidevice_ffi.a`. The first build takes time. `.build/` contains generated/local files and must not be committed.

## 4. Choose signing and VPN mode

| Signing | Build setting | On the phone |
|---|---|---|
| Paid team with Network Extension provisioning | `BUILTIN_VPN=YES` (default) | Allow Roubao's built-in VPN configuration; profiles must permit Packet Tunnel Network Extension |
| Free Personal Team | `BUILTIN_VPN=NO` | Separately install/enable [LocalDevVPN](https://apps.apple.com/app/localdevvpn/id6755608044), then select it in Roubao |

Free profiles normally expire after seven days and require rebuilding/reinstalling. See [Apple's Personal Team rules](https://developer.apple.com/help/account/basics/about-your-developer-account). Paid signing also expires. LocalDevVPN availability depends on your App Store region. The free-team branch has not completed full physical-device acceptance testing for this release.

In Xcode:

1. Open `iPhoneUse.xcodeproj`. Sign into your account under Xcode → Settings → Apple Accounts.
2. Set the project Build Setting **`ROUBAO_BUNDLE_ID`** to your own unique identifier, e.g. `com.yourname.roubao` (replace the placeholder).
3. The app, AgentWidget, and TunnelProvider use that identifier, `.AgentWidget`, and `.TunnelProvider`. Do not change only one target.
4. Select the same team and automatic signing for all three targets, or set project-level `DEVELOPMENT_TEAM`.
5. For a free team, set `BUILTIN_VPN=NO` for both Debug and Release; this removes the Network Extension entitlements.
6. Select **iPhoneUse scheme → your physical iPhone**, then Run.

If iOS requests developer trust, follow **Settings → General → VPN & Device Management** for your own developer identity. Keep the phone unlocked and complete system prompts yourself.

### Optional CLI build and installation

Create a local, ignored `.build/Signing.xcconfig`; replace every placeholder:

```xcconfig
DEVELOPMENT_TEAM = YOUR_TEAM_ID
ROUBAO_BUNDLE_ID = com.yourname.roubao
BUILTIN_VPN = YES
```

Use `NO` for a free team. First sign into Xcode and establish the necessary provisioning, then:

```sh
xcrun devicectl list devices
xcodebuild -project iPhoneUse.xcodeproj -scheme iPhoneUse \
  -configuration Release -destination 'generic/platform=iOS' \
  -derivedDataPath .build/DeviceBuild -xcconfig .build/Signing.xcconfig \
  -allowProvisioningUpdates build
xcrun devicectl device install app --device 'YOUR_DEVICE_ID' \
  .build/DeviceBuild/Build/Products/Release-iphoneos/iPhoneUse.app
```

Replace the device ID with your device. Do not put Apple account passwords in scripts or the repository.

## 5. Generate this phone's Remote Pairing record

Keep USB connected, trust complete, Developer Mode enabled, and the phone unlocked. From the repository root:

```sh
cargo build --manifest-path .build/idevice/Cargo.toml \
  --release -p idevice-tools --bin idevice-tools --no-default-features --features ring
mkdir -p .runtime
umask 077
.build/idevice/target/release/idevice-tools --udid 'YOUR_DEVICE_UDID' \
  remotepairing_lockdown pair iPhoneUse .runtime/pairingFile.plist
chmod 600 .runtime/pairingFile.plist
```

Use the phone's **UDID from Xcode device details**, not its name or a Bonjour identifier. Keep the hostname `iPhoneUse`, which matches the current implementation. Specify the intended device when several are connected. Confirm the command wrote the file successfully; a hang or empty file is not success.

Transfer the file privately through AirDrop/Files and use **Roubao → Settings → Pairing File → Import**. Alternatively, copy it directly into the installed app container:

```sh
xcrun devicectl device copy to --device 'YOUR_DEVICE_ID' \
  --domain-type appDataContainer --domain-identifier 'com.yourname.roubao' \
  --source .runtime/pairingFile.plist --destination Documents/pairingFile.plist
```

Use your actual app Bundle ID, then reopen Roubao. Confirm its imported status. **Pairing records contain private trust credentials. Never upload them to GitHub, issues, public drives, or other users.** They are device-specific. Trust resets, Developer Mode changes, OS updates, or invalid records may require regeneration; another phone always needs its own record.

## 6. Connect to this iPhone

1. Open Settings → Phone Connection and select the VPN mode chosen above.
2. For built-in mode, allow the VPN configuration and personally complete any passcode prompt. A dismissed prompt alone does not prove the configuration was saved; inspect the actual connection state.
3. For LocalDevVPN, start its VPN in that app, then reconnect in Roubao. Built-in errors do not silently switch modes.
4. Allow Local Network access. For background operation, follow the app's background permission setup. “Always” location access serves the current keep-alive approach, not a verified Maps feature.
5. Wait for the first Developer Disk Image (DDI) download and mount. Already-mounted images are reused. Diagnose download errors directly instead of repeatedly re-signing.

This VPN routes local developer-service traffic back to the same phone. It is not a remote model proxy. It may displace another VPN. The system may report that the device is viewed remotely from a Mac even when the controller is this app on this phone.

## 7. Cellular-only operation, with Wi-Fi off

1. Disable Wi-Fi in Settings, enable cellular, and check that the app/model/Shortcuts are allowed to use data.
2. In Roubao's home connection notice or **Settings → Phone Connection**, tap the action to add the cellular helper.
3. Review and add **`[iPhone Use] Toggle Cellular`** in Shortcuts. Keep its exact name. Current [shortcut link](https://www.icloud.com/shortcuts/3b7bbc4cd1f243968423cfb0e40ed5b6).
4. Return to Roubao and use the “helper added / connect” action, allowing normal shortcut prompts.
5. Roubao first tries a direct connection. If needed, the helper temporarily disables cellular while establishing the local tunnel, then restores data for model access. **No Wi-Fi network is required.**
6. If restoration is unconfirmed, manually re-enable cellular in Control Center and verify connectivity. A canceled shortcut or missing callback is not success.

If the link is unavailable, create the same-name shortcut: **If Shortcut Input has any value → Set Cellular Data On; Otherwise → Set Cellular Data Off.** Use explicit On/Off, not Toggle. This assists local routing; it does not make cloud inference work offline. Validate cellular cold reconnection, reboots, and your carrier on your own device.

## 8. Configure models, optional JEV, and voice

- **Settings → AI Model:** choose a supported provider or custom compatible API, then enter your Base URL, key, and exact model ID. Follow the provider's URL format; a full `/chat/completions` endpoint is not usually a Base URL. Run the connection test and read its actual errors.
- The primary model needs working tool calls; image grounding requires vision support. “OpenAI compatible” does not guarantee either. A passing probe does not establish complex-task success.
- **Optional JEV:** Settings → Task Execution → JEV visual grounding. Add your own TypeSafe key. It is optional; the primary model remains available. Nonretryable 401/403/404/451 responses hand control back for that run. Service region/access rules are provider-dependent. [Technical details](JEV.md)
- **Optional voice:** configure your Doubao/Volcengine ASR API key and the required provider-side product access. Allow Microphone, record, watch partial text arrive, then edit and manually send. Text tasks do not require ASR.
- Allow notifications for waiting questions and budget extensions. Dynamic Island requires supported hardware and Live Activities enabled.

Keys live in the phone's Keychain. Your chosen cloud services receive the relevant task/screenshots/audio. This release does not include anyone's API account.

## 9. Verify independent execution

Run “Open Calculator, clear it, calculate 23×17, and read the result.” Expect **391**. Then:

1. Disconnect USB and shut down the Mac; repeat.
2. Keep Wi-Fi off and cellular on, actively reconnect, and repeat. An existing connection is not a cold-reconnection test.
3. Check a question card, Stop, task continuation, and a PDF follow-up.
4. Keep the screen awake initially; iOS can limit background execution. Your hardware/OS combination is verified only after your own tests pass.

## 10. Troubleshooting

| Symptom | Action |
|---|---|
| Developer Mode missing | USB trust, prepare in Xcode Devices, restart/confirm; consult Apple guidance |
| Network Extension signing error | Check team entitlement; free team uses `BUILTIN_VPN=NO` and LocalDevVPN |
| Bundle ID unavailable | Choose your own `ROUBAO_BUNDLE_ID`; keep both extension suffixes aligned |
| VPN not saved after Allow | Complete any passcode prompt and inspect actual system VPN state and original error |
| Asked to turn on Wi-Fi | Add/run the section 7 helper; no Wi-Fi router is required |
| Pairing invalid/expired | Verify phone and hostname, then regenerate/import for that phone |
| DDI download failed | Check connectivity to the download source and retry; do not reset trust just for a download error |
| Cannot act while locked | Unlock the phone; this is a system constraint |
| Free build stops opening | Re-sign/reinstall after profile expiration; back up files first rather than uninstalling |
| Model 401/403/451 or unsupported tools/images | Check key, region/access, model capability, and Base URL; do not post credentials |
| Missed clicks/stalled page | Keep redacted logs, allow fresh primary-model grounding; do not blindly replay an uncertain final action |
| Text did not appear | Check focus, paste permission, and a new screenshot; clipboard readback is not field-value verification |

## Development and unsigned IPA packaging

```sh
# No signing account used; output cannot be installed directly
xcodebuild -project iPhoneUse.xcodeproj -scheme iPhoneUse \
  -configuration Release -destination 'generic/platform=iOS' \
  -derivedDataPath .build/UnsignedBuild CODE_SIGNING_ALLOWED=NO build
scripts/package-ipa.sh \
  "$PWD/.build/UnsignedBuild/Build/Products/Release-iphoneos/iPhoneUse.app" \
  "$PWD/output/Roubao-2.0-unsigned.ipa"
swift test --scratch-path .build/ModelTests
```

Unsigned IPAs still require your own signing and entitlements. Do not publish developer profiles, device lists, keys, pairing records, or private screenshots inside packages. A simulator checks UI only, not CoreDevice/VPN: use `rustup target add aarch64-apple-ios-sim` and `scripts/build-idevice.sh sim`, then configure its library path as described in the archived upstream development notes.
