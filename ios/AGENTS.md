# iphone-use-app

An iPhone app where an LLM controls the phone it runs on: type a prompt (or run
the "Run Agent" Shortcut) and it loops CoreDevice screenshot -> chat completion tool
call -> CoreDevice tap/swipe/type/open-app until done. No Mac or board at run
time. Background, design notes and gotchas live in Notion: "Controlling iphone".

It reaches its own phone's developer services through a loopback VPN (our `TunnelProvider`
extension, based on LocalDevVPN/StosVPN) + an RP pairing file, using the Rust lib
[idevice](https://github.com/jkcoxson/idevice). Feature scope: `docs/FEATURES.md`.

## Git

Always commit on `main`, no feature branches or PRs. From a worktree, commit there, then
rebase onto `main` and fast-forward it (`git -C <main checkout> merge --ff-only <branch>`).

## Layout

- `iPhoneUse/Agent/`: the ChatGPT-style chat screen + chat list drawer (`AgentView`), loop + history (`PhoneAgent`), `LLMSettings` (provider, Keychain key, model), `RunAgentIntent`
- `iPhoneUse/Settings/`: Settings (a sheet from the chat's gear), model picker
- `iPhoneUse/Device/`: `Connection` (VPN -> tunnel -> DDI), `DeviceSession` (tunnel, heartbeat), `DeviceSession+Services.swift` (apps, DDI, `agentScreenshot/Touch/Home/Type/Launch`)
- `TunnelProvider/`: loopback packet-tunnel extension
- `AgentWidget/`: Live Activity; `Shared/`: files compiled into both app and widget (`StopAgentIntent` uses `#if WIDGET`)
- `idevice-patch/`: our additions to idevice's FFI (`probe.rs`: screenshot, touch, buttons, keyboard)

## Build

1. `scripts/build-idevice.sh` (once, or after changing `idevice-patch/`): builds `iPhoneUse/idevice/libidevice_ffi.a`, which is gitignored (45 MB).
2. `xcodebuild -project iPhoneUse.xcodeproj -scheme iPhoneUse -destination id=<udid> -allowProvisioningUpdates build`, then `xcrun devicectl device install app`.
   Free Apple ID: add `BUILTIN_VPN=NO` (no Network Extension entitlement) and use LocalDevVPN.

Simulator (UI only, no device): `scripts/build-idevice.sh sim`, then build `-configuration Debug`
with `LIBRARY_SEARCH_PATHS='$(PROJECT_DIR)/iPhoneUse/idevice/sim'` and launch with `-demoChat YES`
(fake run, see README). Argent can't install from the session scratchpad; copy the `.app` into
`.build/` (gitignored) first.

On the phone, once: Developer Mode on, import a pairing file
(`idevice-tools rppairing pair <name> <out>` over USB), allow the VPN configuration prompt,
add an API key in Settings. The DDI downloads and mounts on its own.

## Testing without an LLM

`DEV=<udid> scripts/runscript.sh "open com.apple.Preferences; wait 1.5; tap 0.5 0.9; shot a" a`
launches the app with `-agentScript`, waits for "script done" in `Documents/agent.log`, and pulls the screenshots.
`-agentPrompt "..."` as a launch argument starts a real LLM run.

## Decisions

- (2026-10-19) idevice is not vendored: `idevice-patch/` + `scripts/build-idevice.sh` rebuild it from jkcoxson/idevice @ 3854a5d.
- (2026-10-19) Display name is "iphone-use-app". Project/target/module `iPhoneUse`, bundle id `blog.eliot.iphoneuse` (re-import the pairing file after a bundle id change).
- (2026-10-19) Don't describe this repo as a fork of StikDebug; it is credited in the README and the in-app Credits.
- (2026-10-19) No tab bar: one ChatGPT-style Agent screen, Settings opens from its top-right gear (see `docs/FEATURES.md`). Each run is one chat in the list; runs don't share context with the model. The loopback VPN ships in the app (LocalDevVPN optional, see below); no target IP / port settings (peer is always `10.7.0.1`, port via Bonjour).
- (2026-10-19) idevice is built with only the features the app calls, and `ring` instead of `aws-lc` (see `scripts/build-idevice.sh`).
- (2026-11-08) VPN setup errors must preserve the selected mode and report the original error. Do not silently switch to LocalDevVPN after a refused/failed save. This supersedes the automatic fallback described in the older VPN decision below. Check whether DDI is already mounted before downloading it.
- (2026-10-19) Two VPN modes, same peer `10.7.0.1`: Built-in (`TunnelProvider/`, needs the Network Extension entitlement, so a paid team) and LocalDevVPN (the App Store app, for free Apple IDs). Build switch `BUILTIN_VPN` (default YES); `BUILTIN_VPN=NO` signs the app and extension with no entitlements. Runtime picker in Settings → Phone Connection (`vpnMode` in UserDefaults); a NEVPNError from the built-in VPN switches to LocalDevVPN on its own.

- (2026-11-08) Conversations continue after completion/PDF creation. Reuse that conversation's history/evidence/files; only New Chat starts fresh. This supersedes the older one-run-per-chat decision. Never inherit another chat's latest checkpoint.
- (2026-11-08) Every uncertain JEV/OCR/input/tool outcome returns to the primary model; keep its full tool set. Default 100 steps, then notify and wait for the owner to approve +50, pause or stop. Preserve completed work and explicit continuation.
- (2026-11-08) Pending owner questions are bound to conversation ID + task folder + current turn, never the chat's first prompt or an unrelated global latest task. Four unchanged UI attempts suspend JEV and give the primary model explicit stall evidence. Final completion is reviewed by the same primary model against the latest request.
- (2026-11-08) After TWO unchanged locating clicks or two uncertain locators, suspend the OCR/coordinate route. Keep decisions with the primary model, require a dedicated image-only locate_visual_target observation and one-use tap_visual_target, invalidate on screen changes. This supersedes the four-action-only recovery threshold; generic four-action stall reporting remains as additional evidence.
- (2026-11-08) Approval parsing and persistence are task/app-independent. Parse native card JSON as well as plaintext. Scope approvals to immutable operation description/app/action/non-coordinate content; preserve explicit approval on relocation, never replay submitted/unknown actions. All ordinary click routes must reject observed final-action labels instead of bypassing confirmed_action. Stop Task belongs to the owner. Resume by stable task folder and supply the complete scoped file manifest each decision.
- (2026-11-09) Distinguish structural page progress from whole-image changes. Notifications/videos/ads must not reset failed-click counters. Visual targets require same-page and target-neighborhood freshness rather than unrelated whole-frame equality. Nonretryable JEV 401/403/404/451 stays unavailable for that run; route to the primary image tool.
