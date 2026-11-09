# Contributing / 参与贡献

Please include a concrete problem, expected behavior, and a small reproducible change. Preserve the AGPL-3.0 license, original notices, and third-party attribution. Contributions are under the repository's existing license.

首次开发请按 [中文安装](docs/INSTALL.md) 或 [English setup](docs/INSTALL.en.md) 配置自己的 Bundle ID、签名和模型。不要提交证书、Key、设备配对、个人日志或真实账号截图。

```sh
swift test --scratch-path .build/ModelTests
```

For device/UI changes, also build the iOS target and state what was actually tested: simulator, physical device/OS, VPN mode, cellular/Wi-Fi, model, expected/actual results. Offline success is not physical-device acceptance. Reversible documentation/layout changes do not need artificial implementation-mirroring tests.

Useful contributions include cellular cold-reconnection tests, free-team installation validation, visual grounding/stall regressions with synthetic fixtures, input focus/replacement checks, streaming ASR handling, accessibility, and English UI localization. Keep final decisions and recovery with the primary model; do not add silent task-ending fallbacks, fixed hidden step stops, repeated submission retries, or task-specific authorization exceptions.

Public issue/PR discussion may use Chinese or English. Maintainer automation follows the local `AGENTS.md` main-branch workflow; external contributors can propose changes through GitHub's usual contribution mechanisms.
