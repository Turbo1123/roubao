# Source provenance and acknowledgments / 来源与致谢

Roubao 2.0 is derived from **[EliotAndres/iphone-use-app](https://github.com/EliotAndres/iphone-use-app)**, not a clean-room implementation of its device connection or application foundation. The original repository's AGPL-3.0 [LICENSE](LICENSE) is retained, along with source notices and app credits. The local imported upstream baseline was `20ac0665eea956f8fc3f96115f781b0ee9cdde9e`. This public repository begins with a reviewed source snapshot rather than redistributing local device/setup history.

Roubao additions include AgentRunKit integration, Chinese task UX, continuation, user question cards and notifications, budget extension, scoped authorization and files, native PDF reporting/sharing, streaming ASR drafts, JEV assistance, and visual/stall recovery. Existing upstream capabilities are credited, including on-phone control, pairing, local VPN, developer image handling, Live Activities, and cellular helper foundations. We do not claim invention of all these mechanisms or verified worldwide priority.

| Project | Use / acknowledgment |
|---|---|
| [iphone-use-app](https://github.com/EliotAndres/iphone-use-app) | Application and on-phone device-control foundation; AGPL-3.0 |
| [idevice](https://github.com/jkcoxson/idevice) | Rust device services and pairing; MIT; pinned revision in build script, local patch included |
| [StikDebug](https://github.com/StephenDev0/StikDebug) | Tunnel, pairing, and developer-image foundations credited by upstream |
| [LocalDevVPN](https://github.com/jkcoxson/LocalDevVPN) / [StosVPN](https://github.com/SideStore/StosVPN) | Loopback VPN foundations and optional external VPN |
| [AgentRunKit](https://github.com/Tom-Ryder/AgentRunKit) | Agent loop, checkpoints, approvals; MIT |
| [SwiftOpenAI](https://github.com/jamesrochabrun/SwiftOpenAI) | Compatible model protocol client; MIT |
| [ESP32 iPhone Agent by Henry Yuan](https://github.com/onehumanbeing/esp32-iphone-agent) | Design reference for model configuration, local observation, and specialized visual decisions; no hardware dependency here |
| [Browser Use / JEV Ultrafast](https://github.com/browser-use/jev-ultrafast), [typesafe-computer-use](https://github.com/awlevin/typesafe-computer-use) | Indexed action-space and execution design references |
| [DeveloperDiskImage](https://github.com/doronz88/DeveloperDiskImage) | Developer-image download source; Apple images are not bundled in this repository |

Additional bundled notices are in [iPhoneUse/Resources/ThirdPartyNotices.txt](iPhoneUse/Resources/ThirdPartyNotices.txt). Dependency source and license notices remain applicable; this table does not relicense third-party projects.

Archived upstream README/feature notes live in `docs/upstream/`. Demo video, GIF, and original screenshots in `docs/` came from the upstream repository; they are not newly captured Roubao 2.0 demos or current reliability evidence. Brand names belong to their respective owners. Roubao is not officially affiliated with Apple, Doubao/ByteDance, or the model providers.
