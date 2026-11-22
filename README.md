# Pulse

**Pulse turns your Ollee Watch into a remote screen for your Android phone.**
Control your music, read notifications, ask an AI by voice, run your own shortcuts and play games with friends, all from the watch.

Pulse is two parts that work together:
- **Pulse app** (Android): runs in the background and talks to the watch over Bluetooth.
- **Pulse face** (`Pulse.min.js`): the watch face that shows everything.

> Pulse is a fan project. It isn't made by or affiliated with Ollee.

## Download
👉 **[Get the latest Pulse](https://github.com/lorah2262-ops/pulse/releases/latest)**

That page always has the newest build: **Pulse.apk** (the app) and **Pulse.min.js** (the watch face).

You need:
- An **Ollee Watch** that runs Forge faces
- An **Android phone**, Android 8 or newer (no iPhone app)

## Install
1. **Install the app.** On your phone, open the download link above and tap **Pulse.apk**. If Android asks, allow your browser to install apps.
2. **Put the face on the watch.** Download **Pulse.min.js** and install it on the watch with the Ollee app (Forge).
3. **Set up Pulse.** Open the Pulse app and follow **SETUP**: it asks for notification access, Bluetooth and the other permissions it needs, one at a time.
4. **Connect.** Open the Pulse face on the watch. The app shows **Connected** when they're talking.

**Using the Ollee app at the same time:** the watch talks to one app at a time. Tap **Disconnect** in Pulse before using the Ollee app, then connect again after.

**Had an older Pulse APK from someone?** If this one won't install over it ("App not installed"), uninstall the old Pulse once, then install this one.

## Updates
Pulse updates itself. On the Home screen, the update card's **Check** button downloads and installs the newest build. When the watch face needs updating too, the app tells you and can update it for you.

## What it does
- **Music remote:** play, pause, skip and change the volume of whatever is playing, with the song scrolling on the watch.
- **Notifications:** messages scroll across the watch, with rules for which apps get through and quiet times.
- **Ask AI by voice:** hold a button, ask a question, and read Gemini's answer on the watch. Uses your own free Gemini key. You can also ask about a photo.
- **Your shortcuts:** run MacroDroid macros from the watch and show their replies.
- **Calendar:** your next events on the watch, plus a reminder of when to leave.
- **Games with friends:** host an UNO-style card game or Buckshot on the watch. Friends play in their phone's browser, nearby or online. No app needed for them.
- **Watch faces:** keep a library of faces in the app and install them from the watch.
- **NFC tap:** stick an NFC tag on the watch and tap your phone to it for a quick action.
- **Battery saver:** quiet mode, batching and a night link-off to keep the watch going longer.

The full guide, with every button and feature, is in **[PULSE2-SETUP.md](PULSE2-SETUP.md)**.

## Privacy
- Notifications and music info go from your phone to your watch over Bluetooth. Pulse has no server of its own.
- **Ask AI** sends your question (your voice recording or photo) to Google Gemini, using your key.
- **Online games** use a Firebase database you set up yourself. Local games stay on your Wi-Fi.

## Problems or ideas?
Open an [issue](https://github.com/lorah2262-ops/pulse/issues) with what happened and, if you can, a screenshot.

---

## For developers
- `app/` is the Android app: plain Java, no libraries.
- `app/src/main/assets/Pulse.min.js` is the watch face the app ships and installs. Face changes are made to this file directly.
- `app/src/main/assets/play.html` is the game page friends open in their browser.
- Every push to `main` builds on GitHub (`.github/workflows/build.yml`) and publishes **Pulse.apk** and **Pulse.min.js** as the latest release. Each build gets a higher version number and the same signing key, so it installs over the previous one.
