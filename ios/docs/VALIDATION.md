# Validation and known limits / 验证与边界

Release preparation: **2026-11-09**. This is a developer preview, not a general automation reliability benchmark. 本页区分离线检查、历史真机观察和未完成验收；源码发布并不代表所有任务稳定成功。

| Area / 项目 | Evidence / 证据 | Limit / 限制 |
|---|---|---|
| Harness/protocol | 87 offline tests passed before release preparation; current release checks recorded in release notes | No real model or phone actions in offline tests |
| iOS build | Development built with Xcode 27.1; deployment target iOS 26 | Does not validate all iOS 26+ devices |
| Physical devices | Development used iPhone 15 Pro Max and 17 Pro Max on iOS 27.0.1 | Device-specific observations, not a compatibility certification |
| Native task loop | Calculator 23×17 returned 391 through the on-phone AgentRunKit loop | Does not establish arbitrary app task success |
| Cellular | Helper route implemented; user observed a Xiaohongshu task running over the cellular setup | Reboot/cold reconnect and carrier combinations need further controlled testing |
| Research/PDF | Development produced a three-platform shopping comparison PDF and a follow-up summary in the same conversation | Visible offers were not guaranteed lowest checkout prices; no purchase acceptance |
| Streaming ASR | Synthetic audio produced partial editable transcription on the test phone | Real acoustic conditions and accents not benchmarked |
| Paste permission | Controlled in-app test triggered a paste prompt, clicked Allow through the driver, and checked exact synthetic field text | This does not prove arbitrary system prompts or every app's whole-field replacement |
| Visual grounding | Synthetic image-only target probe worked; offline recovery and target freshness cases pass | Not a general localization accuracy benchmark |
| Latest coffee loop fix | Offline dynamic-page examples and private recorded-frame replay tested structural stall detection | Coffee ordering was NOT rerun end to end after the fix; checkout/payment not accepted |
| Messaging/sharing | A development flow reached actual file delivery; duplicate submission was also observed and informed guards | Reliable unattended messaging is NOT claimed; important outcomes need owner verification |
| Free Apple ID | Build switch and external VPN path inherited/documented | Full end-to-end free-team installation not accepted in this release |

## Known limits / 已知限制

- **Setup and maintenance:** Mac + USB initially. Signing expiry, trust resets, invalid pairing, and OS updates may require renewed setup. No claim of permanently computer-free installation.
- **Connectivity:** Local VPN required; other VPNs can conflict. Cellular helper is a routing workaround, not an offline model. First DDI download depends on network/source availability.
- **Background and lock:** iOS controls scheduling and may stop the app. The phone must be unlocked for UI operation. Live Activities are system-throttled.
- **Model/provider:** Vision/tool-call quality varies. JEV returned HTTP 451 during a development run; the exact provider-side cause was not established. Availability, latency, price, and supported regions depend on your provider.
- **Input and vision:** Clipboard readback verifies transport, not native field contents. OCR is not a UI tree; clicks, focus, replacement, dynamic pages, and modal dialogs remain fallible.
- **Authorization:** Approved/submitted/unknown outcomes are tracked and observed final-action labels guarded, but the system has not been proven to catch every semantically sensitive button. No autonomous account-writing, ordering, or payment guarantee.
- **Resume:** External effects and checkpoint writes are not atomic. A crash at the boundary can leave an uncertain outcome; inspect before replaying.
- **Files:** Native report/file features exist. There is no arbitrary-code sandbox or guarantee that a model-generated report is complete or factually correct.
- **Language:** Chinese and English documentation are provided. The current application interface is primarily Chinese, with some inherited English strings; a fully localized English app UI is not claimed.

Private logs, pairing records, keys, and personal screenshots are deliberately excluded from this source release. Public reproducibility starts with the offline tests and the setup guide; historical private-device observations are reported with their limits rather than shipped as raw user data.
