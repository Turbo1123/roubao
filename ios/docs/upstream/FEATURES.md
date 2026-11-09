# Features

What iphone-use-app does, and what it deliberately leaves out. Decided 2026-10-19.

The app is one ChatGPT-style **Agent** screen; **Settings** opens from its top-right gear. It is minimalist: anything the agent
doesn't need stays out, to keep the code and the binary small.

## Keep / add

### Agent screen
- ChatGPT-style chat: prompt bubble, the agent's reasoning as text, steps in small mono, glass input bar *(new)*
- Prompt + Run/Stop loop: screenshot → LLM tool call → tap/long_press/swipe/type/home/open_app until `done`
- Live log of each step with timings
- Connection status line (VPN / tunnel / DDI) *(new)*
- Run history: past runs in a chat list that slides in from the left *(new)*
- Red dot on the Settings gear when setup blocks a run (model, pairing file, connection)
- Installed-app list (name: bundle id) in the system prompt
- Return to the app when the run is done
- `agent.log` in Documents
- Launch args for testing: `-agentScript` (no LLM, used by `scripts/runscript.sh`) and `-agentPrompt`

### Outside the app
- Live Activity / Dynamic Island showing the current step
- Stop button in the Live Activity *(new)*
- "Run Agent" Shortcut / Siri intent, `iphoneuse://run?prompt=...` deep link (new session), guide in the drawer and Settings

### Settings
- Setup checklist on top, each row ✓ / ⚠ / ✗ and opening the screen that fixes it: AI model, pairing file,
  phone connection (reconnect, redownload DDI), run in background (location "Always" + keep-alive toggles).
  No catch-all "Advanced": every option sits under a named row.
- LLM *(new; picker modeled on [esp32-iphone-agent](https://github.com/onehumanbeing/esp32-iphone-agent))*:
  - API key, stored in the Keychain (replaces the bundled `openrouter.txt`)
  - Provider: OpenRouter or a custom OpenAI-compatible base URL
  - Model: picked from OpenRouter's model list, or typed as a free-text model ID
  - Max steps (today hard-coded to 40)
  - Editable system prompt
- Pairing file import + status row
- Redownload DDI button
- Background keep-alive: silent audio, background location
- Links: pairing file guide, GitHub repo
- Credits: idevice, LocalDevVPN/StosVPN, esp32-iphone-agent, DeveloperDiskImage

### Under the hood
- Built-in loopback VPN, reusing LocalDevVPN's packet tunnel provider, so users don't install
  LocalDevVPN *(new)*
- Or the LocalDevVPN app instead, for free Apple IDs that can't sign the Network Extension
  entitlement: `BUILTIN_VPN=NO` build, VPN picker in Phone Connection. Choose it explicitly;
  a built-in VPN save/start error preserves the chosen mode and reports the original error.
- Auto-mount the developer disk image
- Heartbeat keep-alive for the CoreDevice tunnel

### Repo + build
- Slim idevice feature set (45 MB `.a` instead of 74 MB)
- README with credits

## Left out

- Target device IP and remote pairing port fields (fixed by the built-in VPN / found by Bonjour)
- Settings: Discord link, App Folder button, Confirm JIT links,
  Always Run Scripts (TXM override), Malloc debugging
- Apps tab, JIT enabling, LiveContainer flow
- JS scripts, the script editor, bundled scripts, the CodeEditorView package
- Tools tab: console/syslog, device info, app expiry/profiles, process inspector, location simulation + map
- Enable JIT / Kill Process intents
- `stikdebug://` / `stikjit://` URL schemes, `.stiktool` document type, `_stikdebug` Bonjour services
  (keep `_remotepairing._tcp`)
- App Store icon fetcher, localization helper, haptics and accessibility announcer helpers
- Build-IPA workflow, AltSource update script, issue/PR templates, empty test targets,
  README screenshots (`assets/`), `info.md`

## Not doing

- Connecting over 127.0.0.1 without a VPN: skipped; go straight to the built-in VPN.

## Notes from the implementation

- Run history is in the Agent tab's toolbar (clock icon): prompt, outcome, full log, "Use Prompt".
- The API key is stored per provider, so switching provider doesn't lose either key.
- Heartbeat runs only while the agent runs.
- Bundle id is now `blog.eliot.iphoneuse`: re-import the pairing file after installing.
