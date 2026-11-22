# This is the PUBLIC Pulse repo (lorah2262-ops/pulse)

Anyone can see this repo and download its builds. Only put things here that are meant for everyone.

## The two repos
| Repo | Visibility | What goes there |
|---|---|---|
| `lorah2262-ops/pulse` (this one) | public | Fixes and features for everyone who uses Pulse |
| `lorah2262-ops/pulse-personal` | private | Everything public, plus the owner's personal features |

## Rules
- Never add personal features or personal data here (for example the Onewheel/BMS control, the owner's names, numbers or keys). Those go to `pulse-personal`.
- No secrets in the code: the signing key's password is the `PULSE_KS_PASS` GitHub secret.
- Builds: every push to `main` builds on GitHub and becomes the latest release (build number = run number, under 1000). The app's Check button installs it.
- The watch face is `watch/Pulse.min.js` and its copy `app/src/main/assets/Pulse.min.js` (must be identical). Don't run `tools/build-face.sh`: `watch/Pulse.js` is older.
- After pushing a fix here, the private repo can take it with a merge (see its CLAUDE.md).
