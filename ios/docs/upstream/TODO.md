# TODO

Feature scope: `docs/FEATURES.md`. Left over:

## Verify on a phone

- [ ] Built-in loopback VPN: "Add VPN Configuration" prompt, then connect to `10.7.0.1` on Wi-Fi.
      Maintainers report it does NOT work cellular-only on iOS 26.6/27 (LocalDevVPN issue #31).
      It takes the single VPN slot (stops Tailscale etc. while on); it is left on after a run.
- [ ] idevice built with `ring` instead of `aws-lc`: pairing/TLS still work.
- [ ] Heartbeat during agent runs (new, not exercised yet).
- [ ] Stop button in the Live Activity actually stops the run.
- [ ] Full LLM run on the 16 Pro (iOS 27); Gemini 3.5 Flash Lite quality
- [ ] Reboot test and 30 min soak (Notion plan T7/T8)

## Other

- [ ] App icon is a placeholder bolt (`iPhoneUse/AppIcon.icon`).
- [ ] GitHub link in Settings points to `github.com/EliotAndres/iphone-use-app`, which doesn't exist yet.
