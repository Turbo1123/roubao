# Security and privacy / 安全与隐私

Roubao controls the phone you install it on through developer services. Use your own device and accounts. Keep Developer Mode, VPN, pairing, and model access under your control.

- Never post API keys, pairing plists, signing certificates/private keys, provisioning profiles, account passwords, verification codes, or complete private app containers.
- 配对文件是设备信任凭据，不是可以共享的配置模板。API Key 使用自己的账号，在手机设置中填写，保存在钥匙串。仓库不附送任何凭据。
- Model requests include task text, screenshots, and relevant history. Optional JEV receives its decision input; cloud ASR receives audio. Their privacy/retention policies apply.
- Conversations, screenshots, reports, and checkpoints can contain personal information. Native sharing sends the files you choose; inspect them first.
- Device control is powerful and fallible. Approval and duplicate-action guards are incomplete defenses, not a guarantee of safe unattended financial/account actions. System authentication is not bypassed.
- Stop the task if outcomes are uncertain. Inspect actual app state before retrying a send/order. Revoke affected keys and device trust if private credentials were exposed.

## Reporting

For ordinary defects, open an Issue with redacted device/OS/build information and a minimal reproduction. **Do not include a working exploit against another person, a live credential, or personal task evidence.** For sensitive vulnerabilities, use the repository's private vulnerability-reporting feature if available; otherwise open a minimal issue requesting a private contact channel without revealing the sensitive detail. No guaranteed response-time commitment is made.

Before contributing, scan changes locally. `.gitignore` protects common generated/local files but cannot detect every credential. The release source snapshot was checked with Gitleaks; that is a check, not proof that all sensitive data is impossible to include.
