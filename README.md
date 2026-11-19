# Pulse

Turns an Ollee watch into a remote screen for an Android phone.

| Folder | What |
|---|---|
| `app/` | PulseBridge Android app (plain Java, no libraries) |
| `watch/Pulse.min.js` | The watch face, loaded with Ollee Forge (the app also carries a copy in `app/src/main/assets/`) |
| `watch/Pulse.js` | Readable face source (**older than Pulse.min.js**, see note) |
| `tools/build-face.sh` | Minify `watch/Pulse.js` → `watch/Pulse.min.js` and copy it into the app |
| `PULSE2-SETUP.md` | User guide: buttons, MacroDroid, Ask AI |

## Getting a build
Every push to `main` builds on GitHub and publishes **Pulse.apk** + **Pulse.min.js** under
**Releases → Latest**. Open that page on the phone, download, install. Each build has a higher
version number and the same signing key, so it installs over the previous one.

Note: `watch/Pulse.js` is behind the shipped `Pulse.min.js`. Until the newest readable source is
added, face changes are made to `Pulse.min.js` directly; don't run `build-face.sh` or it will
replace the current face with the older one.
