# License scopes / 分平台许可

This repository contains separately organized Android and iOS source trees. Their existing licenses are retained; no single MIT label should be read as relicensing the iOS code.

| Scope | License |
|---|---|
| Legacy Android code at the root (`app/`, Gradle files, original Android docs/assets) | Existing [MIT license](LICENSE), Copyright (c) 2025 Roubao Team |
| Roubao 2.0 for iPhone under `ios/`, derived from EliotAndres/iphone-use-app | [AGPL-3.0](ios/LICENSE), with upstream and third-party notices retained |
| Third-party components | Their original licenses and notices; see [iOS source provenance](ios/THIRD_PARTY_NOTICES.md) |

根目录 `LICENSE` 保留 Android 原有 MIT 授权，不覆盖 `ios/` 的 AGPL-3.0 源码。iPhone 代码、原作者署名与第三方声明均独立保留。请按实际使用的平台和依赖查看对应许可。
